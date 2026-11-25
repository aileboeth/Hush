package com.aileboeth.hush.state

import android.content.Context
import com.aileboeth.hush.BuildConfig
import com.aileboeth.hush.contract.AppGroup
import com.aileboeth.hush.contract.AppRestriction
import com.aileboeth.hush.contract.DaemonStatus
import com.aileboeth.hush.contract.HushGraph
import com.aileboeth.hush.contract.HushLog
import com.aileboeth.hush.contract.MemoryOverview
import com.aileboeth.hush.contract.PolicyConfig
import com.aileboeth.hush.contract.SecondaryUserInfo
import com.aileboeth.hush.design.formatMemoryKb
import com.aileboeth.hush.update.AppUpdater
import com.aileboeth.hush.update.ReleaseInfo
import com.aileboeth.hush.update.UpdateState
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class HushStore(
    context: Context,
    private val graph: HushGraph,
) {
    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val guard = graph.guardController
    private val bootstrap = graph.bootstrapController

    private val _uiState = MutableStateFlow(
        HushUiState(
            themePreference = loadThemePreference(),
            isPreviewMode = graph.isPreviewMode,
            usbFallbackCommand = bootstrap.usbFallbackCommand,
        ),
    )
    val uiState: StateFlow<HushUiState> = _uiState.asStateFlow()

    private val _notices = MutableSharedFlow<HushNotice>(extraBufferCapacity = 8)
    val notices: SharedFlow<HushNotice> = _notices.asSharedFlow()

    private var initialAutoConnectDone = false
    private var isForegrounded = true
    private var livePollJob: Job? = null
    private var policyDebounceJob: Job? = null
    private var updateDownloadJob: Job? = null

    init {
        observeControllers()
        startInitialConnect()
        startSilentUpdateCheck()
    }

    private fun observeControllers() {
        scope.launch {
            combine(
                guard.daemonStatus,
                guard.memoryOverview,
                guard.managedApps,
                guard.policyConfig,
                guard.cleanupLogs,
            ) { status, memory, apps, policy, logs ->
                GuardSnapshot(status, memory, apps, policy, logs)
            }.collect { snap ->
                _uiState.update { cur ->
                    val nextHistory = appendMemorySample(cur.memoryHistory, snap.memory)
                    cur.copy(
                        daemonStatus = snap.status,
                        memoryOverview = snap.memory,
                        memoryHistory = nextHistory,
                        managedApps = snap.apps,
                        policyConfig = snap.policy,
                        cleanupLogs = snap.logs,
                    )
                }
                if (snap.status is DaemonStatus.Running && isForegrounded) {
                    ensureLivePolling()
                } else {
                    livePollJob?.cancel()
                    livePollJob = null
                }
            }
        }

        scope.launch {
            guard.secondaryUsers.collect { users ->
                _uiState.update { it.copy(secondaryUsers = users) }
            }
        }

        scope.launch {
            combine(
                bootstrap.state,
                bootstrap.hasPairedBefore,
            ) { state, paired -> state to paired }
                .collect { (bootState, paired) ->
                    _uiState.update { cur ->
                        cur.copy(
                            bootstrapState = bootState,
                            hasPairedBefore = paired,
                            usbFallbackCommand = bootstrap.usbFallbackCommand,
                        )
                    }
                }
        }
    }

    private fun appendMemorySample(
        current: List<MemorySamplePoint>,
        overview: MemoryOverview,
    ): List<MemorySamplePoint> {
        if (overview.memTotalKb <= 0L) return current
        val now = if (overview.sampledAtMs > 0L) overview.sampledAtMs else System.currentTimeMillis()
        val availMb = overview.memAvailableKb / 1024f
        val usedMb = overview.memUsedKb / 1024f
        val swapMb = overview.swapUsedKb / 1024f
        val sample = MemorySamplePoint(
            timestampMs = now,
            availableMb = availMb,
            usedMb = usedMb,
            swapUsedMb = swapMb,
        )

        if (current.isEmpty()) return listOf(sample)

        val last = current.last()
        if (now <= last.timestampMs) return current
        return (current + sample).takeLast(MAX_HISTORY_POINTS)
    }

    private fun startInitialConnect() {
        if (initialAutoConnectDone) return
        initialAutoConnectDone = true
        scope.launch {
            _uiState.update { it.copy(isReconnecting = true) }
            val result = guard.reconnect()
            if (result.isFailure) {
                guard.refreshSnapshot()
            }
            _uiState.update { it.copy(isReconnecting = false) }
        }
    }

    private fun ensureLivePolling() {
        if (!isForegrounded || livePollJob?.isActive == true) return
        livePollJob = scope.launch {
            while (isActive && isForegrounded) {
                delay(LIVE_POLL_INTERVAL_MS)
                if (isForegrounded && _uiState.value.daemonStatus is DaemonStatus.Running && !_uiState.value.isRefreshing) {
                    guard.refreshSnapshot()
                }
            }
        }
    }

    fun setForegrounded(foregrounded: Boolean) {
        if (isForegrounded == foregrounded) return
        isForegrounded = foregrounded
        if (foregrounded) {
            if (_uiState.value.daemonStatus is DaemonStatus.Running) {
                ensureLivePolling()
            }
        } else {
            livePollJob?.cancel()
            livePollJob = null
        }
    }

    fun onAppForegrounded() {
        isForegrounded = true
        val status = _uiState.value.daemonStatus
        scope.launch {
            if (status is DaemonStatus.Running) {
                ensureLivePolling()
                guard.refreshSnapshot()
            } else if (!_uiState.value.isReconnecting) {
                guard.reconnect()
            }
        }
    }

    fun selectTab(tab: HushTab) {
        _uiState.update { it.copy(activeTab = tab) }
    }

    fun navigateToAppsWithFilter(filter: AppFilterMode) {
        _uiState.update { it.copy(activeTab = HushTab.APPS, appFilter = filter) }
    }

    fun cycleThemePreference() {
        val next = when (_uiState.value.themePreference) {
            ThemePreference.SYSTEM -> ThemePreference.LIGHT
            ThemePreference.LIGHT -> ThemePreference.DARK
            ThemePreference.DARK -> ThemePreference.SYSTEM
        }
        prefs.edit().putString(KEY_THEME, next.name).apply()
        _uiState.update { it.copy(themePreference = next) }
        notify("外观：${next.label}")
    }

    fun openStatusPopover() {
        _uiState.update { it.copy(showStatusPopover = true) }
    }

    fun closeStatusPopover() {
        _uiState.update { it.copy(showStatusPopover = false) }
    }

    fun openActivationSheet() {
        _uiState.update { it.copy(showStatusPopover = false, showActivationSheet = true) }
        bootstrap.startDiscovery()
    }

    fun closeActivationSheet() {
        bootstrap.stopDiscovery()
        bootstrap.dismissPairingNotification()
        _uiState.update { it.copy(showActivationSheet = false) }
        if (_uiState.value.daemonStatus is DaemonStatus.Running) {
            refreshSnapshot(silent = true)
        }
    }

    fun openActivitySheet() {
        _uiState.update { it.copy(showActivitySheet = true) }
    }

    fun closeActivitySheet() {
        _uiState.update { it.copy(showActivitySheet = false) }
    }

    fun setAppFilter(filter: AppFilterMode) {
        _uiState.update { it.copy(appFilter = filter) }
    }

    fun setAppSort(sort: AppSortMode) {
        _uiState.update { it.copy(appSort = sort) }
    }

    fun setAppSearchQuery(query: String) {
        _uiState.update { it.copy(appSearchQuery = query) }
    }

    fun openAppDetail(packageName: String) {
        _uiState.update { it.copy(selectedAppPackage = packageName) }
    }

    fun closeAppDetail() {
        _uiState.update { it.copy(selectedAppPackage = null) }
    }

    fun setActivityFilter(filter: ActivityFilterMode) {
        _uiState.update { it.copy(activityFilter = filter) }
    }

    fun reconnectManual() {
        if (_uiState.value.isReconnecting) return
        scope.launch {
            _uiState.update { it.copy(isReconnecting = true) }
            val res = guard.reconnect()
            _uiState.update { it.copy(isReconnecting = false) }
            res.fold(
                onSuccess = { notify("已重新连接后台守护进程") },
                onFailure = { err -> notify(err.message ?: "未检测到运行中的守护进程", isError = true) },
            )
        }
    }

    fun refreshSnapshot(silent: Boolean = false) {
        if (_uiState.value.isRefreshing) return
        scope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            val res = guard.refreshSnapshot()
            _uiState.update { it.copy(isRefreshing = false) }
            if (!silent) {
                res.onFailure { err -> notify(err.message ?: "刷新失败", isError = true) }
            }
        }
    }

    fun setAppGroup(packageName: String, appLabel: String, group: AppGroup) {
        scope.launch {
            val res = guard.setAppGroup(packageName, group)
            res.fold(
                onSuccess = {
                    val label = when (group) {
                        AppGroup.FORCE_STOP -> "强杀"
                        AppGroup.SOFT_TRIM -> "保消息瘦身"
                        AppGroup.PROTECTED -> "常驻保护"
                        AppGroup.UNSET -> "未分类"
                    }
                    notify("「$appLabel」已设为 $label")
                },
                onFailure = { err -> notify(err.message ?: "设置分组失败", isError = true) },
            )
        }
    }

    fun forceStopNow(packageName: String, appLabel: String) {
        if (packageName in _uiState.value.busyPackages) return
        scope.launch {
            _uiState.update { it.copy(busyPackages = it.busyPackages + packageName) }
            val res = guard.forceStopNow(packageName)
            _uiState.update { it.copy(busyPackages = it.busyPackages - packageName) }
            res.fold(
                onSuccess = { freedKb ->
                    val detail = if (freedKb > 0L) " · 释放 ${formatMemoryKb(freedKb)}" else ""
                    notify("已停止「$appLabel」$detail")
                },
                onFailure = { err -> notify(err.message ?: "停止失败", isError = true) },
            )
        }
    }

    fun softTrimNow(packageName: String, appLabel: String) {
        if (packageName in _uiState.value.busyPackages) return
        scope.launch {
            _uiState.update { it.copy(busyPackages = it.busyPackages + packageName) }
            val res = guard.softTrimNow(packageName)
            _uiState.update { it.copy(busyPackages = it.busyPackages - packageName) }
            res.fold(
                onSuccess = { freedKb ->
                    val detail = if (freedKb > 0L) " · 释放 ${formatMemoryKb(freedKb)}" else " · 推送保持活跃"
                    notify("已瘦身「$appLabel」$detail")
                },
                onFailure = { err -> notify(err.message ?: "瘦身失败", isError = true) },
            )
        }
    }

    fun setAppRestriction(packageName: String, restriction: AppRestriction) {
        if (packageName in _uiState.value.busyPackages) return
        val prev = _uiState.value.managedApps.firstOrNull { it.packageName == packageName }?.restriction
        scope.launch {
            _uiState.update { it.copy(busyPackages = it.busyPackages + packageName) }
            val res = guard.setAppRestriction(packageName, restriction)
            _uiState.update { it.copy(busyPackages = it.busyPackages - packageName) }
            res.fold(
                onSuccess = {
                    val msg = when {
                        restriction.disableApp && prev?.disableApp != true -> "已停用应用，系统将不再拉起"
                        !restriction.disableApp && prev?.disableApp == true -> "已恢复启用应用"
                        else -> "后台限制已更新"
                    }
                    notify(msg)
                },
                onFailure = { err -> notify(err.message ?: "更新限制失败", isError = true) },
            )
        }
    }

    fun updatePolicy(config: PolicyConfig, immediate: Boolean = false) {
        _uiState.update { it.copy(policyConfig = config) }
        policyDebounceJob?.cancel()
        policyDebounceJob = scope.launch {
            if (!immediate) delay(240L)
            guard.updatePolicy(config).onFailure { err ->
                notify(err.message ?: "策略同步失败", isError = true)
            }
        }
    }

    fun restoreAllRestrictions() {
        if (_uiState.value.isGlobalActionBusy) return
        scope.launch {
            _uiState.update { it.copy(isGlobalActionBusy = true) }
            val res = guard.restoreAllRestrictions()
            _uiState.update { it.copy(isGlobalActionBusy = false) }
            res.fold(
                onSuccess = { count ->
                    notify(if (count > 0) "已还原 $count 个应用的后台限制" else "当前无已施加的后台限制")
                },
                onFailure = { err -> notify(err.message ?: "还原失败", isError = true) },
            )
        }
    }

    fun clearLogs() {
        if (_uiState.value.isGlobalActionBusy) return
        scope.launch {
            _uiState.update { it.copy(isGlobalActionBusy = true) }
            val res = guard.clearLogs()
            _uiState.update { it.copy(isGlobalActionBusy = false) }
            res.fold(
                onSuccess = { notify("已清空清理记录") },
                onFailure = { err -> notify(err.message ?: "清空失败", isError = true) },
            )
        }
    }

    fun stopSecondaryUser(user: SecondaryUserInfo) {
        if (user.userId in _uiState.value.busyUserIds) return
        scope.launch {
            _uiState.update { it.copy(busyUserIds = it.busyUserIds + user.userId) }
            val res = guard.stopSecondaryUser(user.userId)
            _uiState.update { it.copy(busyUserIds = it.busyUserIds - user.userId) }
            res.fold(
                onSuccess = { freedKb ->
                    val detail = if (freedKb > 0L) " · 释放 ${formatMemoryKb(freedKb)}" else ""
                    notify("已彻底停机「${user.name}」$detail")
                },
                onFailure = { err -> notify(err.message ?: "停机第二系统失败", isError = true) },
            )
        }
    }

    fun stopDaemon() {
        if (_uiState.value.isGlobalActionBusy) return
        scope.launch {
            _uiState.update { it.copy(isGlobalActionBusy = true) }
            val res = guard.stopDaemon()
            _uiState.update { it.copy(isGlobalActionBusy = false) }
            res.fold(
                onSuccess = { notify("守护进程已安全停止") },
                onFailure = { err -> notify(err.message ?: "停止失败", isError = true) },
            )
        }
    }

    fun restartBootstrapDiscovery() {
        bootstrap.stopDiscovery()
        bootstrap.startDiscovery()
        notify("正在重新侦测本机无线调试")
    }

    fun showPairingNotification() {
        bootstrap.showPairingNotification()
        notify("已发送配对通知，请从屏幕左上角下拉填写")
    }

    fun pairWithCode(host: String, port: Int, pairingCode: String) {
        scope.launch {
            bootstrap.pairWithCode(host, port, pairingCode).fold(
                onSuccess = { notify("配对成功，现在可以启动守护进程") },
                onFailure = { err -> notify(err.message ?: "配对失败，请核对配对码和端口", isError = true) },
            )
        }
    }

    fun launchDaemon(host: String? = null, port: Int? = null) {
        scope.launch {
            bootstrap.launchDaemon(host, port).fold(
                onSuccess = { notify("已激活，现在可以关闭无线调试") },
                onFailure = { err -> notify(err.message ?: "启动失败", isError = true) },
            )
        }
    }

    fun attachManualDaemon(port: Int = 0, token: String = "") {
        scope.launch {
            bootstrap.attachManualDaemon(port, token).fold(
                onSuccess = { notify("已连接 USB 启动的守护进程") },
                onFailure = { err -> notify(err.message ?: "手动连接失败", isError = true) },
            )
        }
    }

    fun openDisclaimerDialog() {
        _uiState.update { it.copy(showDisclaimerDialog = true) }
    }

    fun closeDisclaimerDialog() {
        _uiState.update { it.copy(showDisclaimerDialog = false) }
    }

    fun openUpdateDialog() {
        _uiState.update { it.copy(showUpdateDialog = true) }
    }

    fun closeUpdateDialog() {
        _uiState.update { it.copy(showUpdateDialog = false) }
    }

    private fun startSilentUpdateCheck() {
        if (graph.isPreviewMode) return
        scope.launch {
            delay(2_500L)
            checkForUpdates(silent = true)
        }
    }

    fun checkForUpdates(silent: Boolean = false) {
        val current = _uiState.value.updateState
        if (current is UpdateState.Checking || current is UpdateState.Downloading) return
        scope.launch {
            if (!silent) {
                _uiState.update { it.copy(updateState = UpdateState.Checking) }
            }
            val res = AppUpdater.checkLatestRelease(BuildConfig.VERSION_NAME)
            res.fold(
                onSuccess = { latest ->
                    if (latest != null) {
                        val lastPrompted = prefs.getString(KEY_LAST_PROMPTED_VERSION, null)
                        val shouldPop = !silent || lastPrompted != latest.versionName
                        if (shouldPop) {
                            prefs.edit().putString(KEY_LAST_PROMPTED_VERSION, latest.versionName).apply()
                        }
                        _uiState.update {
                            it.copy(
                                updateState = UpdateState.Available(latest),
                                showUpdateDialog = it.showUpdateDialog || shouldPop,
                            )
                        }
                        if (!silent) {
                            notify("发现新版本 v${latest.versionName}")
                        }
                    } else {
                        _uiState.update { it.copy(updateState = UpdateState.UpToDate(BuildConfig.VERSION_NAME)) }
                        if (!silent) {
                            notify("当前已是最新版本 v${BuildConfig.VERSION_NAME}")
                        }
                    }
                },
                onFailure = { err ->
                    if (!silent) {
                        val msg = err.message ?: "无法连接 GitHub，请稍后重试"
                        _uiState.update { it.copy(updateState = UpdateState.Error(msg)) }
                        notify("检查更新失败：$msg", isError = true)
                    }
                },
            )
        }
    }

    fun startUpdateDownload(release: ReleaseInfo) {
        if (_uiState.value.updateState is UpdateState.Downloading) return
        updateDownloadJob?.cancel()
        updateDownloadJob = scope.launch {
            _uiState.update {
                it.copy(
                    updateState = UpdateState.Downloading(release, 0L, release.apkSizeBytes),
                    showUpdateDialog = true,
                )
            }
            val res = AppUpdater.downloadApk(appContext, release) { downloaded, total ->
                _uiState.update { cur ->
                    if (cur.updateState is UpdateState.Downloading) {
                        cur.copy(updateState = UpdateState.Downloading(release, downloaded, total))
                    } else cur
                }
            }
            res.fold(
                onSuccess = { apkFile ->
                    _uiState.update {
                        it.copy(updateState = UpdateState.ReadyToInstall(release, apkFile.absolutePath))
                    }
                    installDownloadedUpdate()
                },
                onFailure = { err ->
                    val msg = err.message ?: "下载安装包失败"
                    _uiState.update { it.copy(updateState = UpdateState.Error(msg, release)) }
                    notify("下载失败：$msg", isError = true)
                },
            )
        }
    }

    fun cancelUpdateDownload() {
        val current = _uiState.value.updateState
        updateDownloadJob?.cancel()
        updateDownloadJob = null
        if (current is UpdateState.Downloading) {
            _uiState.update { it.copy(updateState = UpdateState.Available(current.release)) }
            notify("已取消更新下载")
        }
    }

    fun installDownloadedUpdate() {
        val ready = _uiState.value.updateState as? UpdateState.ReadyToInstall ?: return
        AppUpdater.launchApkInstaller(appContext, File(ready.apkPath)).fold(
            onSuccess = { openedInstaller ->
                if (!openedInstaller) {
                    notify("请先开启「允许安装未知应用」，返回后再点立即安装")
                }
            },
            onFailure = { err ->
                notify(err.message ?: "唤起安装器失败", isError = true)
            },
        )
    }

    fun openDiagnosticsSheet() {
        _uiState.update { it.copy(showDiagnosticsSheet = true) }
        refreshDiagnostics()
    }

    fun closeDiagnosticsSheet() {
        _uiState.update { it.copy(showDiagnosticsSheet = false) }
    }

    fun refreshDiagnostics() {
        scope.launch {
            _uiState.update { it.copy(isDiagnosticsLoading = true) }
            val res = guard.fetchDiagnostics()
            _uiState.update { cur ->
                cur.copy(
                    isDiagnosticsLoading = false,
                    diagnosticLogs = res.getOrDefault(HushLog.snapshot()),
                )
            }
        }
    }

    fun clearDiagnostics() {
        scope.launch {
            guard.clearDiagnostics()
            _uiState.update { it.copy(diagnosticLogs = emptyList()) }
            notify("已清空诊断日志")
        }
    }

    fun notify(message: String, isError: Boolean = false) {
        if (isError) {
            HushLog.w("Notice", message)
        }
        _notices.tryEmit(HushNotice(message, isError))
    }

    private fun loadThemePreference(): ThemePreference {
        val raw = prefs.getString(KEY_THEME, ThemePreference.SYSTEM.name)
        return runCatching { ThemePreference.valueOf(raw ?: ThemePreference.SYSTEM.name) }
            .getOrDefault(ThemePreference.SYSTEM)
    }

    private data class GuardSnapshot(
        val status: DaemonStatus,
        val memory: MemoryOverview,
        val apps: List<com.aileboeth.hush.contract.ManagedAppInfo>,
        val policy: PolicyConfig,
        val logs: List<com.aileboeth.hush.contract.CleanupEvent>,
    )

    companion object {
        private const val PREFS_NAME = "hush_ui_prefs"
        private const val KEY_THEME = "theme_preference"
        private const val KEY_LAST_PROMPTED_VERSION = "last_prompted_update_version"
        private const val LIVE_POLL_INTERVAL_MS = 8_000L
        private const val MAX_HISTORY_POINTS = 24
    }
}
