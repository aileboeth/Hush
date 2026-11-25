package com.aileboeth.hush.engine

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.aileboeth.hush.contract.AppConfigSnapshot
import com.aileboeth.hush.contract.AppGroup
import com.aileboeth.hush.contract.AppRestriction
import com.aileboeth.hush.contract.AppRuntimeSample
import com.aileboeth.hush.contract.CleanupEvent
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
 */
class RealGuardController(
    private val context: Context,
    private val store: HushConfigStore,
    private val client: DaemonClient,
) : GuardController {

    private var configSnapshot: AppConfigSnapshot = store.loadConfig()
    private val labelCache = HashMap<String, String>()

    private val _daemonStatus = MutableStateFlow<DaemonStatus>(DaemonStatus.Disconnected)
    override val daemonStatus: StateFlow<DaemonStatus> = _daemonStatus.asStateFlow()

    private val _memoryOverview = MutableStateFlow(MemoryOverview())
    override val memoryOverview: StateFlow<MemoryOverview> = _memoryOverview.asStateFlow()

    private val _managedApps = MutableStateFlow<List<ManagedAppInfo>>(emptyList())
    override val managedApps: StateFlow<List<ManagedAppInfo>> = _managedApps.asStateFlow()

    private val _policyConfig = MutableStateFlow(configSnapshot.policy)
    override val policyConfig: StateFlow<PolicyConfig> = _policyConfig.asStateFlow()

    private val _cleanupLogs = MutableStateFlow<List<CleanupEvent>>(emptyList())
    override val cleanupLogs: StateFlow<List<CleanupEvent>> = _cleanupLogs.asStateFlow()

    private val _secondaryUsers = MutableStateFlow<List<SecondaryUserInfo>>(emptyList())
    override val secondaryUsers: StateFlow<List<SecondaryUserInfo>> = _secondaryUsers.asStateFlow()

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
        val installed = loadInstalledUserApps()
        val statusRes = client.call(HushProtocol.METHOD_STATUS)
        if (statusRes.isFailure) {
            _daemonStatus.value = DaemonStatus.Disconnected
            _secondaryUsers.value = emptyList()
            // Even when disconnected, show installed apps + saved groups so the user can configure offline
            _managedApps.value = mergeAppsWithSamples(installed, emptyMap())
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

        val targetPkgs = installed.map { it.first }
        val sampleMap = HashMap<String, AppRuntimeSample>()
        client.call(HushProtocol.METHOD_SAMPLE_APPS, mapOf("packages" to targetPkgs)).onSuccess { resp ->
            val list = resp.obj("data")?.listOfObj("samples").orEmpty()
            for (item in list) {
                val sample = HushProtocol.decodeAppSample(item)
                sampleMap[sample.packageName] = sample
            }
        }
        _managedApps.value = mergeAppsWithSamples(installed, sampleMap)

        client.call(HushProtocol.METHOD_GET_LOGS).onSuccess { resp ->
            val logs = resp.obj("data")?.listOfObj("logs").orEmpty().map { raw ->
                val ev = HushProtocol.decodeCleanupEvent(raw)
                ev.copy(appLabel = labelCache[ev.packageName] ?: ev.appLabel)
            }
            _cleanupLogs.value = logs
        }

        client.call(HushProtocol.METHOD_GET_SECONDARY_USERS).onSuccess { resp ->
            val users = resp.obj("data")?.listOfObj("users").orEmpty()
                .map(HushProtocol::decodeSecondaryUser)
            _secondaryUsers.value = users
        }

        return Result.success(Unit)
    }

    override suspend fun setAppGroup(packageName: String, group: AppGroup): Result<Unit> {
        if (SafetyGuardrails.isSystemProtected(packageName) && group != AppGroup.PROTECTED) {
            return Result.failure(SecurityException("系统关键组件不可移出保护"))
        }
        val updatedGroups = configSnapshot.appGroups.toMutableMap()
        if (group == AppGroup.UNSET) {
            updatedGroups.remove(packageName)
        } else {
            updatedGroups[packageName] = group
        }
        configSnapshot = configSnapshot.copy(appGroups = updatedGroups)
        store.saveConfig(configSnapshot)

        _managedApps.value = _managedApps.value.map {
            if (it.packageName == packageName) it.copy(group = group) else it
        }
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

    override suspend fun forceStopNow(packageName: String): Result<Long> {
        if (SafetyGuardrails.isSystemProtected(packageName)) {
            return Result.failure(SecurityException("系统关键组件受保护"))
        }
        val res = client.call(HushProtocol.METHOD_FORCE_STOP, mapOf("packageName" to packageName))
        return res.map { resp ->
            resp.obj("data")?.longVal("freedKb") ?: 0L
        }.also {
            if (it.isSuccess) refreshSnapshot()
        }
    }

    override suspend fun softTrimNow(packageName: String): Result<Long> {
        if (SafetyGuardrails.isSystemProtected(packageName)) {
            return Result.failure(SecurityException("系统关键组件受保护"))
        }
        val res = client.call(HushProtocol.METHOD_SOFT_TRIM, mapOf("packageName" to packageName))
        return res.map { resp ->
            resp.obj("data")?.longVal("freedKb") ?: 0L
        }.also {
            if (it.isSuccess) refreshSnapshot()
        }
    }

    override suspend fun setAppRestriction(packageName: String, restriction: AppRestriction): Result<Unit> {
        val group = configSnapshot.appGroups[packageName] ?: AppGroup.UNSET
        if (restriction.hasAnyRestriction && SafetyGuardrails.isRestrictionBlocked(packageName, group)) {
            return Result.failure(SecurityException("保消息瘦身档与保护档禁止施加后台限制，以防漏消息"))
        }
        val res = client.call(
            HushProtocol.METHOD_SET_RESTRICTION,
            mapOf(
                "packageName" to packageName,
                "restriction" to HushProtocol.encodeRestriction(restriction),
            ),
        )
        return res.map {
            val updated = configSnapshot.appliedRestrictions.toMutableMap()
            if (restriction.hasAnyRestriction) {
                updated[packageName] = restriction
            } else {
                updated.remove(packageName)
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
        return client.call(HushProtocol.METHOD_CLEAR_LOGS).map {
            _cleanupLogs.value = emptyList()
        }
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
        val daemonEntries = if (client.currentSession != null) {
            client.call(HushProtocol.METHOD_GET_DIAGNOSTICS).getOrNull()
                ?.obj("data")?.listOfObj("entries").orEmpty()
                .map(HushProtocol::decodeDiagnosticEntry)
        } else {
            emptyList()
        }
        val merged = (local + daemonEntries).sortedBy { it.timestampMs }
        return Result.success(merged)
    }

    override suspend fun clearDiagnostics(): Result<Unit> {
        HushLog.clear()
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

    private suspend fun loadInstalledUserApps(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val result = ArrayList<Pair<String, String>>()
        for (ai in apps) {
            val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSystem = (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            val isTracked = ai.packageName in configSnapshot.appliedRestrictions ||
                ai.packageName in configSnapshot.appGroups ||
                !ai.enabled
            if (isSystem && !isUpdatedSystem && !isTracked) continue
            if (ai.packageName == SafetyGuardrails.SELF_PACKAGE) continue
            val label = pm.getApplicationLabel(ai).toString()
            labelCache[ai.packageName] = label
            result.add(ai.packageName to label)
        }
        result.sortBy { it.second.lowercase() }
        result
    }

    private fun mergeAppsWithSamples(
        installed: List<Pair<String, String>>,
        samples: Map<String, AppRuntimeSample>,
    ): List<ManagedAppInfo> {
        return installed.map { (pkg, label) ->
            val s = samples[pkg]
            val protectedSys = SafetyGuardrails.isSystemProtected(pkg)
            val group = if (protectedSys) AppGroup.PROTECTED else (configSnapshot.appGroups[pkg] ?: AppGroup.UNSET)
            ManagedAppInfo(
                packageName = pkg,
                label = label,
                userId = s?.userId ?: 0,
                isSystemProtected = protectedSys,
                group = group,
                running = s?.running ?: false,
                isFrozen = s?.isFrozen ?: false,
                isPlayingAudio = s?.isPlayingAudio ?: false,
                totalPssKb = s?.totalPssKb ?: 0L,
                swapPssKb = s?.swapPssKb ?: 0L,
                processCount = s?.processCount ?: 0,
                restriction = s?.restriction ?: (configSnapshot.appliedRestrictions[pkg] ?: AppRestriction()),
                standbyBucket = s?.standbyBucket ?: com.aileboeth.hush.contract.StandbyBucket.UNKNOWN,
                lastForegroundMs = s?.lastForegroundMs ?: 0L,
            )
        }.sortedWith(
            compareByDescending<ManagedAppInfo> { it.totalPssKb }
                .thenBy { it.group.ordinal }
                .thenBy { it.label.lowercase() }
        )
    }
}
