package com.aileboeth.hush

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aileboeth.hush.contract.DaemonStatus
import com.aileboeth.hush.design.AppleCapsuleMorph
import com.aileboeth.hush.design.AppleFloatingTabBar
import com.aileboeth.hush.design.AppleMeterBar
import com.aileboeth.hush.design.ApplePillButton
import com.aileboeth.hush.design.AppleRubberBandSheet
import com.aileboeth.hush.design.HushIcons
import com.aileboeth.hush.design.HushSprings
import com.aileboeth.hush.design.HushTheme
import com.aileboeth.hush.design.HushType
import com.aileboeth.hush.design.MorphAnchor
import com.aileboeth.hush.design.applePressScale
import com.aileboeth.hush.design.formatMemoryMbFloat
import com.aileboeth.hush.design.openExternalUrl
import com.aileboeth.hush.feature.activation.ActivationSheetContent
import com.aileboeth.hush.feature.activation.StatusCapsulePopover
import com.aileboeth.hush.feature.activity.ActivitySheetContent
import com.aileboeth.hush.feature.apps.AppDetailOverlay
import com.aileboeth.hush.feature.apps.AppsScreen
import com.aileboeth.hush.feature.apps.GroupEditorSheetContent
import com.aileboeth.hush.feature.diagnostics.DiagnosticsSheetContent
import com.aileboeth.hush.feature.overview.OverviewScreen
import com.aileboeth.hush.feature.overview.StatusCapsule
import com.aileboeth.hush.feature.policies.PoliciesScreen
import com.aileboeth.hush.state.HushStore
import com.aileboeth.hush.state.HushTab
import com.aileboeth.hush.update.AppUpdater
import com.aileboeth.hush.update.ReleaseInfo
import com.aileboeth.hush.update.UpdateState
import kotlin.math.roundToInt

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

    val capsuleAnchor = remember { MorphAnchor() }
    var capsuleHidden by remember { mutableStateOf(false) }
    var activationProgress by remember { mutableFloatStateOf(0f) }
    var activityProgress by remember { mutableFloatStateOf(0f) }
    var diagnosticsProgress by remember { mutableFloatStateOf(0f) }
    var groupEditorProgress by remember { mutableFloatStateOf(0f) }
    var showStopConfirm by remember { mutableStateOf(false) }

    // Hoist scroll states ABOVE AnimatedContent & Crossfade so opening detail / force-stopping / switching tabs NEVER jumps back to top!
    val overviewListState = rememberLazyListState()
    val appsListState = rememberLazyListState()
    val policiesListState = rememberLazyListState()
    var savedAppsScrollIndex by remember { mutableIntStateOf(-1) }
    var savedAppsScrollOffset by remember { mutableIntStateOf(0) }

    LaunchedEffect(uiState.selectedAppKey) {
        if (uiState.selectedAppKey == null && savedAppsScrollIndex >= 0) {
            val targetIdx = savedAppsScrollIndex
            val targetOffset = savedAppsScrollOffset
            savedAppsScrollIndex = -1
            // Guard against Compose chasing a moved firstVisibleItemKey after force-stop
            if (kotlin.math.abs(appsListState.firstVisibleItemIndex - targetIdx) > 1) {
                appsListState.scrollToItem(targetIdx, targetOffset)
            }
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        store.setForegrounded(true)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        store.setForegrounded(false)
    }

    // Back handling from highest overlay down to default tab
    BackHandler(enabled = showStopConfirm) {
        showStopConfirm = false
    }
    BackHandler(enabled = !showStopConfirm && uiState.groupEditorTarget != null) {
        store.closeGroupEditor()
    }
    BackHandler(enabled = !showStopConfirm && uiState.groupEditorTarget == null && uiState.showDiagnosticsSheet) {
        store.closeDiagnosticsSheet()
    }
    BackHandler(enabled = !showStopConfirm && uiState.groupEditorTarget == null && !uiState.showDiagnosticsSheet && uiState.showActivitySheet) {
        store.closeActivitySheet()
    }
    BackHandler(enabled = !showStopConfirm && uiState.groupEditorTarget == null && !uiState.showActivitySheet && uiState.showActivationSheet) {
        store.closeActivationSheet()
    }
    BackHandler(
        enabled = !showStopConfirm &&
            uiState.groupEditorTarget == null &&
            !uiState.showActivitySheet &&
            !uiState.showActivationSheet &&
            uiState.showStatusPopover,
    ) {
        store.closeStatusPopover()
    }
    BackHandler(
        enabled = !showStopConfirm &&
            uiState.groupEditorTarget == null &&
            !uiState.showActivitySheet &&
            !uiState.showActivationSheet &&
            !uiState.showStatusPopover &&
            uiState.selectedAppKey != null,
    ) {
        store.closeAppDetail()
    }
    BackHandler(
        enabled = !showStopConfirm &&
            uiState.groupEditorTarget == null &&
            !uiState.showActivitySheet &&
            !uiState.showActivationSheet &&
            !uiState.showStatusPopover &&
            uiState.selectedAppKey == null &&
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

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val statusBarTopPx = with(LocalDensity.current) { statusBarTop.toPx() }
    val sheetDepth = maxOf(activationProgress, activityProgress, diagnosticsProgress, groupEditorProgress).coerceIn(0f, 1f)
    val receded = sheetDepth > 0.35f

    // Keep system status-bar icons legible when the light backdrop recedes onto black behind a sheet
    val activity = LocalContext.current as? Activity
    LaunchedEffect(receded, c.isDark) {
        activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars =
                !receded && !c.isDark
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // Primary Content Backdrop — recedes smoothly with iOS scale + rounded top corners under sheets
        SharedTransitionLayout(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val s = 1f - 0.075f * sheetDepth
                    scaleX = s
                    scaleY = s
                    transformOrigin = TransformOrigin(0.5f, 0f)
                    translationY = sheetDepth * statusBarTopPx * 0.65f
                    val cornerPx = 30.dp.toPx() * (sheetDepth * 3f).coerceAtMost(1f)
                    shape = RoundedCornerShape(cornerPx)
                    clip = sheetDepth > 0.001f
                }
                .background(c.canvas),
        ) {
            val sharedScope = this

            // Primary Tabbed Layer vs Shared-Element App Detail Page
            // Key strictly by stable `selectedAppKey` (NOT mutable ManagedAppInfo) so force-stop / refresh updates IN-PLACE!
            AnimatedContent(
                targetState = uiState.selectedAppKey,
                transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(200)) },
                label = "sharedAppDetail",
            ) { selectedKey ->
                val animScope = this
                val selectedApp = selectedKey?.let { k -> uiState.managedApps.firstOrNull { it.appKey == k } }
                if (selectedApp != null) {
                    AppDetailOverlay(
                        app = selectedApp,
                        customGroups = uiState.customGroups,
                        fromOverview = detailOpenedFromOverview,
                        isDaemonOnline = uiState.daemonStatus is DaemonStatus.Running,
                        isBusy = selectedApp.appKey in uiState.busyPackages,
                        sharedTransitionScope = sharedScope,
                        animatedVisibilityScope = animScope,
                        onClose = { store.closeAppDetail() },
                        onSelectGroup = { group ->
                            store.setAppGroup(selectedApp.packageName, selectedApp.displayLabel, group, selectedApp.userId)
                        },
                        onToggleCustomGroup = { groupId ->
                            store.toggleAppInCustomGroup(groupId, selectedApp.appKey)
                        },
                        onCreateCustomGroup = {
                            store.openCustomGroupEditor(null, preselectedAppKeys = setOf(selectedApp.appKey))
                        },
                        onForceStop = {
                            store.forceStopNow(selectedApp.packageName, selectedApp.displayLabel, selectedApp.userId)
                        },
                        onSoftTrim = {
                            store.softTrimNow(selectedApp.packageName, selectedApp.displayLabel, selectedApp.userId)
                        },
                        onUpdateRestriction = { restriction ->
                            store.setAppRestriction(selectedApp.packageName, restriction, selectedApp.userId)
                        },
                        onCopiedNotice = { store.notify(it) },
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // Main Edge-to-Edge Tabbed Content (screens pad for statusBars inside LazyColumn)
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
                                    capsuleAnchor = capsuleAnchor,
                                    capsuleHidden = capsuleHidden,
                                    onOpenStatusPopover = { store.openStatusPopover() },
                                    onRefresh = { store.refreshSnapshot() },
                                    onCycleTheme = { store.cycleThemePreference() },
                                    onOpenActivation = { store.openActivationSheet() },
                                    onReconnect = { store.reconnectManual() },
                                    onOpenActivitySheet = { store.openActivitySheet() },
                                    onNavigateAppsFilter = { store.navigateToAppsWithFilter(it) },
                                    onNavigateCustomGroup = { store.navigateToAppsWithCustomGroup(it) },
                                    onOpenCreateGroup = { store.openCustomGroupEditor(null) },
                                    onExecuteGroupAction = { name, action, targets ->
                                        store.executeGroupAction(name, action, targets)
                                    },
                                    onOpenAppDetail = { appKey ->
                                        detailOpenedFromOverview = true
                                        store.openAppDetail(appKey)
                                    },
                                    onQuickForceStop = { pkg, label, uid -> store.forceStopNow(pkg, label, uid) },
                                    onQuickSoftTrim = { pkg, label, uid -> store.softTrimNow(pkg, label, uid) },
                                    onStopSecondaryUser = { user -> store.stopSecondaryUser(user) },
                                    listState = overviewListState,
                                )
                                HushTab.APPS -> AppsScreen(
                                    uiState = uiState,
                                    sharedTransitionScope = sharedScope,
                                    animatedVisibilityScope = animScope,
                                    onRefresh = { store.refreshSnapshot() },
                                    onFilterChange = { store.setAppFilter(it) },
                                    onSelectCustomGroup = { store.selectCustomGroup(it) },
                                    onSortChange = { store.setAppSort(it) },
                                    onSearchChange = { store.setAppSearchQuery(it) },
                                    onSelectApp = { appKey ->
                                        detailOpenedFromOverview = false
                                        savedAppsScrollIndex = appsListState.firstVisibleItemIndex
                                        savedAppsScrollOffset = appsListState.firstVisibleItemScrollOffset
                                        store.openAppDetail(appKey)
                                    },
                                    onOpenBuiltInGroupEditor = { store.openBuiltInGroupEditor(it) },
                                    onOpenCustomGroupEditor = { store.openCustomGroupEditor(it) },
                                    onExecuteGroupAction = { name, action, targets ->
                                        store.executeGroupAction(name, action, targets)
                                    },
                                    onRestoreAllRestrictions = { store.restoreAllRestrictions() },
                                    listState = appsListState,
                                )
                                HushTab.POLICIES -> PoliciesScreen(
                                    uiState = uiState,
                                    onUpdatePolicy = { cfg, imm -> store.updatePolicy(cfg, imm) },
                                    onOpenActivitySheet = { store.openActivitySheet() },
                                    onRestoreAllRestrictions = { store.restoreAllRestrictions() },
                                    onOpenActivation = { store.openActivationSheet() },
                                    onStopDaemon = { store.stopDaemon() },
                                    onCheckUpdates = { store.checkForUpdates(silent = false) },
                                    onOpenUpdateDialog = { store.openUpdateDialog() },
                                    onOpenDisclaimer = { store.openDisclaimerDialog() },
                                    onOpenDiagnostics = { store.openDiagnosticsSheet() },
                                    listState = policiesListState,
                                )
                            }
                        }

                        // Subtle Status-Bar Gradient Veil so scrolled content never clashes with system clock/icons
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .height(statusBarTop + 14.dp)
                                .background(
                                    Brush.verticalGradient(
                                        0.0f to c.canvas.copy(alpha = 0.96f),
                                        0.72f to c.canvas.copy(alpha = 0.88f),
                                        1.0f to c.canvas.copy(alpha = 0f),
                                    ),
                                ),
                        )

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
        }

        // Figure 5 — Compact Apple Status Capsule Fluid Morph Popover
        AppleCapsuleMorph(
            visible = uiState.showStatusPopover,
            anchor = capsuleAnchor.bounds,
            onDismiss = { store.closeStatusPopover() },
            onCapsuleHiddenChange = { capsuleHidden = it },
            capsule = { StatusCapsule(status = uiState.daemonStatus, elevated = false) },
        ) {
            StatusCapsulePopover(
                status = uiState.daemonStatus,
                isRefreshing = uiState.isRefreshing,
                isReconnecting = uiState.isReconnecting,
                isGlobalActionBusy = uiState.isGlobalActionBusy,
                onRefresh = { store.refreshSnapshot() },
                onReconnect = { store.reconnectManual() },
                onOpenActivation = {
                    store.closeStatusPopover()
                    store.openActivationSheet()
                },
                onRequestStop = {
                    store.closeStatusPopover()
                    showStopConfirm = true
                },
            )
        }

        // Full-Height Beginner-Friendly Wireless Activation Bottom Sheet
        AppleRubberBandSheet(
            visible = uiState.showActivationSheet,
            onDismiss = { store.closeActivationSheet() },
            allowHalfDetent = false,
            containerColor = if (c.isDark) Color(0xFF111113) else c.canvas,
            onProgress = { activationProgress = it },
        ) {
            ActivationSheetContent(
                uiState = uiState,
                onClose = { store.closeActivationSheet() },
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
            allowHalfDetent = true,
            containerColor = c.surface,
            onProgress = { activityProgress = it },
        ) {
            ActivitySheetContent(
                uiState = uiState,
                onFilterChange = { store.setActivityFilter(it) },
                onClearLogs = { store.clearLogs() },
                onClose = { store.closeActivitySheet() },
            )
        }

        // Full-Height Diagnostic Logs Sheet (merged App + Daemon logs with copy & FileProvider share)
        AppleRubberBandSheet(
            visible = uiState.showDiagnosticsSheet,
            onDismiss = { store.closeDiagnosticsSheet() },
            allowHalfDetent = false,
            containerColor = c.surface,
            onProgress = { diagnosticsProgress = it },
        ) {
            DiagnosticsSheetContent(
                uiState = uiState,
                onRefresh = { store.refreshDiagnostics() },
                onClear = { store.clearDiagnostics() },
                onClose = { store.closeDiagnosticsSheet() },
                onNotice = { store.notify(it) },
            )
        }

        // Full-Height Batch Group Member & Custom Group Editor Sheet
        AppleRubberBandSheet(
            visible = uiState.groupEditorTarget != null,
            onDismiss = { store.closeGroupEditor() },
            allowHalfDetent = false,
            containerColor = c.surface,
            onProgress = { groupEditorProgress = it },
        ) {
            val target = uiState.groupEditorTarget
            if (target != null) {
                GroupEditorSheetContent(
                    target = target,
                    allApps = uiState.managedApps,
                    onClose = { store.closeGroupEditor() },
                    onSaveBuiltInMembers = { tier, keys -> store.saveBuiltInGroupMembers(tier, keys) },
                    onSaveCustomGroup = { group ->
                        store.saveCustomGroup(group, selectAfterSave = uiState.selectedAppKey == null)
                    },
                    onDeleteCustomGroup = { group -> store.deleteCustomGroup(group) },
                )
            }
        }

        // iOS-style Confirmation Overlay for Stop Daemon (triggered from Status Capsule Popover)
        AppleConfirmOverlay(
            visible = showStopConfirm,
            title = "停止守护进程？",
            message = "停止后将暂停自动清理与内存采样，下次可通过无线调试一键恢复。",
            confirmText = "停止",
            destructive = true,
            onDismiss = { showStopConfirm = false },
            onConfirm = {
                showStopConfirm = false
                store.stopDaemon()
            },
        )

        if (uiState.showUpdateDialog) {
            val ctx = LocalContext.current
            AppleUpdateDialog(
                state = uiState.updateState,
                onDismiss = { store.closeUpdateDialog() },
                onDownload = { store.startUpdateDownload(it) },
                onCancelDownload = { store.cancelUpdateDownload() },
                onInstall = { store.installDownloadedUpdate() },
                onOpenGitHub = { url -> openExternalUrl(ctx, url) },
            )
        }

        if (uiState.showDisclaimerDialog) {
            val ctx = LocalContext.current
            AppleDisclaimerDialog(
                onDismiss = { store.closeDisclaimerDialog() },
                onOpenAuthorGitHub = { openExternalUrl(ctx, AppUpdater.AUTHOR_GITHUB_URL) },
            )
        }

        // Dynamic-Island-inspired Floating Toast Pill at top (swipe up or tap to dismiss immediately)
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 10.dp, start = 24.dp, end = 24.dp),
        ) { data ->
            val accent = if (lastNoticeIsError) c.red else c.green
            val toastScope = rememberCoroutineScope()
            val dragY = remember(data) { Animatable(0f) }
            val dismissThresholdPx = with(LocalDensity.current) { 18.dp.toPx() }
            Surface(
                modifier = Modifier
                    .graphicsLayer {
                        val y = dragY.value.coerceAtMost(12f)
                        translationY = y
                        alpha = (1f - (-y / (dismissThresholdPx * 2.5f)).coerceIn(0f, 0.85f))
                    }
                    .pointerInput(data) {
                        detectTapGestures(onTap = { data.dismiss() })
                    }
                    .pointerInput(data) {
                        detectVerticalDragGestures(
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                val next = (dragY.value + dragAmount).coerceAtMost(10f)
                                toastScope.launch { dragY.snapTo(next) }
                            },
                            onDragEnd = {
                                if (dragY.value <= -dismissThresholdPx) {
                                    toastScope.launch {
                                        dragY.animateTo(-dismissThresholdPx * 3f, tween(120))
                                        data.dismiss()
                                    }
                                } else {
                                    toastScope.launch { dragY.animateTo(0f, HushSprings.fluid()) }
                                }
                            },
                            onDragCancel = {
                                toastScope.launch { dragY.animateTo(0f, HushSprings.fluid()) }
                            },
                        )
                    },
                shape = RoundedCornerShape(24.dp),
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
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Clean iOS-style custom confirmation overlay animated with fluid spring scale.
 */
@Composable
private fun AppleConfirmOverlay(
    visible: Boolean,
    title: String,
    message: String,
    confirmText: String,
    destructive: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val c = HushTheme.colors
    var present by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }

    LaunchedEffect(visible) {
        if (visible) {
            present = true
            progress.snapTo(0f)
            progress.animateTo(1f, HushSprings.fluid())
        } else if (present) {
            progress.animateTo(0f, tween(170))
            present = false
        }
    }
    if (!present) return
    BackHandler(enabled = visible) { onDismiss() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = progress.value.coerceIn(0f, 1f) }
            .background(Color.Black.copy(alpha = if (c.isDark) 0.56f else 0.32f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center,
    ) {
        AppleConfirmDialogCard(
            title = title,
            message = message,
            confirmText = confirmText,
            destructive = destructive,
            onDismiss = onDismiss,
            onConfirm = onConfirm,
            modifier = Modifier
                .graphicsLayer {
                    val scale = 1.12f - 0.12f * progress.value
                    scaleX = scale
                    scaleY = scale
                }
                .pointerInput(Unit) { detectTapGestures { } },
        )
    }
}

/**
 * Reusable iOS-style custom confirmation dialog hosted in a Compose Dialog window.
 */
@Composable
fun AppleConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    destructive: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        AppleConfirmDialogCard(
            title = title,
            message = message,
            confirmText = confirmText,
            destructive = destructive,
            onDismiss = onDismiss,
            onConfirm = onConfirm,
        )
    }
}

@Composable
private fun AppleConfirmDialogCard(
    title: String,
    message: String,
    confirmText: String,
    destructive: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = HushTheme.colors
    val shape = RoundedCornerShape(18.dp)
    Surface(
        modifier = modifier.width(288.dp),
        shape = shape,
        color = if (c.isDark) c.surfaceElevated else c.surface,
        border = if (c.isDark) BorderStroke(0.7.dp, c.glassBorder) else null,
        shadowElevation = if (c.isDark) 0.dp else 20.dp,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Column(
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(
                    text = title,
                    style = HushType.sectionTitle,
                    color = c.textPrimary,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = message,
                    style = HushType.caption,
                    color = c.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.6.dp)
                    .background(c.separator),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(bottomStart = 18.dp))
                        .applePressScale(pressedScale = 0.97f, cornerRadius = 0.dp, onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "取消",
                        style = HushType.body.copy(fontWeight = FontWeight.Medium),
                        color = c.blue,
                    )
                }
                Box(
                    modifier = Modifier
                        .width(0.6.dp)
                        .fillMaxHeight()
                        .background(c.separator),
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(bottomEnd = 18.dp))
                        .applePressScale(pressedScale = 0.97f, cornerRadius = 0.dp, onClick = onConfirm),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = confirmText,
                        style = HushType.body.copy(fontWeight = FontWeight.SemiBold),
                        color = if (destructive) c.red else c.blue,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppleUpdateDialog(
    state: UpdateState,
    onDismiss: () -> Unit,
    onDownload: (ReleaseInfo) -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: () -> Unit,
    onOpenGitHub: (String) -> Unit,
) {
    val c = HushTheme.colors
    val release = when (state) {
        is UpdateState.Available -> state.release
        is UpdateState.Downloading -> state.release
        is UpdateState.ReadyToInstall -> state.release
        is UpdateState.Error -> state.release
        else -> null
    }
    if (release == null) {
        onDismiss()
        return
    }
    val sizeText = if (release.apkSizeBytes > 0L) {
        formatMemoryMbFloat(release.apkSizeBytes / (1024f * 1024f))
    } else {
        null
    }

    Dialog(
        onDismissRequest = {
            if (state !is UpdateState.Downloading) onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            shape = RoundedCornerShape(24.dp),
            color = if (c.isDark) c.surfaceElevated else c.surface,
            border = if (c.isDark) BorderStroke(0.7.dp, c.glassBorder) else null,
            shadowElevation = if (c.isDark) 0.dp else 22.dp,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "发现新版本",
                            style = HushType.micro.copy(fontWeight = FontWeight.SemiBold),
                            color = c.blue,
                        )
                        Text(
                            text = "Hush v${release.versionName}",
                            style = HushType.sectionTitle,
                            color = c.textPrimary,
                        )
                    }
                    if (sizeText != null) {
                        Text(
                            text = sizeText,
                            style = HushType.micro.copy(fontWeight = FontWeight.SemiBold),
                            color = c.textSecondary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(100.dp))
                                .background(c.surfaceMuted)
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }

                if (release.notes.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (c.isDark) c.surface else c.canvas)
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                    ) {
                        Text(
                            text = release.notes,
                            style = HushType.caption,
                            color = c.textSecondary,
                        )
                    }
                }

                when (state) {
                    is UpdateState.Downloading -> {
                        val pct = if (state.progress >= 0f) (state.progress * 100f).roundToInt() else null
                        val dlMb = formatMemoryMbFloat(state.downloadedBytes / (1024f * 1024f))
                        val totalMb = if (state.totalBytes > 0L) {
                            formatMemoryMbFloat(state.totalBytes / (1024f * 1024f))
                        } else null
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = if (pct != null) "正在下载更新… $pct%" else "正在下载更新…",
                                    style = HushType.caption.copy(fontWeight = FontWeight.SemiBold),
                                    color = c.textPrimary,
                                )
                                Text(
                                    text = if (totalMb != null) "$dlMb / $totalMb" else dlMb,
                                    style = HushType.micro,
                                    color = c.textSecondary,
                                )
                            }
                            AppleMeterBar(
                                fraction = if (state.progress >= 0f) state.progress else 0.15f,
                                color = c.blue,
                                height = 6.dp,
                            )
                        }
                        ApplePillButton(
                            text = "取消下载",
                            onClick = onCancelDownload,
                            containerColor = c.surfaceMuted,
                            contentColor = c.textPrimary,
                            height = 44.dp,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    is UpdateState.ReadyToInstall -> {
                        ApplePillButton(
                            text = "立即安装 v${release.versionName}",
                            onClick = onInstall,
                            height = 46.dp,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "前往 GitHub 发布页",
                                style = HushType.caption.copy(fontWeight = FontWeight.SemiBold),
                                color = c.blue,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .applePressScale(cornerRadius = 8.dp) { onOpenGitHub(release.htmlUrl) }
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                            )
                            Text(
                                text = "稍后再说",
                                style = HushType.caption,
                                color = c.textSecondary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .applePressScale(cornerRadius = 8.dp, onClick = onDismiss)
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                            )
                        }
                    }

                    else -> {
                        if (state is UpdateState.Error) {
                            Text(
                                text = state.message,
                                style = HushType.micro,
                                color = c.red,
                            )
                        }
                        if (release.apkDownloadUrl != null) {
                            ApplePillButton(
                                text = "立即下载并安装",
                                onClick = { onDownload(release) },
                                height = 46.dp,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "前往 GitHub 下载",
                                style = HushType.caption.copy(fontWeight = FontWeight.SemiBold),
                                color = c.blue,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .applePressScale(cornerRadius = 8.dp) { onOpenGitHub(release.htmlUrl) }
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                            )
                            Text(
                                text = "稍后提醒",
                                style = HushType.caption,
                                color = c.textSecondary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .applePressScale(cornerRadius = 8.dp, onClick = onDismiss)
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppleDisclaimerDialog(
    onDismiss: () -> Unit,
    onOpenAuthorGitHub: () -> Unit,
) {
    val c = HushTheme.colors
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            shape = RoundedCornerShape(24.dp),
            color = if (c.isDark) c.surfaceElevated else c.surface,
            border = if (c.isDark) BorderStroke(0.7.dp, c.glassBorder) else null,
            shadowElevation = if (c.isDark) 0.dp else 22.dp,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = HushIcons.ShieldCheck,
                        contentDescription = null,
                        tint = c.blue,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = "免责声明与安全说明",
                        style = HushType.sectionTitle,
                        color = c.textPrimary,
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (c.isDark) c.surface else c.canvas)
                        .verticalScroll(rememberScrollState())
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "1. 风险自担与系统组件提醒\n「彻底停用应用（pm disable-user）」「强杀」「后台限制」与「第二系统停机」均调用 Android 系统级 Shell 接口。虽然 Hush 已内置核心进程硬编码护栏，但各厂商 ROM 组件命名与依赖关系存在差异——请勿停用或强杀你不了解用途的系统组件。因自行操作导致的系统功能异常、消息延迟或未保存数据丢失，均由使用者自行承担责任，作者不对任何直接或间接损失负责。",
                        style = HushType.caption,
                        color = c.textSecondary,
                    )
                    Text(
                        text = "2. 完全可逆与一键恢复\nHush 不修改系统分区、不依赖 Root，也不卸载任何应用。所有通过 Hush 写入的后台限制与停用状态均可在应用详情页或策略页「一键还原全部后台限制」中随时恢复。",
                        style = HushType.caption,
                        color = c.textSecondary,
                    )
                    Text(
                        text = "3. 隐私与开源声明\n本软件由 ${AppUpdater.AUTHOR_NAME} 开源维护，完全离线执行核心守护逻辑，仅在检查应用更新时访问 GitHub Releases 公开接口，不收集任何个人隐私或遥测数据。",
                        style = HushType.caption,
                        color = c.textSecondary,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ApplePillButton(
                        text = "作者 GitHub",
                        onClick = onOpenAuthorGitHub,
                        containerColor = c.blueSoft,
                        contentColor = c.blue,
                        height = 44.dp,
                        modifier = Modifier.weight(1f),
                    )
                    ApplePillButton(
                        text = "我已知晓",
                        onClick = onDismiss,
                        height = 44.dp,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
