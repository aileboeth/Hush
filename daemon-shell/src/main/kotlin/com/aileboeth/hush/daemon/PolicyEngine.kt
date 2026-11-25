package com.aileboeth.hush.daemon

import com.aileboeth.hush.contract.AppConfigSnapshot
import com.aileboeth.hush.contract.AppGroup
import com.aileboeth.hush.contract.AppRestriction
import com.aileboeth.hush.contract.AppRuntimeSample
import com.aileboeth.hush.contract.CleanupAction
import com.aileboeth.hush.contract.CleanupEvent
import com.aileboeth.hush.contract.CleanupTrigger
import com.aileboeth.hush.contract.MemoryOverview
import com.aileboeth.hush.contract.PolicyConfig
import com.aileboeth.hush.contract.SafetyGuardrails
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * In-daemon automated policy engine running at `uid=2000`.
 */
class PolicyEngine(
    private val bridge: SystemBridge,
    initialConfig: AppConfigSnapshot = AppConfigSnapshot(),
) {
    private val lock = Any()
    private val eventIdGen = AtomicLong(1L)

    @Volatile
    private var config: AppConfigSnapshot = initialConfig

    private var currentForegroundPkg: String? = null
    private val lastForegroundMs = ConcurrentHashMap<String, Long>()
    private val lastSoftTrimMs = ConcurrentHashMap<String, Long>()
    private var screenOffSinceMs: Long? = null
    private var screenOffCleanupExecuted: Boolean = false

    private val eventLog = ArrayDeque<CleanupEvent>(MAX_LOG_ENTRIES)
    private val restrictionLedger = ConcurrentHashMap<String, AppRestriction>()

    init {
        restrictionLedger.putAll(initialConfig.appliedRestrictions)
    }

    fun updateConfig(newConfig: AppConfigSnapshot) {
        synchronized(lock) {
            config = newConfig
            restrictionLedger.clear()
            restrictionLedger.putAll(newConfig.appliedRestrictions)
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
                (e.action == CleanupAction.FORCE_STOP || e.action == CleanupAction.SOFT_TRIM) &&
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
        val fg = bridge.getForegroundPackage()
        val awake = bridge.isScreenAwake()

        if (fg != null) {
            if (currentForegroundPkg != null && currentForegroundPkg != fg) {
                lastForegroundMs[currentForegroundPkg!!] = now
            }
            currentForegroundPkg = fg
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

        val managedEntries = snap.appGroups.filterValues {
            it == AppGroup.FORCE_STOP || it == AppGroup.SOFT_TRIM
        }
        if (managedEntries.isEmpty()) return

        val activeAudio = if (snap.policy.exemptActiveAudio) {
            bridge.getActiveAudioPackages()
        } else {
            emptySet()
        }

        // Single batch snapshot for all managed apps so tick stays O(1) in shell forks
        val batchSamples = bridge.samplePackagesBatch(managedEntries.keys.toList(), userId = 0)

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
        val evictedThisTick = HashSet<String>()
        for ((pkg, group) in managedEntries) {
            if (pkg == currentForegroundPkg) continue
            if (SafetyGuardrails.isSystemProtected(pkg)) continue

            val sample = batchSamples[pkg]
            if (sample == null || !sample.running) {
                lastForegroundMs.remove(pkg)
                continue
            }

            val lastFg = lastForegroundMs[pkg]
            if (lastFg == null) {
                lastForegroundMs[pkg] = now
                continue
            }
            if ((now - lastFg) < timeoutMs) continue

            if (group == AppGroup.SOFT_TRIM) {
                val lastTrim = lastSoftTrimMs[pkg] ?: 0L
                if (now - lastTrim < timeoutMs) continue
            }

            if (snap.policy.exemptActiveAudio && pkg in activeAudio) {
                continue
            }

            when (group) {
                AppGroup.FORCE_STOP -> {
                    executeForceStop(pkg, sample.totalPssKb, CleanupTrigger.BACKGROUND_TIMEOUT, now)
                    evictedThisTick.add(pkg)
                }
                AppGroup.SOFT_TRIM -> executeSoftTrim(pkg, sample.totalPssKb, CleanupTrigger.BACKGROUND_TIMEOUT, now)
                else -> Unit
            }
        }

        // 3. Memory pressure watchdog (respects minimum grace period & stops once RAM recovers)
        if (snap.policy.memoryPressureEnabled) {
            evaluateMemoryPressure(managedEntries, batchSamples, evictedThisTick, activeAudio, snap.policy, now)
        }
    }

    fun manualForceStop(packageName: String): Result<Long> {
        if (SafetyGuardrails.isSystemProtected(packageName)) {
            return Result.failure(SecurityException("系统关键组件受保护，不能强杀"))
        }
        val before = bridge.samplePackage(packageName, userId = 0)
        val ok = bridge.forceStop(packageName, userId = 0)
        if (!ok) return Result.failure(IllegalStateException("强杀 $packageName 失败"))
        val freed = before.totalPssKb.coerceAtLeast(0L)
        recordEvent(
            packageName = packageName,
            action = CleanupAction.FORCE_STOP,
            trigger = CleanupTrigger.MANUAL,
            freedKb = freed,
        )
        return Result.success(freed)
    }

    fun manualSoftTrim(packageName: String): Result<Long> {
        if (SafetyGuardrails.isSystemProtected(packageName)) {
            return Result.failure(SecurityException("系统关键组件受保护，不能操作"))
        }
        val before = bridge.samplePackage(packageName, userId = 0)
        val ok = bridge.softTrim(packageName, userId = 0)
        if (!ok) return Result.failure(IllegalStateException("为 $packageName 温和瘦身失败"))
        val after = bridge.samplePackage(packageName, userId = 0)
        val freed = (before.totalPssKb - after.totalPssKb).coerceAtLeast(0L)
        lastSoftTrimMs[packageName] = bridge.nowMs()
        recordEvent(
            packageName = packageName,
            action = CleanupAction.SOFT_TRIM,
            trigger = CleanupTrigger.MANUAL,
            freedKb = freed,
        )
        return Result.success(freed)
    }

    fun setRestriction(packageName: String, restriction: AppRestriction): Result<Unit> {
        val group = config.appGroups[packageName] ?: AppGroup.UNSET
        if (restriction.hasAnyRestriction && SafetyGuardrails.isRestrictionBlocked(packageName, group)) {
            return Result.failure(SecurityException("保消息瘦身档与保护档不允许限制后台，以防漏消息"))
        }
        val ok = bridge.applyRestriction(packageName, restriction, userId = 0)
        if (!ok) return Result.failure(IllegalStateException("修改 $packageName 的后台限制失败"))

        if (restriction.hasAnyRestriction) {
            restrictionLedger[packageName] = restriction
        } else {
            restrictionLedger.remove(packageName)
        }
        recordEvent(
            packageName = packageName,
            action = CleanupAction.RESTRICT_CHANGED,
            trigger = CleanupTrigger.MANUAL,
            freedKb = 0L,
        )
        return Result.success(Unit)
    }

    fun restoreAllRestrictions(): Result<Int> {
        val packages = restrictionLedger.keys.toList()
        var restored = 0
        for (pkg in packages) {
            if (bridge.applyRestriction(pkg, AppRestriction(), userId = 0)) {
                restrictionLedger.remove(pkg)
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

    fun samplePackages(packages: List<String>): List<AppRuntimeSample> {
        val activeAudio = bridge.getActiveAudioPackages()
        val batch = bridge.samplePackagesBatch(packages, userId = 0)
        return packages.map { pkg ->
            val sample = batch[pkg] ?: AppRuntimeSample(packageName = pkg, userId = 0)
            sample.copy(
                isPlayingAudio = pkg in activeAudio,
                lastForegroundMs = lastForegroundMs[pkg] ?: 0L,
            )
        }
    }

    fun readMemory(): MemoryOverview = bridge.readMemoryOverview()

    private fun runBatchCleanup(
        targets: Map<String, AppGroup>,
        samples: Map<String, AppRuntimeSample>,
        activeAudio: Set<String>,
        trigger: CleanupTrigger,
        policy: PolicyConfig,
        now: Long,
        ignoreForeground: Boolean,
    ) {
        for ((pkg, group) in targets) {
            if (!ignoreForeground && pkg == currentForegroundPkg) continue
            if (SafetyGuardrails.isSystemProtected(pkg)) continue
            if (policy.exemptActiveAudio && pkg in activeAudio) {
                recordEvent(pkg, groupToAction(group), trigger, 0L, skippedReason = "正在播放音频")
                continue
            }
            val sample = samples[pkg] ?: continue
            if (!sample.running) continue

            when (group) {
                AppGroup.FORCE_STOP -> executeForceStop(pkg, sample.totalPssKb, trigger, now)
                AppGroup.SOFT_TRIM -> executeSoftTrim(pkg, sample.totalPssKb, trigger, now)
                else -> Unit
            }
        }
    }

    private fun evaluateMemoryPressure(
        targets: Map<String, AppGroup>,
        samples: Map<String, AppRuntimeSample>,
        alreadyEvicted: Set<String>,
        activeAudio: Set<String>,
        policy: PolicyConfig,
        now: Long,
    ) {
        val mem = bridge.readMemoryOverview()
        val minAvailKb = policy.minAvailableRamMb * 1024L
        var lowAvailableRam = mem.memAvailableKb > 0L && mem.memAvailableKb < minAvailKb

        val runningForceStop = ArrayList<Pair<String, AppRuntimeSample>>()
        var managedPssTotalKb = 0L
        for ((pkg, group) in targets) {
            if (group != AppGroup.FORCE_STOP) continue
            if (pkg in alreadyEvicted || pkg == currentForegroundPkg) continue
            if (SafetyGuardrails.isSystemProtected(pkg)) continue
            if (policy.exemptActiveAudio && pkg in activeAudio) continue
            val sample = samples[pkg] ?: continue
            if (!sample.running) continue

            managedPssTotalKb += sample.totalPssKb
            // Respect a minimum 60-second grace window so an app you just switched away from
            // seconds ago (e.g. to reply to a message) is not insta-killed under mild pressure.
            val lastFg = lastForegroundMs[pkg] ?: now
            if (now - lastFg >= MIN_PRESSURE_GRACE_MS) {
                runningForceStop.add(pkg to sample)
            }
        }

        val budgetKb = policy.managedBudgetMb * 1024L
        if (!lowAvailableRam && managedPssTotalKb <= budgetKb) return

        // Evict least-recently-used FORCE_STOP apps one at a time until both budget & RAM recover
        runningForceStop.sortBy { (pkg, _) -> lastForegroundMs[pkg] ?: 0L }
        var remainingKb = managedPssTotalKb
        for ((pkg, sample) in runningForceStop) {
            executeForceStop(pkg, sample.totalPssKb, CleanupTrigger.MEMORY_PRESSURE, now)
            remainingKb = (remainingKb - sample.totalPssKb).coerceAtLeast(0L)
            if (lowAvailableRam) {
                val updatedMem = bridge.readMemoryOverview()
                lowAvailableRam = updatedMem.memAvailableKb > 0L && updatedMem.memAvailableKb < minAvailKb
            }
            if (!lowAvailableRam && remainingKb <= budgetKb) break
        }

        // If RAM is still critically low, gently trim SOFT_TRIM apps without touching push daemons
        if (lowAvailableRam) {
            val cooldownMs = policy.backgroundTimeoutMinutes * 60_000L
            for ((pkg, group) in targets) {
                if (group != AppGroup.SOFT_TRIM) continue
                if (pkg == currentForegroundPkg) continue
                if (policy.exemptActiveAudio && pkg in activeAudio) continue
                val lastFg = lastForegroundMs[pkg] ?: now
                if (now - lastFg < MIN_PRESSURE_GRACE_MS) continue
                val lastTrim = lastSoftTrimMs[pkg] ?: 0L
                if (now - lastTrim < cooldownMs) continue
                val sample = samples[pkg] ?: continue
                if (sample.running) {
                    executeSoftTrim(pkg, sample.totalPssKb, CleanupTrigger.MEMORY_PRESSURE, now)
                }
            }
        }
    }

    private fun executeForceStop(pkg: String, beforePssKb: Long, trigger: CleanupTrigger, now: Long) {
        if (bridge.forceStop(pkg, userId = 0)) {
            recordEvent(
                packageName = pkg,
                action = CleanupAction.FORCE_STOP,
                trigger = trigger,
                freedKb = beforePssKb.coerceAtLeast(0L),
                timestampMs = now,
            )
        }
    }

    private fun executeSoftTrim(pkg: String, beforePssKb: Long, trigger: CleanupTrigger, now: Long) {
        if (bridge.softTrim(pkg, userId = 0)) {
            lastSoftTrimMs[pkg] = now
            val after = bridge.samplePackage(pkg, userId = 0)
            val freed = (beforePssKb - after.totalPssKb).coerceAtLeast(0L)
            recordEvent(
                packageName = pkg,
                action = CleanupAction.SOFT_TRIM,
                trigger = trigger,
                freedKb = freed,
                timestampMs = now,
            )
        }
    }

    private fun groupToAction(group: AppGroup): CleanupAction =
        if (group == AppGroup.SOFT_TRIM) CleanupAction.SOFT_TRIM else CleanupAction.FORCE_STOP

    private fun recordEvent(
        packageName: String,
        action: CleanupAction,
        trigger: CleanupTrigger,
        freedKb: Long,
        skippedReason: String? = null,
        timestampMs: Long = bridge.nowMs(),
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
                    appLabel = packageName,
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
