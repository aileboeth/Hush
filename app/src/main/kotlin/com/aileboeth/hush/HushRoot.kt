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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.aileboeth.hush.design.AppleRubberBandSheet
import com.aileboeth.hush.design.HushIcons
import com.aileboeth.hush.design.HushSprings
import com.aileboeth.hush.design.HushTheme
import com.aileboeth.hush.design.HushType
import com.aileboeth.hush.design.MorphAnchor
import com.aileboeth.hush.design.applePressScale
import com.aileboeth.hush.feature.activation.ActivationSheetContent
import com.aileboeth.hush.feature.activation.StatusCapsulePopover
import com.aileboeth.hush.feature.activity.ActivitySheetContent
import com.aileboeth.hush.feature.apps.AppDetailOverlay
import com.aileboeth.hush.feature.apps.AppsScreen
import com.aileboeth.hush.feature.overview.OverviewScreen
import com.aileboeth.hush.feature.overview.StatusCapsule
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

    val capsuleAnchor = remember { MorphAnchor() }
    var capsuleHidden by remember { mutableStateOf(false) }
    var activationProgress by remember { mutableFloatStateOf(0f) }
    var activityProgress by remember { mutableFloatStateOf(0f) }
    var showStopConfirm by remember { mutableStateOf(false) }

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
    BackHandler(enabled = !showStopConfirm && uiState.showActivitySheet) {
        store.closeActivitySheet()
    }
    BackHandler(enabled = !showStopConfirm && !uiState.showActivitySheet && uiState.showActivationSheet) {
        store.closeActivationSheet()
    }
    BackHandler(
        enabled = !showStopConfirm &&
            !uiState.showActivitySheet &&
            !uiState.showActivationSheet &&
            uiState.showStatusPopover,
    ) {
        store.closeStatusPopover()
    }
    BackHandler(
        enabled = !showStopConfirm &&
            !uiState.showActivitySheet &&
            !uiState.showActivationSheet &&
            !uiState.showStatusPopover &&
            uiState.selectedAppPackage != null,
    ) {
        store.closeAppDetail()
    }
    BackHandler(
        enabled = !showStopConfirm &&
            !uiState.showActivitySheet &&
            !uiState.showActivationSheet &&
            !uiState.showStatusPopover &&
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

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val statusBarTopPx = with(LocalDensity.current) { statusBarTop.toPx() }
    val sheetDepth = maxOf(activationProgress, activityProgress).coerceIn(0f, 1f)
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
                                    onRestoreAllRestrictions = { store.restoreAllRestrictions() },
                                )
                                HushTab.POLICIES -> PoliciesScreen(
                                    uiState = uiState,
                                    onUpdatePolicy = { cfg, imm -> store.updatePolicy(cfg, imm) },
                                    onOpenActivitySheet = { store.openActivitySheet() },
                                    onRestoreAllRestrictions = { store.restoreAllRestrictions() },
                                    onOpenActivation = { store.openActivationSheet() },
                                    onStopDaemon = { store.stopDaemon() },
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
