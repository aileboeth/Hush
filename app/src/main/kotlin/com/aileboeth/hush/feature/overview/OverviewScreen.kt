package com.aileboeth.hush.feature.overview

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aileboeth.hush.contract.AppGroup
import com.aileboeth.hush.contract.AppTarget
import com.aileboeth.hush.contract.DaemonStatus
import com.aileboeth.hush.contract.ManagedAppInfo
import com.aileboeth.hush.contract.SecondaryUserInfo
import com.aileboeth.hush.design.AppIconAvatar
import com.aileboeth.hush.design.AppleGroupedCard
import com.aileboeth.hush.design.AppleInsetDivider
import com.aileboeth.hush.design.AppleLuminousTiltCard
import com.aileboeth.hush.design.AppleMeterBar
import com.aileboeth.hush.design.ApplePillButton
import com.aileboeth.hush.design.AppleScrubMemoryChart
import com.aileboeth.hush.design.BreathingDot
import com.aileboeth.hush.design.HushIcons
import com.aileboeth.hush.design.HushTheme
import com.aileboeth.hush.design.HushType
import com.aileboeth.hush.design.MorphAnchor
import com.aileboeth.hush.design.OdometerText
import com.aileboeth.hush.design.applePressScale
import com.aileboeth.hush.design.formatClockShort
import com.aileboeth.hush.design.formatMemoryKb
import com.aileboeth.hush.design.formatMemoryMbFloat
import com.aileboeth.hush.design.StaggerClock
import com.aileboeth.hush.design.morphAnchor
import com.aileboeth.hush.design.staggeredCascade
import com.aileboeth.hush.design.tierAccentColor
import com.aileboeth.hush.state.AppFilterMode
import com.aileboeth.hush.state.HushUiState
import java.util.Calendar
import kotlin.math.roundToInt

/**
 * Reusable top-right daemon status capsule shared pixel-for-pixel between [OverviewScreen]
 * and the fluid-morphing status popover in `HushRoot`.
 */
@Composable
fun StatusCapsule(
    status: DaemonStatus,
    elevated: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val c = HushTheme.colors
    val capsuleShape = RoundedCornerShape(100.dp)
    val dotColor = when (status) {
        is DaemonStatus.Running -> c.green
        is DaemonStatus.Connecting -> c.orange
        is DaemonStatus.Error -> c.red
        is DaemonStatus.Disconnected -> c.blue
    }
    val labelText = when (status) {
        is DaemonStatus.Running -> "守护运行中"
        is DaemonStatus.Connecting -> "连接中"
        is DaemonStatus.Error -> "连接异常"
        is DaemonStatus.Disconnected -> "无线激活"
    }
    val isPulsing = status is DaemonStatus.Running || status is DaemonStatus.Connecting

    Surface(
        modifier = modifier,
        shape = capsuleShape,
        color = if (c.isDark) c.surfaceElevated else c.surface,
        border = BorderStroke(0.7.dp, c.glassBorder),
        shadowElevation = if (elevated && !c.isDark) 2.dp else 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 13.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            BreathingDot(
                color = dotColor,
                isPulsing = isPulsing,
                size = 7.dp,
            )
            Text(
                text = labelText,
                style = HushType.caption.copy(fontWeight = FontWeight.SemiBold),
                color = c.textPrimary,
                maxLines = 1,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun OverviewScreen(
    uiState: HushUiState,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    capsuleAnchor: MorphAnchor,
    capsuleHidden: Boolean,
    onRefresh: () -> Unit,
    onCycleTheme: () -> Unit,
    onOpenStatusPopover: () -> Unit,
    onOpenActivation: () -> Unit,
    onReconnect: () -> Unit,
    onOpenActivitySheet: () -> Unit,
    onNavigateAppsFilter: (AppFilterMode) -> Unit,
    onNavigateCustomGroup: (String) -> Unit,
    onOpenCreateGroup: () -> Unit,
    onExecuteGroupAction: (String, AppGroup, List<AppTarget>) -> Unit,
    onOpenAppDetail: (String) -> Unit,
    onQuickForceStop: (String, String, Int) -> Unit,
    onQuickSoftTrim: (String, String, Int) -> Unit,
    onStopSecondaryUser: (SecondaryUserInfo) -> Unit,
    listState: LazyListState = rememberLazyListState(),
    modifier: Modifier = Modifier,
) {
    val c = HushTheme.colors
    val runningStatus = uiState.daemonStatus as? DaemonStatus.Running
    val isOnline = runningStatus != null
    val mem = uiState.memoryOverview
    val stagger = remember { StaggerClock(windowMs = if (listState.firstVisibleItemIndex == 0) 900L else 0L) }
    val refreshState = rememberPullToRefreshState()
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    // Scrubbing state for the interactive Apple Health–style memory line chart
    var scrubbedIndex by remember { mutableStateOf<Int?>(null) }
    val samples = uiState.memoryHistory
    val activeSample = scrubbedIndex?.let { samples.getOrNull(it) } ?: samples.lastOrNull()
    val todayLine = remember { todayChineseDateLine() }
    val fullSecondaryUsers = uiState.fullSecondaryUsers

    PullToRefreshBox(
        isRefreshing = uiState.isRefreshing,
        onRefresh = onRefresh,
        state = refreshState,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = refreshState,
                isRefreshing = uiState.isRefreshing,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding(),
            )
        },
        modifier = modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = statusTop + 10.dp,
                bottom = 120.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // 1. Apple Health–style Date Eyebrow + Large Title + Fluid Morph Status Capsule + Theme Circle
            item(key = "header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            text = todayLine,
                            style = HushType.caption.copy(fontWeight = FontWeight.SemiBold),
                            color = c.textSecondary,
                        )
                        Text(
                            text = "Hush",
                            style = HushType.largeTitle,
                            color = c.textPrimary,
                        )
                    }

                    Row(
                        modifier = Modifier.padding(bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // Morph anchor source for AppleCapsuleMorph compact status popover
                        Box(
                            modifier = Modifier
                                .morphAnchor(capsuleAnchor)
                                .graphicsLayer { alpha = if (capsuleHidden) 0f else 1f }
                                .applePressScale(
                                    enabled = !capsuleHidden,
                                    cornerRadius = 100.dp,
                                    onClick = onOpenStatusPopover,
                                ),
                        ) {
                            StatusCapsule(
                                status = uiState.daemonStatus,
                                elevated = true,
                            )
                        }

                        // Subtle Apple monochrome theme circle toggle
                        Surface(
                            modifier = Modifier
                                .size(34.dp)
                                .applePressScale(cornerRadius = 100.dp, onClick = onCycleTheme),
                            shape = CircleShape,
                            color = if (c.isDark) c.surfaceElevated else c.surface,
                            border = BorderStroke(0.7.dp, c.glassBorder),
                            shadowElevation = if (c.isDark) 0.dp else 2.dp,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = HushIcons.SunMoon,
                                    contentDescription = "切换浅深色",
                                    tint = c.textSecondary,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }

            // 2. Signature Hero Card: Luminous Tilt Card + Mechanical Odometer + Scrubbing Chart + Slim RAM/Swap Bars
            item(key = "hero_card") {
                AppleLuminousTiltCard(
                    isActive = isOnline,
                    modifier = Modifier.staggeredCascade(0, stagger),
                ) {
                    // Top caption & right ZRAM pill badge
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (scrubbedIndex != null && activeSample != null) {
                                "可用内存 · ${formatClockShort(activeSample.timestampMs)}"
                            } else {
                                "可用物理内存"
                            },
                            style = HushType.caption,
                            color = c.textSecondary,
                        )

                        if (isOnline && (activeSample != null || mem.memTotalKb > 0L)) {
                            val zramText = if (activeSample != null) {
                                formatMemoryMbFloat(activeSample.swapUsedMb)
                            } else {
                                formatMemoryKb(mem.swapUsedKb)
                            }
                            Text(
                                text = "ZRAM $zramText",
                                style = HushType.micro.copy(fontWeight = FontWeight.SemiBold),
                                color = c.blue,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(100.dp))
                                    .background(c.blueSoft)
                                    .padding(horizontal = 9.dp, vertical = 4.dp),
                            )
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    // Rolling 10-face Mechanical Odometer Hero Counter
                    val heroDisplay = when {
                        !isOnline -> "未连接"
                        activeSample != null -> formatMemoryMbFloat(activeSample.availableMb)
                        mem.memTotalKb > 0L -> formatMemoryKb(mem.memAvailableKb)
                        else -> "—"
                    }
                    OdometerText(
                        text = heroDisplay,
                        style = HushType.heroNumber,
                        color = c.textPrimary,
                    )

                    if (isOnline && mem.memTotalKb > 0L) {
                        val subtitle = if (samples.size >= 2) {
                            "已用 ${formatMemoryKb(mem.memUsedKb)} / 总计 ${formatMemoryKb(mem.memTotalKb)} · 左右滑动曲线查看节点"
                        } else {
                            "已用 ${formatMemoryKb(mem.memUsedKb)} / 总计 ${formatMemoryKb(mem.memTotalKb)}"
                        }
                        Text(
                            text = subtitle,
                            style = HushType.micro,
                            color = c.textTertiary,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    } else if (!isOnline) {
                        Text(
                            text = "本机无线调试或电脑 USB 激活后即可接管后台与内存，零 Root（保持开发者选项开启以防断连）",
                            style = HushType.caption,
                            color = c.textSecondary,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    if (isOnline && mem.memTotalKb > 0L) {
                        if (samples.size >= 2) {
                            AppleScrubMemoryChart(
                                samples = samples,
                                selectedIndex = scrubbedIndex,
                                onSelectIndex = { scrubbedIndex = it },
                                chartHeight = 86.dp,
                            )
                            Spacer(Modifier.height(12.dp))
                            // Compact dual slim RAM & ZRAM gauges right below the curve
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                CompactMemoryMeter(
                                    label = "物理内存",
                                    percent = (mem.memUsedFraction * 100f).roundToInt(),
                                    fraction = mem.memUsedFraction,
                                    color = c.blue,
                                    modifier = Modifier.weight(1f),
                                )
                                if (mem.swapTotalKb > 0L) {
                                    CompactMemoryMeter(
                                        label = "ZRAM",
                                        percent = (mem.swapUsedFraction * 100f).roundToInt(),
                                        fraction = mem.swapUsedFraction,
                                        color = c.indigo,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        } else {
                            // Honest cold-start state (< 2 real samples): full slim RAM/Swap bars + live sampling hint
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                StackedMemoryMeter(
                                    label = "物理内存",
                                    detail = "${formatMemoryKb(mem.memUsedKb)} / ${formatMemoryKb(mem.memTotalKb)}",
                                    fraction = mem.memUsedFraction,
                                    color = c.blue,
                                )
                                if (mem.swapTotalKb > 0L) {
                                    StackedMemoryMeter(
                                        label = "ZRAM / Swap",
                                        detail = "${formatMemoryKb(mem.swapUsedKb)} / ${formatMemoryKb(mem.swapTotalKb)}",
                                        fraction = mem.swapUsedFraction,
                                        color = c.indigo,
                                    )
                                }
                                Row(
                                    modifier = Modifier.padding(top = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    BreathingDot(color = c.blue, isPulsing = true, size = 5.5.dp)
                                    Text(
                                        text = "实时采样中，曲线将随运行自动绘出",
                                        style = HushType.micro,
                                        color = c.textTertiary,
                                    )
                                }
                            }
                        }
                    } else if (!isOnline) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            ApplePillButton(
                                text = if (uiState.hasPairedBefore) "一键启动守护" else "开启无线激活",
                                onClick = onOpenActivation,
                                modifier = Modifier.weight(1f),
                                height = 44.dp,
                            )
                            ApplePillButton(
                                text = if (uiState.isReconnecting) "重连中…" else "重连",
                                onClick = onReconnect,
                                enabled = !uiState.isReconnecting,
                                containerColor = c.surfaceMuted,
                                contentColor = c.textPrimary,
                                height = 44.dp,
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    AppleInsetDivider(startIndent = 0.dp)
                    Spacer(Modifier.height(14.dp))

                    // Minimalist 3-column summary row inside Hero Card
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HeroInlineStat(
                            label = "今日释放",
                            value = if (runningStatus != null) formatMemoryKb(runningStatus.todayFreedKb) else "—",
                            onClick = onOpenActivitySheet,
                            modifier = Modifier.weight(1f),
                        )
                        HeroInlineStat(
                            label = "活跃应用",
                            value = "${uiState.runningAppCount} 个",
                            onClick = { onNavigateAppsFilter(AppFilterMode.RUNNING) },
                            modifier = Modifier.weight(1f),
                        )
                        HeroInlineStat(
                            label = "清理记录",
                            value = "${uiState.cleanupLogs.size} 条 ›",
                            accentBlue = true,
                            onClick = onOpenActivitySheet,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // 3. Minimalist 3-Tier Quick Filter Strip
            item(key = "tier_strip") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .staggeredCascade(1, stagger),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MinimalTierTile(
                        title = "强杀",
                        count = uiState.tierCounts[AppGroup.FORCE_STOP] ?: 0,
                        dotColor = c.red,
                        onClick = { onNavigateAppsFilter(AppFilterMode.FORCE_STOP) },
                        modifier = Modifier.weight(1f),
                    )
                    MinimalTierTile(
                        title = "保消息瘦身",
                        count = uiState.tierCounts[AppGroup.SOFT_TRIM] ?: 0,
                        dotColor = c.blue,
                        onClick = { onNavigateAppsFilter(AppFilterMode.SOFT_TRIM) },
                        modifier = Modifier.weight(1f),
                    )
                    MinimalTierTile(
                        title = "常驻保护",
                        count = uiState.tierCounts[AppGroup.PROTECTED] ?: 0,
                        dotColor = c.indigo,
                        onClick = { onNavigateAppsFilter(AppFilterMode.PROTECTED) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // 3.2 Quick App Groups Card (one-tap batch force-stop / soft-trim for custom & built-in groups)
            val forceStopTierApps = uiState.appsInTier(AppGroup.FORCE_STOP)
            val softTrimTierApps = uiState.appsInTier(AppGroup.SOFT_TRIM)
            val showQuickGroups = uiState.customGroups.isNotEmpty() || forceStopTierApps.isNotEmpty() || softTrimTierApps.isNotEmpty()
            if (showQuickGroups) {
                item(key = "quick_groups") {
                    Column(
                        modifier = Modifier.staggeredCascade(2, stagger),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("快捷分组", style = HushType.sectionTitle, color = c.textPrimary)
                            Text(
                                text = "+ 新建组",
                                style = HushType.caption.copy(fontWeight = FontWeight.SemiBold),
                                color = c.blue,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .applePressScale(cornerRadius = 8.dp, onClick = onOpenCreateGroup)
                                    .padding(horizontal = 6.dp, vertical = 3.dp),
                            )
                        }

                        AppleGroupedCard {
                            val quickRows = buildList {
                                uiState.customGroups.forEach { cg ->
                                    add(
                                        QuickGroupEntry(
                                            title = cg.name,
                                            action = cg.action,
                                            members = uiState.appsInCustomGroup(cg),
                                            onOpen = { onNavigateCustomGroup(cg.id) },
                                        ),
                                    )
                                }
                                if (uiState.customGroups.isEmpty()) {
                                    if (forceStopTierApps.isNotEmpty()) {
                                        add(
                                            QuickGroupEntry(
                                                title = "强杀组",
                                                action = AppGroup.FORCE_STOP,
                                                members = forceStopTierApps,
                                                onOpen = { onNavigateAppsFilter(AppFilterMode.FORCE_STOP) },
                                            ),
                                        )
                                    }
                                    if (softTrimTierApps.isNotEmpty()) {
                                        add(
                                            QuickGroupEntry(
                                                title = "保消息瘦身组",
                                                action = AppGroup.SOFT_TRIM,
                                                members = softTrimTierApps,
                                                onOpen = { onNavigateAppsFilter(AppFilterMode.SOFT_TRIM) },
                                            ),
                                        )
                                    }
                                }
                            }
                            quickRows.forEachIndexed { idx, entry ->
                                QuickGroupRow(
                                    entry = entry,
                                    isOnline = isOnline,
                                    isBusy = uiState.isGlobalActionBusy,
                                    onExecute = {
                                        val targets = entry.members
                                            .filter { !it.isSystemProtected }
                                            .map { AppTarget(it.packageName, it.userId) }
                                        onExecuteGroupAction(entry.title, entry.action, targets)
                                    },
                                )
                                if (idx < quickRows.lastIndex) {
                                    AppleInsetDivider(startIndent = 44.dp)
                                }
                            }
                        }
                    }
                }
            }

            // 3.5 Multi-User Secondary Space Card (strictly real full secondary systems — NEVER Xspace/clone!)
            if (fullSecondaryUsers.isNotEmpty()) {
                item(key = "secondary_users") {
                    val runningCount = fullSecondaryUsers.count { it.running }
                    Column(
                        modifier = Modifier.staggeredCascade(3, stagger),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("多用户空间", style = HushType.sectionTitle, color = c.textPrimary)
                            Text(
                                text = if (runningCount > 0) "$runningCount 个运行中" else "全部已休眠",
                                style = HushType.caption.copy(fontWeight = FontWeight.SemiBold),
                                color = if (runningCount > 0) c.orange else c.textTertiary,
                            )
                        }

                        AppleGroupedCard {
                            fullSecondaryUsers.forEachIndexed { idx, user ->
                                SecondaryUserRow(
                                    user = user,
                                    isOnline = isOnline,
                                    isBusy = user.userId in uiState.busyUserIds,
                                    onStopUser = { onStopSecondaryUser(user) },
                                )
                                if (idx < fullSecondaryUsers.lastIndex) {
                                    AppleInsetDivider(startIndent = 68.dp)
                                }
                            }
                        }
                    }
                }
            }

            // 4. Clean iOS Grouped Card: Active Focus Apps (safe tier-aware actions — never red kill on UNSET!)
            val focusApps = uiState.managedApps
                .filter { it.totalPssKb > 0L || it.running }
                .sortedByDescending { it.totalPssKb }
                .take(4)

            if (focusApps.isNotEmpty()) {
                item(key = "focus_apps") {
                    Column(
                        modifier = Modifier.staggeredCascade(if (fullSecondaryUsers.isNotEmpty()) 4 else 3, stagger),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("后台占用焦点", style = HushType.sectionTitle, color = c.textPrimary)
                            Text(
                                text = "全部应用",
                                style = HushType.caption.copy(fontWeight = FontWeight.SemiBold),
                                color = c.blue,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .applePressScale(cornerRadius = 8.dp) {
                                        onNavigateAppsFilter(AppFilterMode.ALL)
                                    }
                                    .padding(horizontal = 6.dp, vertical = 3.dp),
                            )
                        }

                        AppleGroupedCard {
                            focusApps.forEachIndexed { idx, app ->
                                FocusAppRow(
                                    app = app,
                                    isOnline = isOnline,
                                    isBusy = app.appKey in uiState.busyPackages,
                                    sharedTransitionScope = sharedTransitionScope,
                                    animatedVisibilityScope = animatedVisibilityScope,
                                    onOpenDetail = { onOpenAppDetail(app.appKey) },
                                    onQuickForceStop = { onQuickForceStop(app.packageName, app.displayLabel, app.userId) },
                                    onQuickSoftTrim = { onQuickSoftTrim(app.packageName, app.displayLabel, app.userId) },
                                )
                                if (idx < focusApps.lastIndex) {
                                    AppleInsetDivider(startIndent = 68.dp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun todayChineseDateLine(): String {
    val cal = Calendar.getInstance()
    val weekdays = arrayOf("星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六")
    val dayOfWeek = weekdays[(cal.get(Calendar.DAY_OF_WEEK) - 1).coerceIn(0, 6)]
    return "${cal.get(Calendar.MONTH) + 1}月${cal.get(Calendar.DAY_OF_MONTH)}日 $dayOfWeek"
}

@Composable
private fun CompactMemoryMeter(
    label: String,
    percent: Int,
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val c = HushTheme.colors
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, style = HushType.micro, color = c.textSecondary)
            Text(
                text = "$percent%",
                style = HushType.micro.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                color = c.textPrimary,
            )
        }
        AppleMeterBar(fraction = fraction, color = color, height = 4.5.dp)
    }
}

@Composable
private fun StackedMemoryMeter(
    label: String,
    detail: String,
    fraction: Float,
    color: Color,
) {
    val c = HushTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = HushType.caption.copy(fontWeight = FontWeight.SemiBold),
                color = c.textPrimary,
            )
            Text(
                text = detail,
                style = HushType.micro.copy(fontFeatureSettings = "tnum"),
                color = c.textSecondary,
            )
        }
        AppleMeterBar(fraction = fraction, color = color, height = 5.5.dp)
    }
}

@Composable
private fun HeroInlineStat(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accentBlue: Boolean = false,
) {
    val c = HushTheme.colors
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .applePressScale(cornerRadius = 12.dp, onClick = onClick)
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(text = label, style = HushType.micro, color = c.textTertiary)
        Text(
            text = value,
            style = HushType.compactNumber.copy(fontSize = 15.sp),
            color = if (accentBlue) c.blue else c.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MinimalTierTile(
    title: String,
    count: Int,
    dotColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = HushTheme.colors
    Surface(
        modifier = modifier
            .applePressScale(cornerRadius = 20.dp, onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = c.surface,
        border = if (c.isDark) BorderStroke(0.6.dp, c.glassBorder) else null,
        shadowElevation = if (c.isDark) 0.dp else 1.5.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(dotColor))
                Text(
                    text = title,
                    style = HushType.micro,
                    color = c.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            OdometerText(
                text = count.toString(),
                style = HushType.metricNumber.copy(fontSize = 22.sp),
                color = c.textPrimary,
            )
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun FocusAppRow(
    app: ManagedAppInfo,
    isOnline: Boolean,
    isBusy: Boolean,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onOpenDetail: () -> Unit,
    onQuickForceStop: () -> Unit,
    onQuickSoftTrim: () -> Unit,
) {
    val c = HushTheme.colors

    with(sharedTransitionScope) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .sharedBounds(
                    sharedContentState = rememberSharedContentState(key = "app_card_overview_${app.appKey}"),
                    animatedVisibilityScope = animatedVisibilityScope,
                    boundsTransform = { _, _ -> spring(dampingRatio = 0.80f, stiffness = 360f) },
                )
                .applePressScale(pressedScale = 0.985f, cornerRadius = 16.dp, onClick = onOpenDetail)
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppIconAvatar(
                packageName = app.packageName,
                label = app.label,
                size = 40.dp,
                modifier = Modifier.sharedElement(
                    state = rememberSharedContentState(key = "app_icon_overview_${app.appKey}"),
                    animatedVisibilityScope = animatedVisibilityScope,
                ),
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = app.label,
                        style = HushType.cardTitle,
                        color = c.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (app.isDualSpace) {
                        Text(
                            text = "双开",
                            style = HushType.micro.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                            color = c.blue,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(c.blueSoft)
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
                val subtitle = buildString {
                    append(formatMemoryKb(app.totalPssKb))
                    if (app.swapPssKb > 0L) append(" · Swap ${formatMemoryKb(app.swapPssKb)}")
                    if (app.isFrozen) append(" · 已冻结")
                    else if (app.isPlayingAudio) append(" · 音频中")
                }
                Text(
                    text = subtitle,
                    style = HushType.micro,
                    color = c.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Tier-safe trailing control: NEVER put a red kill button on UNSET / messaging apps!
            when {
                isOnline && !app.isSystemProtected && app.group == AppGroup.FORCE_STOP -> {
                    FocusActionPill(
                        label = "强杀",
                        bgColor = c.redSoft,
                        fgColor = c.red,
                        isBusy = isBusy,
                        onClick = onQuickForceStop,
                    )
                }
                isOnline && !app.isSystemProtected && app.group == AppGroup.SOFT_TRIM -> {
                    FocusActionPill(
                        label = "瘦身",
                        bgColor = c.blueSoft,
                        fgColor = c.blue,
                        isBusy = isBusy,
                        onClick = onQuickSoftTrim,
                    )
                }
                !app.isSystemProtected && app.group == AppGroup.UNSET -> {
                    FocusActionPill(
                        label = "配置",
                        bgColor = c.neutralSoft,
                        fgColor = c.textSecondary,
                        isBusy = false,
                        onClick = onOpenDetail,
                    )
                }
                else -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        if (app.group == AppGroup.PROTECTED) {
                            Box(Modifier.size(6.dp).clip(CircleShape).background(c.indigo))
                            Text(
                                text = "保护",
                                style = HushType.micro.copy(fontWeight = FontWeight.SemiBold),
                                color = c.indigo,
                            )
                        }
                        Icon(
                            imageVector = HushIcons.ChevronRight,
                            contentDescription = null,
                            tint = c.textTertiary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SecondaryUserRow(
    user: SecondaryUserInfo,
    isOnline: Boolean,
    isBusy: Boolean,
    onStopUser: () -> Unit,
) {
    val c = HushTheme.colors
    val kindBadge = when {
        user.isCloneProfile -> "双开 · #${user.userId}"
        user.isManagedProfile -> "工作资料 · #${user.userId}"
        else -> "第二系统 · #${user.userId}"
    }
    val accent = when {
        !user.running -> c.textTertiary
        user.isFullSecondarySystem -> c.indigo
        else -> c.blue
    }
    val softBg = when {
        !user.running -> c.neutralSoft
        user.isFullSecondarySystem -> c.indigoSoft
        else -> c.blueSoft
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(softBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = HushIcons.Layers,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(19.dp),
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = user.name,
                    style = HushType.cardTitle,
                    color = c.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = kindBadge,
                    style = HushType.micro.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Medium),
                    color = accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(softBg)
                        .padding(horizontal = 6.dp, vertical = 1.5.dp),
                )
            }
            val subtitle = if (user.running) {
                buildString {
                    if (user.processCount > 0) append("${user.processCount} 进程")
                    if (user.totalPssKb > 0L) {
                        if (isNotEmpty()) append(" · ")
                        append("占用 ${formatMemoryKb(user.totalPssKb)}")
                    }
                    if (isEmpty()) append("后台运行中")
                }
            } else {
                "已彻底休眠 · 0 内存占用"
            }
            Text(
                text = subtitle,
                style = HushType.micro,
                color = if (user.running) c.textSecondary else c.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        when {
            isOnline && user.canStop -> {
                FocusActionPill(
                    label = "立即停机",
                    bgColor = c.redSoft,
                    fgColor = c.red,
                    isBusy = isBusy,
                    onClick = onStopUser,
                )
            }
            user.isCurrent -> {
                Text(
                    text = "当前空间",
                    style = HushType.micro.copy(fontWeight = FontWeight.SemiBold),
                    color = c.blue,
                    modifier = Modifier
                        .clip(RoundedCornerShape(100.dp))
                        .background(c.blueSoft)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            else -> {
                Text(
                    text = "已休眠",
                    style = HushType.micro.copy(fontWeight = FontWeight.SemiBold),
                    color = c.textTertiary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(100.dp))
                        .background(c.neutralSoft)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

private data class QuickGroupEntry(
    val title: String,
    val action: AppGroup,
    val members: List<ManagedAppInfo>,
    val onOpen: () -> Unit,
)

@Composable
private fun QuickGroupRow(
    entry: QuickGroupEntry,
    isOnline: Boolean,
    isBusy: Boolean,
    onExecute: () -> Unit,
) {
    val c = HushTheme.colors
    val accent = tierAccentColor(entry.action)
    val running = entry.members.filter { it.running && !it.isSystemProtected }
    val pssKb = running.sumOf { it.totalPssKb }
    val subtitle = when {
        entry.members.isEmpty() -> "暂无成员 · 点击添加"
        running.isEmpty() -> "共 ${entry.members.size} 个 · 均未运行"
        pssKb > 0L -> "${running.size} / ${entry.members.size} 运行中 · ${formatMemoryKb(pssKb)}"
        else -> "${running.size} / ${entry.members.size} 运行中"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .applePressScale(pressedScale = 0.985f, cornerRadius = 16.dp, onClick = entry.onOpen)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(accent),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = entry.title,
                style = HushType.cardTitle,
                color = c.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = HushType.micro,
                color = c.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            isOnline && running.isNotEmpty() && entry.action == AppGroup.FORCE_STOP -> {
                FocusActionPill(
                    label = "强杀 (${running.size})",
                    bgColor = c.redSoft,
                    fgColor = c.red,
                    isBusy = isBusy,
                    onClick = onExecute,
                )
            }
            isOnline && running.isNotEmpty() && entry.action == AppGroup.SOFT_TRIM -> {
                FocusActionPill(
                    label = "瘦身 (${running.size})",
                    bgColor = c.blueSoft,
                    fgColor = c.blue,
                    isBusy = isBusy,
                    onClick = onExecute,
                )
            }
            else -> {
                Icon(
                    imageVector = HushIcons.ChevronRight,
                    contentDescription = null,
                    tint = c.textTertiary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun FocusActionPill(
    label: String,
    bgColor: Color,
    fgColor: Color,
    isBusy: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(100.dp))
            .background(bgColor)
            .applePressScale(enabled = !isBusy, cornerRadius = 100.dp, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (isBusy) {
            CircularProgressIndicator(
                modifier = Modifier.size(13.dp),
                strokeWidth = 1.8.dp,
                color = fgColor,
            )
        } else {
            Text(
                text = label,
                style = HushType.micro.copy(fontWeight = FontWeight.SemiBold),
                color = fgColor,
            )
        }
    }
}
