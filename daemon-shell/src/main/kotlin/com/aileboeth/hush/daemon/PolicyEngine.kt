package com.aileboeth.hush.daemon

import com.aileboeth.hush.contract.AppConfigSnapshot
import com.aileboeth.hush.contract.AppGroup
import com.aileboeth.hush.contract.AppKey
import com.aileboeth.hush.contract.AppRestriction
import com.aileboeth.hush.contract.AppRuntimeSample
import com.aileboeth.hush.contract.AppTarget
import com.aileboeth.hush.contract.BatchActionResult
import com.aileboeth.hush.contract.CleanupAction
import com.aileboeth.hush.contract.CleanupEvent
import com.aileboeth.hush.contract.CleanupTrigger
import com.aileboeth.hush.contract.HushLog
import com.aileboeth.hush.contract.MemoryOverview
import com.aileboeth.hush.contract.PolicyConfig
import com.aileboeth.hush.contract.SafetyGuardrails
import com.aileboeth.hush.contract.SecondaryUserInfo
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * In-daemon automated policy engine running at `uid=2000`.
 * Supports primary (`user 0`), clone/dual-space (`user 999`, etc.), built-in tiers,
 * custom app groups, batch group actions, and secondary Android user whole-space stop.
 */
class PolicyEngine(
    private val bridge: SystemBridge,
    initialConfig: AppConfigSnapshot = AppConfigSnapshot(),
) {
    private val lock = Any()
    private val eventIdGen = AtomicLong(1L)

    @Volatile
    private var config: AppConfigSnapshot = initialConfig

    private var currentForeground: AppTarget? = null
    private var lastObservedUserId: Int = 0
    private val secondaryUserLeftMs = ConcurrentHashMap<Int, Long>()
    private val lastForegroundMs = ConcurrentHashMap<AppTarget, Long>()
    private val lastSoftTrimMs = ConcurrentHashMap<AppTarget, Long>()
    private var screenOffSinceMs: Long? = null
    private var screenOffCleanupExecuted: Boolean = false

    private val eventLog = ArrayDeque<CleanupEvent>(MAX_LOG_ENTRIES)
    private val restrictionLedger = ConcurrentHashMap<AppTarget, AppRestriction>()

    init {
        initialConfig.appliedRestrictions.forEach { (rawKey, r) ->
            restrictionLedger[AppKey.decode(rawKey)] = r
        }
    }

    fun updateConfig(newConfig: AppConfigSnapshot) {
        synchronized(lock) {
            config = newConfig
            restrictionLedger.clear()
            newConfig.appliedRestrictions.forEach { (rawKey, r) ->
                restrictionLedger[AppKey.decode(rawKey)] = r
            }
        }
    }

    fun getConfig(): AppConfigSnapshot = config

    fun getLogs(): List<CleanupEvent> = synchronized(lock) { eventLog.toList().reversed() }

    fun clearLogs() = synchronized(lock) { eventLog.clear() }

    fun getTodayStats(): Pair<Int, Long> = synchronized(lock) {
        val dayStart = startOfDayMs(bridge.nowMs())
        var count = 0
        var freedKb = 0L
        for (e in eventLog) {
            if (e.timestampMs >= dayStart &&
                (e.action == CleanupAction.FORCE_STOP ||
                    e.action == CleanupAction.SOFT_TRIM ||
                    e.action == CleanupAction.STOP_USER) &&
                e.skippedReason == null
            ) {
                count++
                freedKb += e.freedKb
            }
        }
        count to freedKb
    }

    fun tick() {
        val snap = config
        val now = bridge.nowMs()
        val currentUser = bridge.getCurrentUserId()
        val fg = bridge.getForegroundTarget()
        val awake = bridge.isScreenAwake()

        if (currentUser != lastObservedUserId) {
            if (lastObservedUserId > 0) {
                secondaryUserLeftMs[lastObservedUserId] = now
            }
            if (currentUser > 0) {
                secondaryUserLeftMs.remove(currentUser)
            }
            lastObservedUserId = currentUser
        }

        if (fg != null) {
            val prevFg = currentForeground
            if (prevFg != null && prevFg != fg) {
                lastForegroundMs[prevFg] = now
            }
            currentForeground = fg
            lastForegroundMs[fg] = now
        }

        if (!awake) {
            if (screenOffSinceMs == null) {
                screenOffSinceMs = now
                screenOffCleanupExecuted = false
            }
        } else {
            screenOffSinceMs = null
            screenOffCleanupExecuted = false
        }

        if (!snap.policy.enabled) return

        // Always evaluate secondary Android user auto-stop even when zero apps are tagged
        evaluateSecondaryUsers(snap.policy, currentUser, awake, now)

        val effectiveGroups = snap.resolveEffectiveGroups().toMutableMap()
        if (snap.policy.softTrimIncludeDualSpace) {
            for (clone in bridge.listClonePackages()) {
                if (clone !in effectiveGroups && effectiveGroups[AppTarget(clone.packageName, 0)] == AppGroup.SOFT_TRIM) {
                    effectiveGroups[clone] = AppGroup.SOFT_TRIM
                }
            }
        }

        val managedEntries = effectiveGroups.filterValues {
            it == AppGroup.FORCE_STOP || it == AppGroup.SOFT_TRIM
        }
        if (managedEntries.isEmpty()) return

        val activeAudio = if (snap.policy.exemptActiveAudio) {
            bridge.getActiveAudioTargets()
        } else {
            emptySet()
        }

        // Single multi-user O(1) batch snapshot across primary + clone targets
        val batchSamples = bridge.sampleTargetsBatch(managedEntries.keys.toList())

        // 1. Screen-off batch cleanup (runs once after screenOffDelayMinutes)
        val offSince = screenOffSinceMs
        if (!awake &&
            snap.policy.screenOffCleanupEnabled &&
            !screenOffCleanupExecuted &&
            offSince != null &&
            (now - offSince) >= snap.policy.screenOffDelayMinutes * 60_000L
        ) {
            screenOffCleanupExecuted = true
            runBatchCleanup(
                targets = managedEntries,
                samples = batchSamples,
                activeAudio = activeAudio,
                trigger = CleanupTrigger.SCREEN_OFF,
                policy = snap.policy,
                now = now,
                ignoreForeground = true,
            )
            return
        }

        // 2. Per-app background timeout cleanup
        val timeoutMs = snap.policy.backgroundTimeoutMinutes * 60_000L
        val evictedThisTick = HashSet<AppTarget>()
        for ((target, group) in managedEntries) {
            if (target == currentForeground) continue
            if (SafetyGuardrails.isSystemProtected(target.packageName)) continue

            val sample = batchSamples[target]
            if (sample == null || !sample.running) {
                lastForegroundMs.remove(target)
                continue
            }

            val lastFg = lastForegroundMs[target]
            if (lastFg == null) {
                lastForegroundMs[target] = now
                continue
            }
            if ((now - lastFg) < timeoutMs) continue

            if (group == AppGroup.SOFT_TRIM) {
                val lastTrim = lastSoftTrimMs[target] ?: 0L
                if (now - lastTrim < timeoutMs) continue
            }

            if (snap.policy.exemptActiveAudio && target in activeAudio) {
                continue
            }

            when (group) {
                AppGroup.FORCE_STOP -> {
                    executeForceStop(target, sample.totalPssKb, CleanupTrigger.BACKGROUND_TIMEOUT, now)
                    evictedThisTick.add(target)
                }
                AppGroup.SOFT_TRIM -> executeSoftTrim(target, sample.totalPssKb, CleanupTrigger.BACKGROUND_TIMEOUT, now)
                else -> Unit
            }
        }

        // 3. Memory pressure watchdog (respects minimum grace period & stops once RAM recovers)
        if (snap.policy.memoryPressureEnabled) {
            evaluateMemoryPressure(managedEntries, batchSamples, evictedThisTick, activeAudio, snap.policy, now)
        }
    }

    fun manualForceStop(packageName: String, userId: Int = 0): Result<Long> {
        if (SafetyGuardrails.isSystemProtected(packageName)) {
            return Result.failure(SecurityException("系统关键组件受保护，不能强杀"))
        }
        val target = AppTarget(packageName, userId)
        val before = bridge.samplePackage(packageName, userId = userId)
        val ok = bridge.forceStop(packageName, userId = userId)
        if (!ok) return Result.failure(IllegalStateException("强杀 $packageName 失败"))
        val freed = before.totalPssKb.coerceAtLeast(0L)
        recordTargetEvent(
            target = target,
            action = CleanupAction.FORCE_STOP,
            trigger = CleanupTrigger.MANUAL,
            freedKb = freed,
        )
        return Result.success(freed)
    }

    fun manualForceStopBatch(targets: List<AppTarget>): Result<BatchActionResult> {
        val unique = targets.distinct()
        if (unique.isEmpty()) return Result.success(BatchActionResult())
        val batchSamples = bridge.sampleTargetsBatch(unique)
        var affected = 0
        var skipped = 0
        var freedTotal = 0L
        val now = bridge.nowMs()
        for (target in unique) {
            if (SafetyGuardrails.isSystemProtected(target.packageName)) {
                skipped++
                continue
            }
            val sample = batchSamples[target]
            if (sample == null || !sample.running) continue
            if (bridge.forceStop(target.packageName, userId = target.userId)) {
                val freed = sample.totalPssKb.coerceAtLeast(0L)
                affected++
                freedTotal += freed
                recordTargetEvent(
                    target = target,
                    action = CleanupAction.FORCE_STOP,
                    trigger = CleanupTrigger.MANUAL,
                    freedKb = freed,
                    timestampMs = now,
                )
            } else {
                skipped++
            }
        }
        HushLog.i("Policy", "Batch force-stop count=$affected skipped=$skipped freedKb=$freedTotal")
        return Result.success(BatchActionResult(affectedCount = affected, skippedCount = skipped, freedKb = freedTotal))
    }

    fun manualSoftTrim(packageName: String, userId: Int = 0): Result<Long> {
        if (SafetyGuardrails.isSystemProtected(packageName)) {
            return Result.failure(SecurityException("系统关键组件受保护，不能操作"))
        }
        val target = AppTarget(packageName, userId)
        val before = bridge.samplePackage(packageName, userId = userId)
        val ok = bridge.softTrim(packageName, userId = userId)
        if (!ok) return Result.failure(IllegalStateException("为 $packageName 温和瘦身失败"))
        val after = bridge.samplePackage(packageName, userId = userId)
        val freed = (before.totalPssKb - after.totalPssKb).coerceAtLeast(0L)
        lastSoftTrimMs[target] = bridge.nowMs()
        recordTargetEvent(
            target = target,
            action = CleanupAction.SOFT_TRIM,
            trigger = CleanupTrigger.MANUAL,
            freedKb = freed,
        )
        return Result.success(freed)
    }

    fun manualSoftTrimBatch(targets: List<AppTarget>): Result<BatchActionResult> {
        val unique = targets.distinct()
        if (unique.isEmpty()) return Result.success(BatchActionResult())
        val batchSamples = bridge.sampleTargetsBatch(unique)
        var affected = 0
        var skipped = 0
        var freedTotal = 0L
        val now = bridge.nowMs()
        for (target in unique) {
            if (SafetyGuardrails.isSystemProtected(target.packageName)) {
                skipped++
                continue
            }
            val before = batchSamples[target]
            if (before == null || !before.running) continue
            if (bridge.softTrim(target.packageName, userId = target.userId)) {
                lastSoftTrimMs[target] = now
                val after = bridge.samplePackage(target.packageName, userId = target.userId)
                val freed = (before.totalPssKb - after.totalPssKb).coerceAtLeast(0L)
                affected++
                freedTotal += freed
                recordTargetEvent(
                    target = target,
                    action = CleanupAction.SOFT_TRIM,
                    trigger = CleanupTrigger.MANUAL,
                    freedKb = freed,
                    timestampMs = now,
                )
            } else {
                skipped++
            }
        }
        HushLog.i("Policy", "Batch soft-trim count=$affected skipped=$skipped freedKb=$freedTotal")
        return Result.success(BatchActionResult(affectedCount = affected, skippedCount = skipped, freedKb = freedTotal))
    }

    fun setRestriction(packageName: String, restriction: AppRestriction, userId: Int = 0): Result<Unit> {
        val target = AppTarget(packageName, userId)
        val group = config.resolveEffectiveGroups()[target] ?: AppGroup.UNSET
        if (restriction.hasAnyRestriction && SafetyGuardrails.isRestrictionBlocked(packageName, group)) {
            return Result.failure(SecurityException("保消息瘦身档与保护档不允许限制后台，以防漏消息"))
        }
        val ok = bridge.applyRestriction(packageName, restriction, userId = userId)
        if (!ok) {
            val reason = bridge.lastRestrictionError() ?: "修改 $packageName 的后台限制失败"
            return Result.failure(IllegalStateException(reason))
        }

        if (restriction.hasAnyRestriction) {
            restrictionLedger[target] = restriction
        } else {
            restrictionLedger.remove(target)
        }
        recordTargetEvent(
            target = target,
            action = CleanupAction.RESTRICT_CHANGED,
            trigger = CleanupTrigger.MANUAL,
            freedKb = 0L,
        )
        return Result.success(Unit)
    }

    fun restoreAllRestrictions(): Result<Int> {
        val targets = restrictionLedger.keys.toList()
        var restored = 0
        for (target in targets) {
            if (bridge.applyRestriction(target.packageName, AppRestriction(), userId = target.userId)) {
                restrictionLedger.remove(target)
                restored++
            }
        }
        recordEvent(
            packageName = SafetyGuardrails.SELF_PACKAGE,
            action = CleanupAction.RESTORE_ALL,
            trigger = CleanupTrigger.MANUAL,
            freedKb = 0L,
        )
        return Result.success(restored)
    }

    fun samplePackages(packages: List<String>): List<AppRuntimeSample> =
        sampleTargets(packages.map { AppTarget(it, 0) })

    fun sampleTargets(targets: List<AppTarget>): List<AppRuntimeSample> {
        if (targets.isEmpty()) return emptyList()
        val activeAudio = bridge.getActiveAudioTargets()
        val batch = bridge.sampleTargetsBatch(targets)
        return targets.map { target ->
            val sample = batch[target] ?: AppRuntimeSample(packageName = target.packageName, userId = target.userId)
            sample.copy(
                isPlayingAudio = target in activeAudio,
                lastForegroundMs = lastForegroundMs[target] ?: 0L,
            )
        }
    }

    fun listClonePackages(): List<AppTarget> = bridge.listClonePackages()

    fun readMemory(): MemoryOverview = bridge.readMemoryOverview()

    fun listSecondaryUsers(): List<SecondaryUserInfo> = bridge.listSecondaryUsers()

    fun manualStopUser(userId: Int): Result<Long> {
        if (userId <= 0) {
            return Result.failure(SecurityException("主系统用户受保护，不能停机"))
        }
        if (bridge.getCurrentUserId() == userId) {
            return Result.failure(IllegalStateException("当前正处于该空间中，请先切回主系统"))
        }
        val target = bridge.listSecondaryUsers().firstOrNull { it.userId == userId }
        if (target != null && !target.running) {
            secondaryUserLeftMs.remove(userId)
            return Result.success(0L)
        }
        val memBefore = bridge.readMemoryOverview()
        val ok = bridge.stopUser(userId)
        if (!ok) {
            HushLog.w("Policy", "manualStopUser($userId) failed")
            return Result.failure(IllegalStateException("停机第二系统 (User $userId) 失败"))
        }
        secondaryUserLeftMs.remove(userId)
        val memAfter = bridge.readMemoryOverview()
        val deltaAvail = (memAfter.memAvailableKb - memBefore.memAvailableKb).coerceAtLeast(0L)
        val freedKb = maxOf(deltaAvail, target?.totalPssKb ?: 0L)
        val displayName = target?.name?.ifBlank { "User $userId" } ?: "User $userId"
        HushLog.i("Policy", "Stopped secondary user=$userId ($displayName) freedKb=$freedKb trigger=MANUAL")
        recordEvent(
            packageName = "user:$userId",
            appLabel = "第二系统 · $displayName",
            action = CleanupAction.STOP_USER,
            trigger = CleanupTrigger.MANUAL,
            freedKb = freedKb,
        )
        return Result.success(freedKb)
    }

    private fun evaluateSecondaryUsers(
        policy: PolicyConfig,
        currentUser: Int,
        awake: Boolean,
        now: Long,
    ) {
        if (!policy.autoStopSecondaryUsers && !policy.screenOffStopSecondaryUsers) return
        val users = bridge.listSecondaryUsers()
        if (users.isEmpty()) {
            secondaryUserLeftMs.clear()
            return
        }
        val offSince = screenOffSinceMs
        for (u in users) {
            if (!u.running) {
                secondaryUserLeftMs.remove(u.userId)
                continue
            }
            // Auto-stop strictly targets full secondary systems, never user 0, active user, clone/Xspace, or Work Profile
            if (!u.canStop || u.userId == currentUser || !u.isFullSecondarySystem) continue

            if (!awake &&
                policy.screenOffStopSecondaryUsers &&
                offSince != null &&
                (now - offSince) >= policy.screenOffDelayMinutes * 60_000L
            ) {
                executeAutoStopUser(u, CleanupTrigger.SCREEN_OFF, now)
                continue
            }

            if (policy.autoStopSecondaryUsers) {
                val leftAt = secondaryUserLeftMs.putIfAbsent(u.userId, now) ?: now
                val delayMs = policy.secondaryUserStopDelayMinutes * 60_000L
                if (now - leftAt >= delayMs) {
                    executeAutoStopUser(u, CleanupTrigger.BACKGROUND_TIMEOUT, now)
                }
            }
        }
    }

    private fun executeAutoStopUser(user: SecondaryUserInfo, trigger: CleanupTrigger, now: Long) {
        val memBefore = bridge.readMemoryOverview()
        if (!bridge.stopUser(user.userId)) return
        secondaryUserLeftMs.remove(user.userId)
        val memAfter = bridge.readMemoryOverview()
        val deltaAvail = (memAfter.memAvailableKb - memBefore.memAvailableKb).coerceAtLeast(0L)
        val freedKb = maxOf(deltaAvail, user.totalPssKb)
        val displayName = user.name.ifBlank { "User ${user.userId}" }
        HushLog.i("Policy", "Auto-stopped secondary user=${user.userId} ($displayName) freedKb=$freedKb trigger=$trigger")
        recordEvent(
            packageName = "user:${user.userId}",
            appLabel = "第二系统 · $displayName",
            action = CleanupAction.STOP_USER,
            trigger = trigger,
            freedKb = freedKb,
            timestampMs = now,
        )
    }

    private fun runBatchCleanup(
        targets: Map<AppTarget, AppGroup>,
        samples: Map<AppTarget, AppRuntimeSample>,
        activeAudio: Set<AppTarget>,
        trigger: CleanupTrigger,
        policy: PolicyConfig,
        now: Long,
        ignoreForeground: Boolean,
    ) {
        for ((target, group) in targets) {
            if (!ignoreForeground && target == currentForeground) continue
            if (SafetyGuardrails.isSystemProtected(target.packageName)) continue
            if (policy.exemptActiveAudio && target in activeAudio) {
                recordTargetEvent(target, groupToAction(group), trigger, 0L, skippedReason = "正在播放音频", timestampMs = now)
                continue
            }
            val sample = samples[target] ?: continue
            if (!sample.running) continue

            when (group) {
                AppGroup.FORCE_STOP -> executeForceStop(target, sample.totalPssKb, trigger, now)
                AppGroup.SOFT_TRIM -> executeSoftTrim(target, sample.totalPssKb, trigger, now)
                else -> Unit
            }
        }
    }

    private fun evaluateMemoryPressure(
        targets: Map<AppTarget, AppGroup>,
        samples: Map<AppTarget, AppRuntimeSample>,
        alreadyEvicted: Set<AppTarget>,
        activeAudio: Set<AppTarget>,
        policy: PolicyConfig,
        now: Long,
    ) {
        val mem = bridge.readMemoryOverview()
        val minAvailKb = policy.minAvailableRamMb * 1024L
        var lowAvailableRam = mem.memAvailableKb > 0L && mem.memAvailableKb < minAvailKb

        val runningForceStop = ArrayList<Pair<AppTarget, AppRuntimeSample>>()
        var managedPssTotalKb = 0L
        for ((target, group) in targets) {
            if (group != AppGroup.FORCE_STOP) continue
            if (target in alreadyEvicted || target == currentForeground) continue
            if (SafetyGuardrails.isSystemProtected(target.packageName)) continue
            if (policy.exemptActiveAudio && target in activeAudio) continue
            val sample = samples[target] ?: continue
            if (!sample.running) continue

            managedPssTotalKb += sample.totalPssKb
            val lastFg = lastForegroundMs[target] ?: now
            if (now - lastFg >= MIN_PRESSURE_GRACE_MS) {
                runningForceStop.add(target to sample)
            }
        }

        val budgetKb = policy.managedBudgetMb * 1024L
        if (!lowAvailableRam && managedPssTotalKb <= budgetKb) return

        runningForceStop.sortBy { (target, _) -> lastForegroundMs[target] ?: 0L }
        var remainingKb = managedPssTotalKb
        for ((target, sample) in runningForceStop) {
            executeForceStop(target, sample.totalPssKb, CleanupTrigger.MEMORY_PRESSURE, now)
            remainingKb = (remainingKb - sample.totalPssKb).coerceAtLeast(0L)
            if (lowAvailableRam) {
                val updatedMem = bridge.readMemoryOverview()
                lowAvailableRam = updatedMem.memAvailableKb > 0L && updatedMem.memAvailableKb < minAvailKb
            }
            if (!lowAvailableRam && remainingKb <= budgetKb) break
        }

        if (lowAvailableRam) {
            val cooldownMs = policy.backgroundTimeoutMinutes * 60_000L
            for ((target, group) in targets) {
                if (group != AppGroup.SOFT_TRIM) continue
                if (target == currentForeground) continue
                if (policy.exemptActiveAudio && target in activeAudio) continue
                val lastFg = lastForegroundMs[target] ?: now
                if (now - lastFg < MIN_PRESSURE_GRACE_MS) continue
                val lastTrim = lastSoftTrimMs[target] ?: 0L
                if (now - lastTrim < cooldownMs) continue
                val sample = samples[target] ?: continue
                if (sample.running) {
                    executeSoftTrim(target, sample.totalPssKb, CleanupTrigger.MEMORY_PRESSURE, now)
                }
            }
        }
    }

    private fun executeForceStop(target: AppTarget, beforePssKb: Long, trigger: CleanupTrigger, now: Long) {
        if (bridge.forceStop(target.packageName, userId = target.userId)) {
            recordTargetEvent(
                target = target,
                action = CleanupAction.FORCE_STOP,
                trigger = trigger,
                freedKb = beforePssKb.coerceAtLeast(0L),
                timestampMs = now,
            )
        }
    }

    private fun executeSoftTrim(target: AppTarget, beforePssKb: Long, trigger: CleanupTrigger, now: Long) {
        if (bridge.softTrim(target.packageName, userId = target.userId)) {
            lastSoftTrimMs[target] = now
            val after = bridge.samplePackage(target.packageName, userId = target.userId)
            val freed = (beforePssKb - after.totalPssKb).coerceAtLeast(0L)
            recordTargetEvent(
                target = target,
                action = CleanupAction.SOFT_TRIM,
                trigger = trigger,
                freedKb = freed,
                timestampMs = now,
            )
        }
    }

    private fun groupToAction(group: AppGroup): CleanupAction =
        if (group == AppGroup.SOFT_TRIM) CleanupAction.SOFT_TRIM else CleanupAction.FORCE_STOP

    private fun recordTargetEvent(
        target: AppTarget,
        action: CleanupAction,
        trigger: CleanupTrigger,
        freedKb: Long,
        skippedReason: String? = null,
        timestampMs: Long = bridge.nowMs(),
    ) {
        val defaultLabel = if (target.isDualSpace) "${target.packageName} (双开)" else target.packageName
        recordEvent(
            packageName = target.key,
            appLabel = defaultLabel,
            action = action,
            trigger = trigger,
            freedKb = freedKb,
            skippedReason = skippedReason,
            timestampMs = timestampMs,
        )
    }

    private fun recordEvent(
        packageName: String,
        action: CleanupAction,
        trigger: CleanupTrigger,
        freedKb: Long,
        skippedReason: String? = null,
        timestampMs: Long = bridge.nowMs(),
        appLabel: String = packageName,
    ) {
        synchronized(lock) {
            if (eventLog.size >= MAX_LOG_ENTRIES) {
                eventLog.removeFirst()
            }
            eventLog.addLast(
                CleanupEvent(
                    id = eventIdGen.getAndIncrement(),
                    timestampMs = timestampMs,
                    packageName = packageName,
                    appLabel = appLabel,
                    action = action,
                    trigger = trigger,
                    freedKb = freedKb,
                    skippedReason = skippedReason,
                )
            )
        }
    }

    private fun startOfDayMs(nowMs: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = nowMs
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    companion object {
        const val MAX_LOG_ENTRIES = 200
        const val MIN_PRESSURE_GRACE_MS = 60_000L
    }
}
