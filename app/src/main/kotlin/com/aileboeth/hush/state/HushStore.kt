package com.aileboeth.hush.state

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.aileboeth.hush.BuildConfig
import com.aileboeth.hush.contract.AppGroup
import com.aileboeth.hush.contract.AppKey
import com.aileboeth.hush.contract.AppRestriction
import com.aileboeth.hush.contract.AppTarget
import com.aileboeth.hush.contract.CustomAppGroup
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
    private var userExplicitlyStopped: Boolean = prefs.getBoolean(KEY_USER_STOPPED, false)
    private var lastAutoRecoverAttemptMs = 0L
    private var livePollJob: Job? = null
    private var wifiRecoverJob: Job? = null
    private var policyDebounceJob: Job? = null
    private var updateDownloadJob: Job? = null

    init {
        observeControllers()
        registerWifiAutoRecoverCallback()
        startInitialConnect()
        startSilentUpdateCheck()
    }

    private fun setUserStopped(stopped: Boolean) {
        userExplicitlyStopped = stopped
        prefs.edit().putBoolean(KEY_USER_STOPPED, stopped).apply()
    }

    private fun registerWifiAutoRecoverCallback() {
        if (graph.isPreviewMode) return
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                wifiRecoverJob?.cancel()
                wifiRecoverJob = scope.launch {
                    // Let DHCP & local routing settle briefly after Wi-Fi connects / renews
                    delay(2_500L)
                    if (userExplicitlyStopped || !_uiState.value.hasPairedBefore) return@launch
                    val alive = guard.reconnect().isSuccess
                    if (!alive) {
                        HushLog.i("Store", "Wi-Fi available while daemon offline; attempting auto-recover")
                        trySilentWifiAutoRecover(timeoutMs = 6_000L, silentNotice = !isForegrounded)
                    }
                }
            }
        }
        runCatching { cm.registerNetworkCallback(req, cb) }
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
            guard.customGroups.collect { groups ->
                _uiState.update { cur ->
                    val validSelectedId = cur.selectedCustomGroupId?.takeIf { id -> groups.any { it.id == id } }
                    cur.copy(customGroups = groups, selectedCustomGroupId = validSelectedId)
                }
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
                val recovered = trySilentWifiAutoRecover()
                if (!recovered) {
                    guard.refreshSnapshot()
                }
            }
            _uiState.update { it.copy(isReconnecting = false) }
        }
    }

    private suspend fun trySilentWifiAutoRecover(
        timeoutMs: Long = 5_000L,
        silentNotice: Boolean = false,
    ): Boolean {
        if (userExplicitlyStopped || !_uiState.value.hasPairedBefore) return false
        val now = System.currentTimeMillis()
        if (now - lastAutoRecoverAttemptMs < AUTO_RECOVER_COOLDOWN_MS) return false
        lastAutoRecoverAttemptMs = now
        val res = bootstrap.autoRecoverOnWifi(timeoutMs = timeoutMs)
        if (res.isSuccess) {
            if (!silentNotice) {
                notify("已自动恢复后台守护进程")
            }
            return true
        }
        return false
    }

    /** Called by `HushAutoRecoverJobService` (Wi-Fi unmetered periodic watchdog, no notification). */
    suspend fun runBackgroundWatchdogCheck(): Boolean {
        if (graph.isPreviewMode || userExplicitlyStopped || !bootstrap.hasPairedBefore.value) return false
        if (guard.reconnect().isSuccess) return true
        HushLog.i("Watchdog", "Periodic Wi-Fi check found daemon offline; attempting background auto-recover")
        return trySilentWifiAutoRecover(timeoutMs = 6_500L, silentNotice = true)
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
                val snap = guard.refreshSnapshot()
                if (snap.isFailure && !userExplicitlyStopped && !_uiState.value.isReconnecting) {
                    _uiState.update { it.copy(isReconnecting = true) }
                    trySilentWifiAutoRecover(timeoutMs = 5_500L)
                    _uiState.update { it.copy(isReconnecting = false) }
                }
            } else if (!_uiState.value.isReconnecting && !userExplicitlyStopped) {
                _uiState.update { it.copy(isReconnecting = true) }
                val reconn = guard.reconnect()
                if (reconn.isFailure) {
                    trySilentWifiAutoRecover(timeoutMs = 5_500L)
                }
                _uiState.update { it.copy(isReconnecting = false) }
            }
        }
    }

    fun selectTab(tab: HushTab) {
        _uiState.update { it.copy(activeTab = tab) }
    }

    fun navigateToAppsWithFilter(filter: AppFilterMode) {
        _uiState.update {
            it.copy(
                activeTab = HushTab.APPS,
                appFilter = filter,
                selectedCustomGroupId = null,
            )
        }
    }

    fun navigateToAppsWithCustomGroup(groupId: String) {
        _uiState.update {
            it.copy(
                activeTab = HushTab.APPS,
                selectedCustomGroupId = groupId,
            )
        }
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
        _uiState.update { it.copy(appFilter = filter, selectedCustomGroupId = null) }
    }

    fun selectCustomGroup(groupId: String?) {
        _uiState.update { it.copy(selectedCustomGroupId = groupId) }
    }

    fun setAppSort(sort: AppSortMode) {
        _uiState.update { it.copy(appSort = sort) }
    }

    fun setAppSearchQuery(query: String) {
        _uiState.update { it.copy(appSearchQuery = query) }
    }

    fun openAppDetail(appKey: String) {
        _uiState.update { it.copy(selectedAppKey = appKey) }
    }

    fun closeAppDetail() {
        _uiState.update { it.copy(selectedAppKey = null) }
    }

    fun openBuiltInGroupEditor(tier: AppGroup) {
        _uiState.update { it.copy(groupEditorTarget = GroupEditorTarget.BuiltInTier(tier)) }
    }

    fun openCustomGroupEditor(group: CustomAppGroup? = null, preselectedAppKeys: Set<String> = emptySet()) {
        _uiState.update { it.copy(groupEditorTarget = GroupEditorTarget.Custom(group, preselectedAppKeys)) }
    }

    fun closeGroupEditor() {
        _uiState.update { it.copy(groupEditorTarget = null) }
    }

    fun setActivityFilter(filter: ActivityFilterMode) {
        _uiState.update { it.copy(activityFilter = filter) }
    }

    fun reconnectManual() {
        if (_uiState.value.isReconnecting) return
        setUserStopped(false)
        scope.launch {
            _uiState.update { it.copy(isReconnecting = true) }
            val res = guard.reconnect()
            if (res.isSuccess) {
                _uiState.update { it.copy(isReconnecting = false) }
                notify("已重新连接后台守护进程")
                return@launch
            }
            if (_uiState.value.hasPairedBefore) {
                lastAutoRecoverAttemptMs = System.currentTimeMillis()
                val recovered = bootstrap.autoRecoverOnWifi(timeoutMs = 5_500L)
                _uiState.update { it.copy(isReconnecting = false) }
                if (recovered.isSuccess) {
                    notify("已通过无线调试自动恢复守护进程")
                    return@launch
                }
            } else {
                _uiState.update { it.copy(isReconnecting = false) }
            }
            val err = res.exceptionOrNull()
            notify(err?.message ?: "未检测到运行中的守护进程", isError = true)
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

    fun setAppGroup(packageName: String, appLabel: String, group: AppGroup, userId: Int = 0) {
        scope.launch {
            val res = guard.setAppGroup(packageName, group, userId)
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

    fun saveBuiltInGroupMembers(tier: AppGroup, selectedKeys: Set<String>) {
        scope.launch {
            val currentApps = _uiState.value.managedApps
            val updates = LinkedHashMap<String, AppGroup>()
            val removedKeys = HashSet<String>()
            for (app in currentApps) {
                if (app.isSystemProtected) continue
                val wasInTier = app.group == tier
                val nowInTier = app.appKey in selectedKeys
                if (nowInTier && !wasInTier) {
                    updates[app.appKey] = tier
                } else if (!nowInTier && wasInTier) {
                    updates[app.appKey] = AppGroup.UNSET
                    removedKeys.add(app.appKey)
                }
            }
            // If an unchecked app inherited this tier from a custom group of the same action, remove it there too
            if (removedKeys.isNotEmpty()) {
                for (cg in _uiState.value.customGroups) {
                    if (cg.action == tier && cg.appKeys.any { it in removedKeys }) {
                        guard.saveCustomGroup(cg.copy(appKeys = cg.appKeys.filterNot { it in removedKeys }))
                    }
                }
            }
            val res = guard.setAppGroupsBatch(updates)
            _uiState.update { it.copy(groupEditorTarget = null) }
            res.fold(
                onSuccess = {
                    val name = when (tier) {
                        AppGroup.FORCE_STOP -> "强杀组"
                        AppGroup.SOFT_TRIM -> "保消息瘦身组"
                        AppGroup.PROTECTED -> "常驻保护组"
                        AppGroup.UNSET -> "未分类"
                    }
                    notify("已更新「$name」成员（共 ${selectedKeys.size} 个）")
                },
                onFailure = { err -> notify(err.message ?: "更新成员失败", isError = true) },
            )
        }
    }

    fun saveCustomGroup(group: CustomAppGroup, selectAfterSave: Boolean = true) {
        scope.launch {
            val res = guard.saveCustomGroup(group)
            res.fold(
                onSuccess = {
                    _uiState.update { cur ->
                        cur.copy(
                            groupEditorTarget = null,
                            selectedCustomGroupId = if (selectAfterSave) group.id else cur.selectedCustomGroupId,
                        )
                    }
                    notify("已保存分组「${group.name}」（${group.appKeys.size} 个应用）")
                },
                onFailure = { err -> notify(err.message ?: "保存分组失败", isError = true) },
            )
        }
    }

    fun deleteCustomGroup(group: CustomAppGroup) {
        scope.launch {
            val res = guard.deleteCustomGroup(group.id)
            res.fold(
                onSuccess = {
                    _uiState.update { cur ->
                        cur.copy(
                            groupEditorTarget = null,
                            selectedCustomGroupId = if (cur.selectedCustomGroupId == group.id) null else cur.selectedCustomGroupId,
                        )
                    }
                    notify("已删除分组「${group.name}」")
                },
                onFailure = { err -> notify(err.message ?: "删除分组失败", isError = true) },
            )
        }
    }

    fun toggleAppInCustomGroup(groupId: String, appKey: String) {
        val group = _uiState.value.customGroups.firstOrNull { it.id == groupId } ?: return
        val nextKeys = if (appKey in group.appKeys) {
            group.appKeys.filterNot { it == appKey }
        } else {
            group.appKeys + appKey
        }
        scope.launch {
            guard.saveCustomGroup(group.copy(appKeys = nextKeys)).fold(
                onSuccess = {
                    val added = appKey in nextKeys
                    notify(if (added) "已加入分组「${group.name}」" else "已移出分组「${group.name}」")
                },
                onFailure = { err -> notify(err.message ?: "更新分组失败", isError = true) },
            )
        }
    }

    fun executeGroupAction(groupName: String, action: AppGroup, targets: List<AppTarget>) {
        if (_uiState.value.isGlobalActionBusy) return
        if (targets.isEmpty()) {
            notify("「$groupName」内暂无可操作的应用")
            return
        }
        scope.launch {
            _uiState.update { it.copy(isGlobalActionBusy = true) }
            val res = when (action) {
                AppGroup.SOFT_TRIM -> guard.softTrimBatch(targets)
                else -> guard.forceStopBatch(targets)
            }
            _uiState.update { it.copy(isGlobalActionBusy = false) }
            res.fold(
                onSuccess = { batch ->
                    val verb = if (action == AppGroup.SOFT_TRIM) "瘦身" else "强杀"
                    if (batch.affectedCount == 0) {
                        notify("「$groupName」当前没有运行中的应用")
                    } else {
                        val freedText = if (batch.freedKb > 0L) " · 释放 ${formatMemoryKb(batch.freedKb)}" else ""
                        notify("已$verb「$groupName」${batch.affectedCount} 个应用$freedText")
                    }
                },
                onFailure = { err -> notify(err.message ?: "批量执行失败", isError = true) },
            )
        }
    }

    fun forceStopNow(packageName: String, appLabel: String, userId: Int = 0) {
        val key = AppKey.encode(packageName, userId)
        if (key in _uiState.value.busyPackages) return
        scope.launch {
            _uiState.update { it.copy(busyPackages = it.busyPackages + key) }
            val res = guard.forceStopNow(packageName, userId)
            _uiState.update { it.copy(busyPackages = it.busyPackages - key) }
            res.fold(
                onSuccess = { freedKb ->
                    val detail = if (freedKb > 0L) " · 释放 ${formatMemoryKb(freedKb)}" else ""
                    notify("已停止「$appLabel」$detail")
                },
                onFailure = { err -> notify(err.message ?: "停止失败", isError = true) },
            )
        }
    }

    fun softTrimNow(packageName: String, appLabel: String, userId: Int = 0) {
        val key = AppKey.encode(packageName, userId)
        if (key in _uiState.value.busyPackages) return
        scope.launch {
            _uiState.update { it.copy(busyPackages = it.busyPackages + key) }
            val res = guard.softTrimNow(packageName, userId)
            _uiState.update { it.copy(busyPackages = it.busyPackages - key) }
            res.fold(
                onSuccess = { freedKb ->
                    val detail = if (freedKb > 0L) " · 释放 ${formatMemoryKb(freedKb)}" else " · 推送保持活跃"
                    notify("已瘦身「$appLabel」$detail")
                },
                onFailure = { err -> notify(err.message ?: "瘦身失败", isError = true) },
            )
        }
    }

    fun setAppRestriction(packageName: String, restriction: AppRestriction, userId: Int = 0) {
        val key = AppKey.encode(packageName, userId)
        if (key in _uiState.value.busyPackages) return
        val prev = _uiState.value.managedApps.firstOrNull { it.appKey == key }?.restriction
        scope.launch {
            _uiState.update { it.copy(busyPackages = it.busyPackages + key) }
            val res = guard.setAppRestriction(packageName, restriction, userId)
            _uiState.update { it.copy(busyPackages = it.busyPackages - key) }
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
        setUserStopped(true)
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
        setUserStopped(false)
        scope.launch {
            bootstrap.launchDaemon(host, port).fold(
                onSuccess = { notify("守护进程已激活并上线") },
                onFailure = { err -> notify(err.message ?: "启动失败", isError = true) },
            )
        }
    }

    fun attachManualDaemon(port: Int = 0, token: String = "") {
        setUserStopped(false)
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
        private const val KEY_USER_STOPPED = "user_explicitly_stopped_daemon"
        private const val LIVE_POLL_INTERVAL_MS = 8_000L
        private const val MAX_HISTORY_POINTS = 24
        private const val AUTO_RECOVER_COOLDOWN_MS = 30_000L
    }
}
