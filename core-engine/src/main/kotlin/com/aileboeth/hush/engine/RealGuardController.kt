package com.aileboeth.hush.engine

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.aileboeth.hush.contract.AppConfigSnapshot
import com.aileboeth.hush.contract.AppGroup
import com.aileboeth.hush.contract.AppKey
import com.aileboeth.hush.contract.AppRestriction
import com.aileboeth.hush.contract.AppRuntimeSample
import com.aileboeth.hush.contract.AppTarget
import com.aileboeth.hush.contract.BatchActionResult
import com.aileboeth.hush.contract.CleanupEvent
import com.aileboeth.hush.contract.CustomAppGroup
import com.aileboeth.hush.contract.DaemonStatus
import com.aileboeth.hush.contract.DiagnosticLogEntry
import com.aileboeth.hush.contract.GuardController
import com.aileboeth.hush.contract.HushLog
import com.aileboeth.hush.contract.HushProtocol
import com.aileboeth.hush.contract.ManagedAppInfo
import com.aileboeth.hush.contract.MemoryOverview
import com.aileboeth.hush.contract.PolicyConfig
import com.aileboeth.hush.contract.SafetyGuardrails
import com.aileboeth.hush.contract.SecondaryUserInfo
import com.aileboeth.hush.contract.StandbyBucket
import com.aileboeth.hush.contract.intVal
import com.aileboeth.hush.contract.listOfObj
import com.aileboeth.hush.contract.longVal
import com.aileboeth.hush.contract.obj
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Production implementation of [GuardController].
 * Bridges Compose UI state with `HushConfigStore` and the `uid=2000` `daemon-shell` via [DaemonClient].
 * Supports primary (`user 0`) and clone/dual-space (`user 999`, etc.) apps, plus custom app groups.
 */
class RealGuardController(
    private val context: Context,
    private val store: HushConfigStore,
    private val client: DaemonClient,
) : GuardController {

    private var configSnapshot: AppConfigSnapshot = store.loadConfig()
    private val labelCache = HashMap<String, String>()
    private val cloneTargetsCache = LinkedHashSet<AppTarget>()

    private val _daemonStatus = MutableStateFlow<DaemonStatus>(DaemonStatus.Disconnected)
    override val daemonStatus: StateFlow<DaemonStatus> = _daemonStatus.asStateFlow()

    private val _memoryOverview = MutableStateFlow(MemoryOverview())
    override val memoryOverview: StateFlow<MemoryOverview> = _memoryOverview.asStateFlow()

    private val _managedApps = MutableStateFlow<List<ManagedAppInfo>>(emptyList())
    override val managedApps: StateFlow<List<ManagedAppInfo>> = _managedApps.asStateFlow()

    private val _policyConfig = MutableStateFlow(configSnapshot.policy)
    override val policyConfig: StateFlow<PolicyConfig> = _policyConfig.asStateFlow()

    private val _cleanupLogs = MutableStateFlow<List<CleanupEvent>>(store.loadCleanupLogs())
    override val cleanupLogs: StateFlow<List<CleanupEvent>> = _cleanupLogs.asStateFlow()

    private val _secondaryUsers = MutableStateFlow<List<SecondaryUserInfo>>(emptyList())
    override val secondaryUsers: StateFlow<List<SecondaryUserInfo>> = _secondaryUsers.asStateFlow()

    private val _customGroups = MutableStateFlow(configSnapshot.customGroups)
    override val customGroups: StateFlow<List<CustomAppGroup>> = _customGroups.asStateFlow()

    init {
        seedCloneCacheFromConfig()
    }

    suspend fun attachSession(port: Int, token: String): Result<Unit> {
        _daemonStatus.value = DaemonStatus.Connecting
        val conn = client.connect(port, token)
        if (conn.isFailure) {
            _daemonStatus.value = DaemonStatus.Disconnected
            return conn
        }
        store.saveSession(DaemonSession(port, token))
        syncConfigToDaemon()
        return refreshSnapshot()
    }

    override suspend fun reconnect(): Result<Unit> {
        val saved = store.loadSession()
            ?: return Result.failure(IllegalStateException("尚未激活守护进程"))
        return attachSession(saved.port, saved.token)
    }

    override suspend fun refreshSnapshot(): Result<Unit> {
        val user0Installed = loadInstalledUserApps()
        // Self-heal session from persisted credentials if memory session dropped transiently
        if (client.currentSession == null) {
            store.loadSession()?.let { saved ->
                if (client.connect(saved.port, saved.token).isSuccess) {
                    syncConfigToDaemon()
                }
            }
        }
        val statusRes = client.call(HushProtocol.METHOD_STATUS)
        if (statusRes.isFailure) {
            _daemonStatus.value = DaemonStatus.Disconnected
            _secondaryUsers.value = emptyList()
            val allOffline = combineWithClones(user0Installed, cloneTargetsCache)
            _managedApps.value = mergeAppsWithSamples(allOffline, emptyMap())
            return Result.failure(statusRes.exceptionOrNull()!!)
        }

        val statusData = statusRes.getOrThrow().obj("data").orEmpty()
        _daemonStatus.value = DaemonStatus.Running(
            pid = statusData.intVal("pid"),
            uid = statusData.intVal("uid"),
            versionCode = statusData.intVal("versionCode"),
            startedAtMs = statusData.longVal("startedAtMs"),
            port = statusData.intVal("port"),
            todayCleanupCount = statusData.intVal("todayCleanupCount"),
            todayFreedKb = statusData.longVal("todayFreedKb"),
        )

        client.call(HushProtocol.METHOD_MEMORY).onSuccess { resp ->
            resp.obj("data")?.let { _memoryOverview.value = HushProtocol.decodeMemory(it) }
        }

        // Discover dual-space / clone packages (e.g., Xspace / MultiApp user 999) from daemon
        client.call(HushProtocol.METHOD_GET_CLONE_PACKAGES).onSuccess { resp ->
            val clones = resp.obj("data")?.listOfObj("packages").orEmpty()
                .map(HushProtocol::decodeAppTarget)
                .filter { it.packageName.isNotBlank() && it.userId > 0 }
            cloneTargetsCache.clear()
            cloneTargetsCache.addAll(clones)
        }

        val combinedEntries = combineWithClones(user0Installed, cloneTargetsCache)
        val allTargets = combinedEntries.map { AppTarget(it.packageName, it.userId) }
        val sampleMap = HashMap<AppTarget, AppRuntimeSample>()
        client.call(
            HushProtocol.METHOD_SAMPLE_APPS,
            mapOf(
                "targets" to allTargets.map(HushProtocol::encodeAppTarget),
                "packages" to user0Installed.map { it.packageName },
            ),
        ).onSuccess { resp ->
            val list = resp.obj("data")?.listOfObj("samples").orEmpty()
            for (item in list) {
                val sample = HushProtocol.decodeAppSample(item)
                sampleMap[AppTarget(sample.packageName, sample.userId)] = sample
            }
        }
        _managedApps.value = mergeAppsWithSamples(combinedEntries, sampleMap)

        client.call(HushProtocol.METHOD_GET_LOGS).onSuccess { resp ->
            val incoming = resp.obj("data")?.listOfObj("logs").orEmpty().map { raw ->
                val ev = HushProtocol.decodeCleanupEvent(raw)
                val target = AppKey.decode(ev.packageName)
                val baseLabel = labelCache[target.packageName]
                val resolvedLabel = when {
                    baseLabel != null && target.isDualSpace -> "$baseLabel (双开)"
                    baseLabel != null -> baseLabel
                    else -> ev.appLabel
                }
                ev.copy(appLabel = resolvedLabel)
            }
            val merged = mergeCleanupLogs(
                incoming = incoming,
                existing = _cleanupLogs.value,
                maxEntries = HushConfigStore.MAX_PERSISTED_LOGS,
            )
            _cleanupLogs.value = merged
            store.saveCleanupLogs(merged)
        }

        client.call(HushProtocol.METHOD_GET_SECONDARY_USERS).onSuccess { resp ->
            val users = resp.obj("data")?.listOfObj("users").orEmpty()
                .map(HushProtocol::decodeSecondaryUser)
            _secondaryUsers.value = users
        }

        return Result.success(Unit)
    }

    override suspend fun setAppGroup(packageName: String, group: AppGroup, userId: Int): Result<Unit> {
        if (SafetyGuardrails.isSystemProtected(packageName) && group != AppGroup.PROTECTED) {
            return Result.failure(SecurityException("系统关键组件不可移出保护"))
        }
        val key = AppKey.encode(packageName, userId)
        val updatedGroups = configSnapshot.appGroups.toMutableMap()
        if (group == AppGroup.UNSET) {
            updatedGroups.remove(key)
        } else {
            updatedGroups[key] = group
        }
        configSnapshot = configSnapshot.copy(appGroups = updatedGroups)
        store.saveConfig(configSnapshot)
        reapplyEffectiveGroupsInMemory()
        syncConfigToDaemon()
        return Result.success(Unit)
    }

    override suspend fun setAppGroupsBatch(updates: Map<String, AppGroup>): Result<Unit> {
        if (updates.isEmpty()) return Result.success(Unit)
        val updatedGroups = configSnapshot.appGroups.toMutableMap()
        for ((rawKey, group) in updates) {
            val target = AppKey.decode(rawKey)
            if (SafetyGuardrails.isSystemProtected(target.packageName) && group != AppGroup.PROTECTED) continue
            if (group == AppGroup.UNSET) {
                updatedGroups.remove(rawKey)
            } else {
                updatedGroups[rawKey] = group
            }
        }
        configSnapshot = configSnapshot.copy(appGroups = updatedGroups)
        store.saveConfig(configSnapshot)
        reapplyEffectiveGroupsInMemory()
        syncConfigToDaemon()
        return Result.success(Unit)
    }

    override suspend fun saveCustomGroup(group: CustomAppGroup): Result<Unit> {
        val cleanName = group.name.trim()
        if (cleanName.isEmpty()) {
            return Result.failure(IllegalArgumentException("分组名称不能为空"))
        }
        val cleanGroup = group.copy(
            name = cleanName,
            appKeys = group.appKeys.distinct(),
        )
        val current = configSnapshot.customGroups.toMutableList()
        val idx = current.indexOfFirst { it.id == cleanGroup.id }
        if (idx >= 0) {
            current[idx] = cleanGroup
        } else {
            current.add(cleanGroup)
        }
        configSnapshot = configSnapshot.copy(customGroups = current)
        store.saveConfig(configSnapshot)
        _customGroups.value = current
        reapplyEffectiveGroupsInMemory()
        syncConfigToDaemon()
        return Result.success(Unit)
    }

    override suspend fun deleteCustomGroup(groupId: String): Result<Unit> {
        val current = configSnapshot.customGroups.filterNot { it.id == groupId }
        configSnapshot = configSnapshot.copy(customGroups = current)
        store.saveConfig(configSnapshot)
        _customGroups.value = current
        reapplyEffectiveGroupsInMemory()
        syncConfigToDaemon()
        return Result.success(Unit)
    }

    override suspend fun updatePolicy(config: PolicyConfig): Result<Unit> {
        configSnapshot = configSnapshot.copy(policy = config)
        store.saveConfig(configSnapshot)
        _policyConfig.value = config
        syncConfigToDaemon()
        return Result.success(Unit)
    }

    override suspend fun forceStopNow(packageName: String, userId: Int): Result<Long> {
        if (SafetyGuardrails.isSystemProtected(packageName)) {
            return Result.failure(SecurityException("系统关键组件受保护"))
        }
        val res = client.call(
            HushProtocol.METHOD_FORCE_STOP,
            mapOf("packageName" to packageName, "userId" to userId),
        )
        return res.map { resp ->
            resp.obj("data")?.longVal("freedKb") ?: 0L
        }.also {
            if (it.isSuccess) refreshSnapshot()
        }
    }

    override suspend fun forceStopBatch(targets: List<AppTarget>): Result<BatchActionResult> {
        val safeTargets = targets.distinct().filterNot { SafetyGuardrails.isSystemProtected(it.packageName) }
        if (safeTargets.isEmpty()) return Result.success(BatchActionResult())
        val res = client.call(
            HushProtocol.METHOD_FORCE_STOP_BATCH,
            mapOf("targets" to safeTargets.map(HushProtocol::encodeAppTarget)),
        )
        return res.map { resp ->
            val data = resp.obj("data").orEmpty()
            BatchActionResult(
                affectedCount = data.intVal("affectedCount"),
                skippedCount = data.intVal("skippedCount"),
                freedKb = data.longVal("freedKb"),
            )
        }.also {
            if (it.isSuccess) refreshSnapshot()
        }
    }

    override suspend fun softTrimNow(packageName: String, userId: Int): Result<Long> {
        if (SafetyGuardrails.isSystemProtected(packageName)) {
            return Result.failure(SecurityException("系统关键组件受保护"))
        }
        val res = client.call(
            HushProtocol.METHOD_SOFT_TRIM,
            mapOf("packageName" to packageName, "userId" to userId),
        )
        return res.map { resp ->
            resp.obj("data")?.longVal("freedKb") ?: 0L
        }.also {
            if (it.isSuccess) refreshSnapshot()
        }
    }

    override suspend fun softTrimBatch(targets: List<AppTarget>): Result<BatchActionResult> {
        val safeTargets = targets.distinct().filterNot { SafetyGuardrails.isSystemProtected(it.packageName) }
        if (safeTargets.isEmpty()) return Result.success(BatchActionResult())
        val res = client.call(
            HushProtocol.METHOD_SOFT_TRIM_BATCH,
            mapOf("targets" to safeTargets.map(HushProtocol::encodeAppTarget)),
        )
        return res.map { resp ->
            val data = resp.obj("data").orEmpty()
            BatchActionResult(
                affectedCount = data.intVal("affectedCount"),
                skippedCount = data.intVal("skippedCount"),
                freedKb = data.longVal("freedKb"),
            )
        }.also {
            if (it.isSuccess) refreshSnapshot()
        }
    }

    override suspend fun setAppRestriction(packageName: String, restriction: AppRestriction, userId: Int): Result<Unit> {
        val target = AppTarget(packageName, userId)
        val group = configSnapshot.resolveEffectiveGroups()[target] ?: AppGroup.UNSET
        if (restriction.hasAnyRestriction && SafetyGuardrails.isRestrictionBlocked(packageName, group)) {
            return Result.failure(SecurityException("保消息瘦身档与保护档禁止施加后台限制，以防漏消息"))
        }
        val res = client.call(
            HushProtocol.METHOD_SET_RESTRICTION,
            mapOf(
                "packageName" to packageName,
                "userId" to userId,
                "restriction" to HushProtocol.encodeRestriction(restriction),
            ),
        )
        return res.map {
            val key = AppKey.encode(packageName, userId)
            val updated = configSnapshot.appliedRestrictions.toMutableMap()
            if (restriction.hasAnyRestriction) {
                updated[key] = restriction
            } else {
                updated.remove(key)
            }
            configSnapshot = configSnapshot.copy(appliedRestrictions = updated)
            store.saveConfig(configSnapshot)
            refreshSnapshot()
        }
    }

    override suspend fun restoreAllRestrictions(): Result<Int> {
        val res = client.call(HushProtocol.METHOD_RESTORE_ALL)
        return res.map { resp ->
            configSnapshot = configSnapshot.copy(appliedRestrictions = emptyMap())
            store.saveConfig(configSnapshot)
            refreshSnapshot()
            resp.obj("data")?.intVal("restoredCount") ?: 0
        }
    }

    override suspend fun clearLogs(): Result<Unit> {
        _cleanupLogs.value = emptyList()
        store.clearCleanupLogs()
        if (client.currentSession != null) {
            client.call(HushProtocol.METHOD_CLEAR_LOGS)
        }
        return Result.success(Unit)
    }

    override suspend fun stopSecondaryUser(userId: Int): Result<Long> {
        if (userId <= 0) {
            return Result.failure(SecurityException("主系统用户受保护，不能停机"))
        }
        val res = client.call(HushProtocol.METHOD_STOP_USER, mapOf("userId" to userId))
        return res.map { resp ->
            resp.obj("data")?.longVal("freedKb") ?: 0L
        }.also {
            if (it.isSuccess) {
                HushLog.i("Guard", "Stopped secondary userId=$userId freedKb=${it.getOrNull()}")
                refreshSnapshot()
            } else {
                HushLog.w("Guard", "stopSecondaryUser($userId) failed", it.exceptionOrNull())
            }
        }
    }

    override suspend fun fetchDiagnostics(): Result<List<DiagnosticLogEntry>> {
        val local = HushLog.snapshot()
        val liveDaemonEntries = if (client.currentSession != null) {
            client.call(HushProtocol.METHOD_GET_DIAGNOSTICS).getOrNull()
                ?.obj("data")?.listOfObj("entries").orEmpty()
                .map(HushProtocol::decodeDiagnosticEntry)
        } else {
            null
        }
        val daemonEntries = if (liveDaemonEntries != null && liveDaemonEntries.isNotEmpty()) {
            store.saveCachedDaemonDiagnostics(liveDaemonEntries)
            liveDaemonEntries
        } else {
            store.loadCachedDaemonDiagnostics()
        }
        val merged = (local + daemonEntries).sortedBy { it.timestampMs }
        return Result.success(merged)
    }

    override suspend fun clearDiagnostics(): Result<Unit> {
        HushLog.clear()
        store.clearCachedDaemonDiagnostics()
        if (client.currentSession != null) {
            client.call(HushProtocol.METHOD_CLEAR_DIAGNOSTICS)
        }
        return Result.success(Unit)
    }

    override suspend fun stopDaemon(): Result<Unit> {
        val res = client.call(HushProtocol.METHOD_SHUTDOWN)
        client.disconnect()
        store.clearSession()
        _daemonStatus.value = DaemonStatus.Disconnected
        _secondaryUsers.value = emptyList()
        return res.map { }
    }

    private suspend fun syncConfigToDaemon() {
        if (client.currentSession == null) return
        client.call(HushProtocol.METHOD_SYNC_CONFIG, HushProtocol.encodeConfigSnapshot(configSnapshot))
    }

    private fun seedCloneCacheFromConfig() {
        val allKeys = buildList {
            addAll(configSnapshot.appGroups.keys)
            addAll(configSnapshot.appliedRestrictions.keys)
            configSnapshot.customGroups.forEach { addAll(it.appKeys) }
        }
        for (raw in allKeys) {
            val t = AppKey.decode(raw)
            if (t.userId > 0) cloneTargetsCache.add(t)
        }
    }

    private fun reapplyEffectiveGroupsInMemory() {
        val effective = configSnapshot.resolveEffectiveGroups()
        _managedApps.value = _managedApps.value.map { app ->
            val group = if (app.isSystemProtected) {
                AppGroup.PROTECTED
            } else {
                effective[AppTarget(app.packageName, app.userId)] ?: AppGroup.UNSET
            }
            app.copy(group = group)
        }
    }

    private data class InstalledAppEntry(
        val packageName: String,
        val userId: Int,
        val label: String,
    )

    private suspend fun loadInstalledUserApps(): List<InstalledAppEntry> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val trackedUser0Pkgs = HashSet<String>()
        configSnapshot.appGroups.keys.mapTo(trackedUser0Pkgs) { AppKey.decode(it).packageName }
        configSnapshot.appliedRestrictions.keys.mapTo(trackedUser0Pkgs) { AppKey.decode(it).packageName }
        configSnapshot.customGroups.forEach { g ->
            g.appKeys.mapTo(trackedUser0Pkgs) { AppKey.decode(it).packageName }
        }

        val result = ArrayList<InstalledAppEntry>()
        for (ai in apps) {
            val label = pm.getApplicationLabel(ai).toString()
            labelCache[ai.packageName] = label
            val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSystem = (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            val isTracked = ai.packageName in trackedUser0Pkgs || !ai.enabled
            if (isSystem && !isUpdatedSystem && !isTracked) continue
            if (ai.packageName == SafetyGuardrails.SELF_PACKAGE) continue
            result.add(InstalledAppEntry(ai.packageName, 0, label))
        }
        result.sortBy { it.label.lowercase() }
        result
    }

    private fun combineWithClones(
        user0: List<InstalledAppEntry>,
        clones: Set<AppTarget>,
    ): List<InstalledAppEntry> {
        if (clones.isEmpty()) return user0
        val pm = context.packageManager
        val out = ArrayList<InstalledAppEntry>(user0.size + clones.size)
        out.addAll(user0)
        val seen = HashSet<AppTarget>(user0.size + clones.size)
        user0.forEach { seen.add(AppTarget(it.packageName, it.userId)) }

        for (clone in clones) {
            if (clone.userId <= 0 || !seen.add(clone)) continue
            val label = labelCache[clone.packageName] ?: runCatching {
                val ai = pm.getApplicationInfo(clone.packageName, 0)
                pm.getApplicationLabel(ai).toString().also { labelCache[clone.packageName] = it }
            }.getOrDefault(clone.packageName)
            out.add(InstalledAppEntry(clone.packageName, clone.userId, label))
        }
        return out
    }

    private fun mergeAppsWithSamples(
        installed: List<InstalledAppEntry>,
        samples: Map<AppTarget, AppRuntimeSample>,
    ): List<ManagedAppInfo> {
        val effective = configSnapshot.resolveEffectiveGroups()
        return installed.map { entry ->
            val target = AppTarget(entry.packageName, entry.userId)
            val s = samples[target]
            val protectedSys = SafetyGuardrails.isSystemProtected(entry.packageName)
            val group = if (protectedSys) AppGroup.PROTECTED else (effective[target] ?: AppGroup.UNSET)
            ManagedAppInfo(
                packageName = entry.packageName,
                label = entry.label,
                userId = entry.userId,
                isSystemProtected = protectedSys,
                group = group,
                running = s?.running ?: false,
                isFrozen = s?.isFrozen ?: false,
                isPlayingAudio = s?.isPlayingAudio ?: false,
                totalPssKb = s?.totalPssKb ?: 0L,
                swapPssKb = s?.swapPssKb ?: 0L,
                processCount = s?.processCount ?: 0,
                restriction = s?.restriction ?: (configSnapshot.appliedRestrictions[target.key] ?: AppRestriction()),
                standbyBucket = s?.standbyBucket ?: StandbyBucket.UNKNOWN,
                lastForegroundMs = s?.lastForegroundMs ?: 0L,
            )
        }.sortedWith(
            compareByDescending<ManagedAppInfo> { it.totalPssKb }
                .thenBy { it.group.ordinal }
                .thenBy { it.label.lowercase() }
                .thenBy { it.userId },
        )
    }

    companion object {
        /**
         * Merges fresh daemon cleanup events with locally persisted history so records survive
         * daemon restarts, disconnects, and app relaunches without duplicates.
         */
        internal fun mergeCleanupLogs(
            incoming: List<CleanupEvent>,
            existing: List<CleanupEvent>,
            maxEntries: Int = HushConfigStore.MAX_PERSISTED_LOGS,
        ): List<CleanupEvent> {
            if (incoming.isEmpty()) return existing.take(maxEntries)
            data class EventKey(
                val timestampMs: Long,
                val packageName: String,
                val action: com.aileboeth.hush.contract.CleanupAction,
                val trigger: com.aileboeth.hush.contract.CleanupTrigger,
                val skippedReason: String?,
            )
            val merged = LinkedHashMap<EventKey, CleanupEvent>(incoming.size + existing.size)
            for (ev in existing) {
                val k = EventKey(ev.timestampMs, ev.packageName, ev.action, ev.trigger, ev.skippedReason)
                merged[k] = ev
            }
            for (ev in incoming) {
                val k = EventKey(ev.timestampMs, ev.packageName, ev.action, ev.trigger, ev.skippedReason)
                val prev = merged[k]
                val resolved = if (prev != null && ev.appLabel == ev.packageName && prev.appLabel != prev.packageName) {
                    ev.copy(appLabel = prev.appLabel)
                } else {
                    ev
                }
                merged[k] = resolved
            }
            return merged.values
                .sortedWith(compareByDescending<CleanupEvent> { it.timestampMs }.thenByDescending { it.id })
                .take(maxEntries)
        }
    }
}
