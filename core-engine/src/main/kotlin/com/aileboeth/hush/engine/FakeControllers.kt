package com.aileboeth.hush.engine

import com.aileboeth.hush.contract.AdbEndpoint
import com.aileboeth.hush.contract.AppGroup
import com.aileboeth.hush.contract.AppKey
import com.aileboeth.hush.contract.AppRestriction
import com.aileboeth.hush.contract.AppTarget
import com.aileboeth.hush.contract.BatchActionResult
import com.aileboeth.hush.contract.BootstrapController
import com.aileboeth.hush.contract.BootstrapState
import com.aileboeth.hush.contract.CleanupAction
import com.aileboeth.hush.contract.CleanupEvent
import com.aileboeth.hush.contract.CleanupTrigger
import com.aileboeth.hush.contract.CustomAppGroup
import com.aileboeth.hush.contract.DaemonStatus
import com.aileboeth.hush.contract.DiagnosticLogEntry
import com.aileboeth.hush.contract.GuardController
import com.aileboeth.hush.contract.HushGraph
import com.aileboeth.hush.contract.HushLog
import com.aileboeth.hush.contract.HushProtocol
import com.aileboeth.hush.contract.ManagedAppInfo
import com.aileboeth.hush.contract.MemoryOverview
import com.aileboeth.hush.contract.PolicyConfig
import com.aileboeth.hush.contract.SafetyGuardrails
import com.aileboeth.hush.contract.SecondaryUserInfo
import com.aileboeth.hush.contract.StandbyBucket
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/**
 * Interactive offline mock implementation of [GuardController] and [BootstrapController]
 * using strictly synthetic placeholder data.
 *
 * Designed specifically so the Compose UI (`:app`) can be built, previewed, and interactively
 * tested without requiring a connected device or active `uid=2000` daemon.
 */
class FakeGuardController : GuardController {

    private val idGen = AtomicLong(10L)
    private val now = System.currentTimeMillis()

    private val _daemonStatus = MutableStateFlow<DaemonStatus>(
        DaemonStatus.Running(
            pid = 18420,
            uid = 2000,
            versionCode = 1,
            startedAtMs = now - 5 * 3600_000L,
            port = 41892,
            todayCleanupCount = 7,
            todayFreedKb = 3_480_000L,
        )
    )
    override val daemonStatus: StateFlow<DaemonStatus> = _daemonStatus.asStateFlow()

    private val _memoryOverview = MutableStateFlow(
        MemoryOverview(
            memTotalKb = 11_568_000L,
            memAvailableKb = 4_820_000L,
            memFreeKb = 610_000L,
            swapTotalKb = 12_582_908L,
            swapFreeKb = 8_920_000L,
            swapInPages = 420_000L,
            swapOutPages = 510_000L,
            allocStalls = 1_240L,
            sampledAtMs = now,
        )
    )
    override val memoryOverview: StateFlow<MemoryOverview> = _memoryOverview.asStateFlow()

    private val _policyConfig = MutableStateFlow(PolicyConfig())
    override val policyConfig: StateFlow<PolicyConfig> = _policyConfig.asStateFlow()

    private val _managedApps = MutableStateFlow(
        listOf(
            ManagedAppInfo(
                packageName = "com.example.shortvideo",
                label = "短视频示例",
                group = AppGroup.FORCE_STOP,
                running = true,
                isFrozen = false,
                totalPssKb = 1_480_000L,
                swapPssKb = 620_000L,
                processCount = 2,
                restriction = AppRestriction(restrictBackground = true, restrictWakeLock = true, restrictedBucket = true),
                standbyBucket = StandbyBucket.RESTRICTED,
                lastForegroundMs = now - 12 * 60_000L,
            ),
            ManagedAppInfo(
                packageName = "com.example.reader",
                label = "阅读听书示例",
                group = AppGroup.FORCE_STOP,
                running = true,
                isFrozen = false,
                isPlayingAudio = true,
                totalPssKb = 680_000L,
                swapPssKb = 340_000L,
                processCount = 4,
                restriction = AppRestriction(restrictBackground = true),
                standbyBucket = StandbyBucket.RARE,
                lastForegroundMs = now - 8 * 60_000L,
            ),
            ManagedAppInfo(
                packageName = "com.example.shopping",
                label = "购物商城示例",
                group = AppGroup.FORCE_STOP,
                running = true,
                isFrozen = true,
                totalPssKb = 740_000L,
                swapPssKb = 580_000L,
                processCount = 3,
                standbyBucket = StandbyBucket.FREQUENT,
                lastForegroundMs = now - 25 * 60_000L,
            ),
            ManagedAppInfo(
                packageName = "com.example.messenger",
                label = "即时通讯示例",
                group = AppGroup.SOFT_TRIM,
                running = true,
                isFrozen = false,
                totalPssKb = 820_000L,
                swapPssKb = 210_000L,
                processCount = 3,
                standbyBucket = StandbyBucket.ACTIVE,
                lastForegroundMs = now - 4 * 60_000L,
            ),
            ManagedAppInfo(
                packageName = "com.example.messenger",
                label = "即时通讯示例",
                userId = 999,
                group = AppGroup.PROTECTED,
                running = true,
                totalPssKb = 610_000L,
                swapPssKb = 180_000L,
                processCount = 2,
                standbyBucket = StandbyBucket.ACTIVE,
                lastForegroundMs = now - 15 * 60_000L,
            ),
            ManagedAppInfo(
                packageName = "com.example.vault",
                label = "密码保险箱示例",
                group = AppGroup.PROTECTED,
                running = true,
                totalPssKb = 95_000L,
                swapPssKb = 12_000L,
                processCount = 1,
                standbyBucket = StandbyBucket.ACTIVE,
                lastForegroundMs = now - 60 * 60_000L,
            ),
            ManagedAppInfo(
                packageName = "com.example.notes",
                label = "记事本示例",
                group = AppGroup.UNSET,
                running = false,
                totalPssKb = 0L,
                swapPssKb = 0L,
                processCount = 0,
                standbyBucket = StandbyBucket.WORKING_SET,
            ),
        )
    )
    override val managedApps: StateFlow<List<ManagedAppInfo>> = _managedApps.asStateFlow()

    private val _cleanupLogs = MutableStateFlow(
        listOf(
            CleanupEvent(
                id = 3,
                timestampMs = now - 6 * 60_000L,
                packageName = "com.example.shortvideo",
                appLabel = "短视频示例",
                action = CleanupAction.FORCE_STOP,
                trigger = CleanupTrigger.BACKGROUND_TIMEOUT,
                freedKb = 1_510_000L,
            ),
            CleanupEvent(
                id = 2,
                timestampMs = now - 19 * 60_000L,
                packageName = "com.example.messenger",
                appLabel = "即时通讯示例",
                action = CleanupAction.SOFT_TRIM,
                trigger = CleanupTrigger.SCREEN_OFF,
                freedKb = 285_000L,
            ),
            CleanupEvent(
                id = 1,
                timestampMs = now - 20 * 60_000L,
                packageName = "com.example.reader",
                appLabel = "阅读听书示例",
                action = CleanupAction.FORCE_STOP,
                trigger = CleanupTrigger.SCREEN_OFF,
                freedKb = 0L,
                skippedReason = "正在播放音频",
            ),
        )
    )
    override val cleanupLogs: StateFlow<List<CleanupEvent>> = _cleanupLogs.asStateFlow()

    private val _secondaryUsers = MutableStateFlow(
        listOf(
            SecondaryUserInfo(
                userId = 10,
                name = "第二空间示例",
                running = true,
                isCurrent = false,
                processCount = 48,
                totalPssKb = 840_000L,
                swapPssKb = 520_000L,
            ),
        )
    )
    override val secondaryUsers: StateFlow<List<SecondaryUserInfo>> = _secondaryUsers.asStateFlow()

    private val _customGroups = MutableStateFlow(
        listOf(
            CustomAppGroup(
                id = "demo_kill",
                name = "娱乐强杀",
                action = AppGroup.FORCE_STOP,
                appKeys = listOf("com.example.shortvideo", "com.example.shopping"),
            ),
        )
    )
    override val customGroups: StateFlow<List<CustomAppGroup>> = _customGroups.asStateFlow()

    override suspend fun reconnect(): Result<Unit> {
        _daemonStatus.value = DaemonStatus.Connecting
        delay(250)
        _daemonStatus.value = DaemonStatus.Running(
            pid = 18420,
            uid = 2000,
            versionCode = 1,
            startedAtMs = System.currentTimeMillis(),
            port = 41892,
            todayCleanupCount = _cleanupLogs.value.count { it.skippedReason == null },
            todayFreedKb = _cleanupLogs.value.sumOf { it.freedKb },
        )
        return Result.success(Unit)
    }

    override suspend fun refreshSnapshot(): Result<Unit> {
        delay(150)
        _memoryOverview.update { it.copy(sampledAtMs = System.currentTimeMillis()) }
        return Result.success(Unit)
    }

    override suspend fun setAppGroup(packageName: String, group: AppGroup, userId: Int): Result<Unit> {
        _managedApps.update { list ->
            list.map {
                if (it.packageName == packageName && it.userId == userId) it.copy(group = group) else it
            }
        }
        return Result.success(Unit)
    }

    override suspend fun setAppGroupsBatch(updates: Map<String, AppGroup>): Result<Unit> {
        _managedApps.update { list ->
            list.map { app ->
                val next = updates[app.appKey]
                if (next != null) app.copy(group = next) else app
            }
        }
        return Result.success(Unit)
    }

    override suspend fun saveCustomGroup(group: CustomAppGroup): Result<Unit> {
        _customGroups.update { list ->
            val idx = list.indexOfFirst { it.id == group.id }
            if (idx >= 0) list.toMutableList().apply { set(idx, group) } else list + group
        }
        return Result.success(Unit)
    }

    override suspend fun deleteCustomGroup(groupId: String): Result<Unit> {
        _customGroups.update { list -> list.filterNot { it.id == groupId } }
        return Result.success(Unit)
    }

    override suspend fun updatePolicy(config: PolicyConfig): Result<Unit> {
        _policyConfig.value = config
        return Result.success(Unit)
    }

    override suspend fun forceStopNow(packageName: String, userId: Int): Result<Long> {
        val target = _managedApps.value.firstOrNull { it.packageName == packageName && it.userId == userId }
            ?: return Result.failure(IllegalArgumentException("App not found"))
        val freed = target.totalPssKb
        _managedApps.update { list ->
            list.map {
                if (it.packageName == packageName && it.userId == userId) {
                    it.copy(running = false, isFrozen = false, totalPssKb = 0L, swapPssKb = 0L, processCount = 0)
                } else it
            }
        }
        _memoryOverview.update {
            it.copy(
                memAvailableKb = (it.memAvailableKb + freed / 2).coerceAtMost(it.memTotalKb),
                swapFreeKb = (it.swapFreeKb + target.swapPssKb).coerceAtMost(it.swapTotalKb),
                sampledAtMs = System.currentTimeMillis(),
            )
        }
        prependLog(target, CleanupAction.FORCE_STOP, CleanupTrigger.MANUAL, freed)
        return Result.success(freed)
    }

    override suspend fun forceStopBatch(targets: List<AppTarget>): Result<BatchActionResult> {
        var affected = 0
        var freed = 0L
        for (t in targets.distinct()) {
            val cur = _managedApps.value.firstOrNull { it.packageName == t.packageName && it.userId == t.userId }
            if (cur != null && cur.running) {
                forceStopNow(t.packageName, t.userId).onSuccess {
                    affected++
                    freed += it
                }
            }
        }
        return Result.success(BatchActionResult(affectedCount = affected, freedKb = freed))
    }

    override suspend fun softTrimNow(packageName: String, userId: Int): Result<Long> {
        val target = _managedApps.value.firstOrNull { it.packageName == packageName && it.userId == userId }
            ?: return Result.failure(IllegalArgumentException("App not found"))
        val freed = (target.totalPssKb * 35) / 100
        _managedApps.update { list ->
            list.map {
                if (it.packageName == packageName && it.userId == userId) {
                    it.copy(
                        totalPssKb = (it.totalPssKb - freed).coerceAtLeast(150_000L),
                        processCount = it.processCount.coerceAtMost(2),
                    )
                } else it
            }
        }
        prependLog(target, CleanupAction.SOFT_TRIM, CleanupTrigger.MANUAL, freed)
        return Result.success(freed)
    }

    override suspend fun softTrimBatch(targets: List<AppTarget>): Result<BatchActionResult> {
        var affected = 0
        var freed = 0L
        for (t in targets.distinct()) {
            val cur = _managedApps.value.firstOrNull { it.packageName == t.packageName && it.userId == t.userId }
            if (cur != null && cur.running) {
                softTrimNow(t.packageName, t.userId).onSuccess {
                    affected++
                    freed += it
                }
            }
        }
        return Result.success(BatchActionResult(affectedCount = affected, freedKb = freed))
    }

    override suspend fun setAppRestriction(packageName: String, restriction: AppRestriction, userId: Int): Result<Unit> {
        val target = _managedApps.value.firstOrNull { it.packageName == packageName && it.userId == userId }
            ?: return Result.failure(IllegalArgumentException("App not found"))
        if (restriction.hasAnyRestriction && SafetyGuardrails.isRestrictionBlocked(packageName, target.group)) {
            return Result.failure(SecurityException("保消息瘦身档与保护档禁止限制后台"))
        }
        _managedApps.update { list ->
            list.map {
                if (it.packageName == packageName && it.userId == userId) {
                    it.copy(
                        running = if (restriction.disableApp) false else it.running,
                        totalPssKb = if (restriction.disableApp) 0L else it.totalPssKb,
                        swapPssKb = if (restriction.disableApp) 0L else it.swapPssKb,
                        processCount = if (restriction.disableApp) 0 else it.processCount,
                        restriction = restriction,
                        standbyBucket = if (restriction.restrictedBucket) StandbyBucket.RESTRICTED else StandbyBucket.ACTIVE,
                    )
                } else it
            }
        }
        return Result.success(Unit)
    }

    override suspend fun restoreAllRestrictions(): Result<Int> {
        var count = 0
        _managedApps.update { list ->
            list.map {
                if (it.restriction.hasAnyRestriction) {
                    count++
                    it.copy(restriction = AppRestriction(), standbyBucket = StandbyBucket.ACTIVE)
                } else it
            }
        }
        return Result.success(count)
    }

    override suspend fun clearLogs(): Result<Unit> {
        _cleanupLogs.value = emptyList()
        return Result.success(Unit)
    }

    override suspend fun stopSecondaryUser(userId: Int): Result<Long> {
        if (userId <= 0) return Result.failure(SecurityException("主系统用户受保护"))
        val target = _secondaryUsers.value.firstOrNull { it.userId == userId }
            ?: return Result.failure(IllegalArgumentException("未找到该副系统空间"))
        if (!target.running) return Result.success(0L)
        val freed = target.totalPssKb
        _secondaryUsers.update { list ->
            list.map {
                if (it.userId == userId) {
                    it.copy(running = false, processCount = 0, totalPssKb = 0L, swapPssKb = 0L)
                } else it
            }
        }
        _memoryOverview.update {
            it.copy(
                memAvailableKb = (it.memAvailableKb + freed).coerceAtMost(it.memTotalKb),
                swapFreeKb = (it.swapFreeKb + target.swapPssKb).coerceAtMost(it.swapTotalKb),
                sampledAtMs = System.currentTimeMillis(),
            )
        }
        val event = CleanupEvent(
            id = idGen.getAndIncrement(),
            timestampMs = System.currentTimeMillis(),
            packageName = "user:$userId",
            appLabel = "第二系统 · ${target.name}",
            action = CleanupAction.STOP_USER,
            trigger = CleanupTrigger.MANUAL,
            freedKb = freed,
        )
        _cleanupLogs.update { listOf(event) + it }
        return Result.success(freed)
    }

    override suspend fun fetchDiagnostics(): Result<List<DiagnosticLogEntry>> {
        val sample = listOf(
            DiagnosticLogEntry(now - 90_000L, "I", "daemon", "DaemonMain", "Hush preview daemon ready on port 41892"),
            DiagnosticLogEntry(now - 30_000L, "I", "app", "Guard", "Snapshot refreshed (preview mode)"),
        )
        return Result.success((sample + HushLog.snapshot()).sortedBy { it.timestampMs })
    }

    override suspend fun clearDiagnostics(): Result<Unit> {
        HushLog.clear()
        return Result.success(Unit)
    }

    override suspend fun stopDaemon(): Result<Unit> {
        _daemonStatus.value = DaemonStatus.Disconnected
        return Result.success(Unit)
    }

    private fun prependLog(
        app: ManagedAppInfo,
        action: CleanupAction,
        trigger: CleanupTrigger,
        freedKb: Long,
    ) {
        val event = CleanupEvent(
            id = idGen.getAndIncrement(),
            timestampMs = System.currentTimeMillis(),
            packageName = app.packageName,
            appLabel = app.label,
            action = action,
            trigger = trigger,
            freedKb = freedKb,
        )
        _cleanupLogs.update { listOf(event) + it }
        _daemonStatus.update { s ->
            if (s is DaemonStatus.Running) {
                s.copy(
                    todayCleanupCount = s.todayCleanupCount + 1,
                    todayFreedKb = s.todayFreedKb + freedKb,
                )
            } else s
        }
    }
}

class FakeBootstrapController(
    private val fakeGuard: FakeGuardController,
) : BootstrapController {

    private val _state = MutableStateFlow<BootstrapState>(BootstrapState.Idle)
    override val state: StateFlow<BootstrapState> = _state.asStateFlow()

    private val _hasPairedBefore = MutableStateFlow(false)
    override val hasPairedBefore: StateFlow<Boolean> = _hasPairedBefore.asStateFlow()

    override val usbFallbackCommand: String =
        "adb shell 'APK=\$(pm path ${SafetyGuardrails.SELF_PACKAGE} | head -n1 | cut -d: -f2); " +
            "CLASSPATH=\"\$APK\" nohup /system/bin/app_process64 /system/bin ${HushProtocol.DAEMON_MAIN_CLASS} " +
            "--port=38921 --token=hush_preview_demo_token_00000001 >/dev/null 2>&1 &'"

    override fun startDiscovery() {
        _state.value = BootstrapState.Discovering(
            pairingEndpoint = AdbEndpoint("127.0.0.1", 37421, "adb-preview-pair"),
            connectEndpoint = AdbEndpoint("127.0.0.1", 41235, "adb-preview-connect"),
        )
    }

    override fun stopDiscovery() {
        _state.value = BootstrapState.Idle
    }

    override fun showPairingNotification() = Unit
    override fun dismissPairingNotification() = Unit

    override suspend fun pairWithCode(host: String, port: Int, pairingCode: String): Result<Unit> {
        if (pairingCode.length != 6 || !pairingCode.all { it.isDigit() }) {
            val msg = "请输入 6 位数字配对码"
            _state.value = BootstrapState.Error(msg)
            return Result.failure(IllegalArgumentException(msg))
        }
        _state.value = BootstrapState.Pairing(host, port)
        delay(350)
        _hasPairedBefore.value = true
        _state.value = BootstrapState.Paired(host)
        return Result.success(Unit)
    }

    override suspend fun launchDaemon(host: String?, port: Int?): Result<Unit> {
        _state.value = BootstrapState.Launching(host ?: "127.0.0.1", port ?: 41235)
        delay(350)
        fakeGuard.reconnect()
        _state.value = BootstrapState.Ready(port = 41892, suggestDisableWirelessDebug = true)
        return Result.success(Unit)
    }

    override suspend fun attachManualDaemon(port: Int, token: String): Result<Unit> {
        fakeGuard.reconnect()
        _state.value = BootstrapState.Ready(port = port, suggestDisableWirelessDebug = false)
        return Result.success(Unit)
    }

    override suspend fun autoRecoverOnWifi(timeoutMs: Long): Result<Unit> = launchDaemon()
}

class FakeHushGraph : HushGraph {
    private val fakeGuard = FakeGuardController()
    override val guardController: GuardController = fakeGuard
    override val bootstrapController: BootstrapController = FakeBootstrapController(fakeGuard)
    override val isPreviewMode: Boolean = true
}
