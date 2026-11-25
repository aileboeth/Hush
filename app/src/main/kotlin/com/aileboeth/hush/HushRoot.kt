package com.aileboeth.hush

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aileboeth.hush.contract.DaemonStatus
import com.aileboeth.hush.design.AppleFloatingTabBar
import com.aileboeth.hush.design.AppleRubberBandSheet
import com.aileboeth.hush.design.HushIcons
import com.aileboeth.hush.design.HushTheme
import com.aileboeth.hush.design.HushType
import com.aileboeth.hush.feature.activation.ActivationMorphPanel
import com.aileboeth.hush.feature.activity.ActivitySheetContent
import com.aileboeth.hush.feature.apps.AppDetailOverlay
import com.aileboeth.hush.feature.apps.AppsScreen
import com.aileboeth.hush.feature.overview.OverviewScreen
import com.aileboeth.hush.feature.policies.PoliciesScreen
import com.aileboeth.hush.state.HushStore
import com.aileboeth.hush.state.HushTab

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun HushRoot(
    store: HushStore,
    modifier: Modifier = Modifier,
) {
    val c = HushTheme.colors
    val uiState by store.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var lastNoticeIsError by remember { mutableStateOf(false) }
    var detailOpenedFromOverview by remember { mutableStateOf(false) }

    BackHandler(enabled = uiState.showActivitySheet) {
        store.closeActivitySheet()
    }
    BackHandler(enabled = !uiState.showActivitySheet && uiState.showActivationPanel) {
        store.closeActivationPanel()
    }
    BackHandler(enabled = !uiState.showActivitySheet && !uiState.showActivationPanel && uiState.selectedAppPackage != null) {
        store.closeAppDetail()
    }
    BackHandler(
        enabled = !uiState.showActivitySheet &&
            !uiState.showActivationPanel &&
            uiState.selectedAppPackage == null &&
            uiState.activeTab != HushTab.OVERVIEW,
    ) {
        store.selectTab(HushTab.OVERVIEW)
    }

    LaunchedEffect(store) {
        store.notices.collect { notice ->
            lastNoticeIsError = notice.isError
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(
                message = notice.message,
                duration = SnackbarDuration.Short,
            )
        }
    }

    SharedTransitionLayout(
        modifier = modifier
            .fillMaxSize()
            .background(c.canvas),
    ) {
        val sharedScope = this
        Box(modifier = Modifier.fillMaxSize()) {

        // Primary Content Layer vs Shared-Element App Detail Page
        AnimatedContent(
            targetState = uiState.selectedApp,
            transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(200)) },
            label = "sharedAppDetail",
        ) { selectedApp ->
            val animScope = this
            if (selectedApp != null) {
                AppDetailOverlay(
                    app = selectedApp,
                    fromOverview = detailOpenedFromOverview,
                    isDaemonOnline = uiState.daemonStatus is DaemonStatus.Running,
                    isBusy = selectedApp.packageName in uiState.busyPackages,
                    sharedTransitionScope = sharedScope,
                    animatedVisibilityScope = animScope,
                    onClose = { store.closeAppDetail() },
                    onSelectGroup = { group ->
                        store.setAppGroup(selectedApp.packageName, selectedApp.label, group)
                    },
                    onForceStop = {
                        store.forceStopNow(selectedApp.packageName, selectedApp.label)
                    },
                    onSoftTrim = {
                        store.softTrimNow(selectedApp.packageName, selectedApp.label)
                    },
                    onUpdateRestriction = { restriction ->
                        store.setAppRestriction(selectedApp.packageName, restriction)
                    },
                    onCopiedNotice = { store.notify(it) },
                )
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Main Tabbed Content
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding(),
                    ) {
                        Crossfade(
                            targetState = uiState.activeTab,
                            animationSpec = tween(220),
                            label = "tabCrossfade",
                        ) { tab ->
                            when (tab) {
                                HushTab.OVERVIEW -> OverviewScreen(
                                    uiState = uiState,
                                    sharedTransitionScope = sharedScope,
                                    animatedVisibilityScope = animScope,
                                    onRefresh = { store.refreshSnapshot() },
                                    onCycleTheme = { store.cycleThemePreference() },
                                    onOpenActivation = { store.openActivationPanel() },
                                    onReconnect = { store.reconnectManual() },
                                    onOpenActivitySheet = { store.openActivitySheet() },
                                    onNavigateAppsFilter = { store.navigateToAppsWithFilter(it) },
                                    onOpenAppDetail = { pkg ->
                                        detailOpenedFromOverview = true
                                        store.openAppDetail(pkg)
                                    },
                                    onQuickForceStop = { pkg, label -> store.forceStopNow(pkg, label) },
                                    onQuickSoftTrim = { pkg, label -> store.softTrimNow(pkg, label) },
                                )
                                HushTab.APPS -> AppsScreen(
                                    uiState = uiState,
                                    sharedTransitionScope = sharedScope,
                                    animatedVisibilityScope = animScope,
                                    onRefresh = { store.refreshSnapshot() },
                                    onFilterChange = { store.setAppFilter(it) },
                                    onSortChange = { store.setAppSort(it) },
                                    onSearchChange = { store.setAppSearchQuery(it) },
                                    onSelectApp = { pkg ->
                                        detailOpenedFromOverview = false
                                        store.openAppDetail(pkg)
                                    },
                                )
                                HushTab.POLICIES -> PoliciesScreen(
                                    uiState = uiState,
                                    onUpdatePolicy = { cfg, imm -> store.updatePolicy(cfg, imm) },
                                    onOpenActivitySheet = { store.openActivitySheet() },
                                    onRestoreAllRestrictions = { store.restoreAllRestrictions() },
                                    onOpenActivation = { store.openActivationPanel() },
                                    onStopDaemon = { store.stopDaemon() },
                                )
                            }
                        }
                    }

                    // Apple Liquid Floating 3-Tab Pill Dock
                    AppleFloatingTabBar(
                        activeTab = uiState.activeTab,
                        onSelectTab = { store.selectTab(it) },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 14.dp),
                    )
                }
            }
        }

        // Fluid Morph Overlay: Status Capsule <-> Activation & Daemon Control Panel
        AnimatedVisibility(
            visible = uiState.showActivationPanel,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(180)),
        ) {
            ActivationMorphPanel(
                uiState = uiState,
                sharedTransitionScope = sharedScope,
                animatedVisibilityScope = this,
                onClose = { store.closeActivationPanel() },
                onRestartDiscovery = { store.restartBootstrapDiscovery() },
                onShowPairingNotification = { store.showPairingNotification() },
                onPairWithCode = { host, port, code -> store.pairWithCode(host, port, code) },
                onLaunchDaemon = { host, port -> store.launchDaemon(host, port) },
                onAttachManualDaemon = { port, token -> store.attachManualDaemon(port, token) },
                onCopiedNotice = { store.notify(it) },
            )
        }

        // Physics-Driven iOS Rubber-Band Bottom Sheet for Cleanup Activity Timeline
        AppleRubberBandSheet(
            visible = uiState.showActivitySheet,
            onDismiss = { store.closeActivitySheet() },
        ) {
            ActivitySheetContent(
                uiState = uiState,
                onFilterChange = { store.setActivityFilter(it) },
                onClearLogs = { store.clearLogs() },
                onClose = { store.closeActivitySheet() },
            )
        }

        // Dynamic-Island-inspired Floating Toast Pill at top
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 10.dp, start = 24.dp, end = 24.dp),
        ) { data ->
            val accent = if (lastNoticeIsError) c.red else c.green
            Surface(
                shape = RoundedCornerShape(100.dp),
                color = if (c.isDark) c.surfaceElevated else c.textPrimary,
                border = if (c.isDark) BorderStroke(0.7.dp, c.glassBorder) else null,
                shadowElevation = 10.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (lastNoticeIsError) HushIcons.Alert else HushIcons.Check,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = data.visuals.message,
                        style = HushType.caption,
                        color = if (c.isDark) c.textPrimary else c.surface,
                    )
                }
            }
        }
        }
    }
}
