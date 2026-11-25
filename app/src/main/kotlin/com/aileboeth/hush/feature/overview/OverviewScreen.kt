package com.aileboeth.hush.feature.overview

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aileboeth.hush.contract.AppGroup
import com.aileboeth.hush.contract.DaemonStatus
import com.aileboeth.hush.contract.ManagedAppInfo
import com.aileboeth.hush.design.AppIconAvatar
import com.aileboeth.hush.design.AppleGroupedCard
import com.aileboeth.hush.design.AppleInsetDivider
import com.aileboeth.hush.design.AppleLuminousTiltCard
import com.aileboeth.hush.design.ApplePillButton
import com.aileboeth.hush.design.AppleScrubMemoryChart
import com.aileboeth.hush.design.BreathingDot
import com.aileboeth.hush.design.HushIcons
import com.aileboeth.hush.design.HushTheme
import com.aileboeth.hush.design.HushType
import com.aileboeth.hush.design.OdometerText
import com.aileboeth.hush.design.applePressScale
import com.aileboeth.hush.design.formatClockShort
import com.aileboeth.hush.design.formatMemoryKb
import com.aileboeth.hush.design.formatMemoryMbFloat
import com.aileboeth.hush.design.staggeredCascade
import com.aileboeth.hush.design.tierAccentColor
import com.aileboeth.hush.state.AppFilterMode
import com.aileboeth.hush.state.HushUiState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun OverviewScreen(
    uiState: HushUiState,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onRefresh: () -> Unit,
    onCycleTheme: () -> Unit,
    onOpenActivation: () -> Unit,
    onReconnect: () -> Unit,
    onOpenActivitySheet: () -> Unit,
    onNavigateAppsFilter: (AppFilterMode) -> Unit,
    onOpenAppDetail: (String) -> Unit,
    onQuickForceStop: (String, String) -> Unit,
    onQuickSoftTrim: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = HushTheme.colors
    val runningStatus = uiState.daemonStatus as? DaemonStatus.Running
    val isOnline = runningStatus != null

    // Scrubbing state for the interactive Apple Health–style memory line chart
    var scrubbedIndex by remember { mutableStateOf<Int?>(null) }
    val samples = uiState.memoryHistory
    val activeSample = scrubbedIndex?.let { samples.getOrNull(it) } ?: samples.lastOrNull()

    PullToRefreshBox(
        isRefreshing = uiState.isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // 1. Minimalist Apple Large Title + Fluid Morph Status Capsule
            item(key = "header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Hush",
                        style = HushType.largeTitle,
                        color = c.textPrimary,
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // Fluid Morph Source Capsule -> seamlessly morphs into Activation & Control Panel!
                        AnimatedVisibility(
                            visible = !uiState.showActivationPanel,
                            enter = fadeIn(tween(180)),
                            exit = fadeOut(tween(140)),
                        ) {
                            with(sharedTransitionScope) {
                                val capsuleShape = RoundedCornerShape(100.dp)
                                val dotColor = when (uiState.daemonStatus) {
                                    is DaemonStatus.Running -> c.green
                                    is DaemonStatus.Connecting -> c.orange
                                    is DaemonStatus.Error -> c.red
                                    is DaemonStatus.Disconnected -> c.blue
                                }
                                val labelText = when (uiState.daemonStatus) {
                                    is DaemonStatus.Running -> "守护运行中"
                                    is DaemonStatus.Connecting -> "连接中"
                                    is DaemonStatus.Error -> "连接异常"
                                    is DaemonStatus.Disconnected -> "无线激活"
                                }

                                Surface(
                                    modifier = Modifier
                                        .sharedBounds(
                                            sharedContentState = rememberSharedContentState(key = "activation_fluid_morph"),
                                            animatedVisibilityScope = this@AnimatedVisibility,
                                            boundsTransform = { _, _ ->
                                                spring(dampingRatio = 0.78f, stiffness = 340f)
                                            },
                                            clipInOverlayDuringTransition = OverlayClip(capsuleShape),
                                        )
                                        .clip(capsuleShape)
                                        .applePressScale(cornerRadius = 100.dp, onClick = onOpenActivation),
                                    shape = capsuleShape,
                                    color = c.surface,
                                    border = BorderStroke(0.7.dp, c.glassBorder),
                                    shadowElevation = if (c.isDark) 0.dp else 2.dp,
                                ) {
                                    Row(
                                        modifier = Modifier.padding(start = 8.dp, end = 13.dp, top = 7.dp, bottom = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    ) {
                                        BreathingDot(
                                            color = dotColor,
                                            isPulsing = isOnline || uiState.daemonStatus is DaemonStatus.Connecting,
                                            size = 7.dp,
                                        )
                                        Text(
                                            text = labelText,
                                            style = HushType.caption.copy(fontWeight = FontWeight.SemiBold),
                                            color = c.textPrimary,
                                        )
                                    }
                                }
                            }
                        }

                        // Subtle Apple monochrome theme circle toggle
                        Surface(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .applePressScale(cornerRadius = 100.dp, onClick = onCycleTheme),
                            shape = CircleShape,
                            color = c.surface,
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

            // 2. Signature Hero Card: 3D Perspective Tilt + Touch Sheen + Rotating Conic Glow + Odometer + Scrubbing Chart
            item(key = "hero_card") {
                AppleLuminousTiltCard(
                    isActive = isOnline,
                    modifier = Modifier.staggeredCascade(0),
                ) {
                    // Top caption & right secondary badge
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

                        if (isOnline && activeSample != null) {
                            Text(
                                text = "ZRAM ${formatMemoryMbFloat(activeSample.swapUsedMb)}",
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

                    // Rolling Odometer Hero Counter
                    val heroDisplay = when {
                        !isOnline -> "未连接"
                        activeSample != null -> formatMemoryMbFloat(activeSample.availableMb)
                        uiState.memoryOverview.memTotalKb > 0L -> formatMemoryKb(uiState.memoryOverview.memAvailableKb)
                        else -> "—"
                    }
                    OdometerText(
                        text = heroDisplay,
                        style = HushType.heroNumber,
                        color = c.textPrimary,
                    )

                    if (isOnline && uiState.memoryOverview.memTotalKb > 0L) {
                        Text(
                            text = "已用 ${formatMemoryKb(uiState.memoryOverview.memUsedKb)} / 总计 ${formatMemoryKb(uiState.memoryOverview.memTotalKb)} · 左右滑动曲线查看节点",
                            style = HushType.micro,
                            color = c.textTertiary,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    } else if (!isOnline) {
                        Text(
                            text = "本机一次性无线激活后即可接管后台与内存，零 Root、启动后可关闭无线调试",
                            style = HushType.caption,
                            color = c.textSecondary,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    if (isOnline && samples.size >= 2) {
                        AppleScrubMemoryChart(
                            samples = samples,
                            selectedIndex = scrubbedIndex,
                            onSelectIndex = { scrubbedIndex = it },
                            chartHeight = 104.dp,
                        )
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
                        .staggeredCascade(1),
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

            // 4. Clean iOS Grouped Card: Active Focus Apps (tap opens Shared Element Detail!)
            val focusApps = uiState.managedApps
                .filter { it.totalPssKb > 0L || it.running }
                .sortedByDescending { it.totalPssKb }
                .take(4)

            if (focusApps.isNotEmpty()) {
                item(key = "focus_apps") {
                    Column(
                        modifier = Modifier.staggeredCascade(2),
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
                                    isBusy = app.packageName in uiState.busyPackages,
                                    sharedTransitionScope = sharedTransitionScope,
                                    animatedVisibilityScope = animatedVisibilityScope,
                                    onOpenDetail = { onOpenAppDetail(app.packageName) },
                                    onQuickAction = {
                                        if (app.group == AppGroup.SOFT_TRIM) {
                                            onQuickSoftTrim(app.packageName, app.label)
                                        } else {
                                            onQuickForceStop(app.packageName, app.label)
                                        }
                                    },
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
    dotColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = HushTheme.colors
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
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
            Text(
                text = "$count",
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
    onQuickAction: () -> Unit,
) {
    val c = HushTheme.colors
    val isSoft = app.group == AppGroup.SOFT_TRIM
    val canAct = isOnline && !app.isSystemProtected && app.group != AppGroup.PROTECTED

    with(sharedTransitionScope) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .sharedBounds(
                    sharedContentState = rememberSharedContentState(key = "app_card_overview_${app.packageName}"),
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
                    state = rememberSharedContentState(key = "app_icon_overview_${app.packageName}"),
                    animatedVisibilityScope = animatedVisibilityScope,
                ),
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = app.label,
                    style = HushType.cardTitle,
                    color = c.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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

            if (canAct) {
                val pillBg = if (isSoft) c.blueSoft else c.redSoft
                val pillFg = if (isSoft) c.blue else c.red
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(100.dp))
                        .background(pillBg)
                        .applePressScale(enabled = !isBusy, cornerRadius = 100.dp, onClick = onQuickAction)
                        .padding(horizontal = 13.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(13.dp),
                            strokeWidth = 1.8.dp,
                            color = pillFg,
                        )
                    } else {
                        Text(
                            text = if (isSoft) "瘦身" else "强杀",
                            style = HushType.micro.copy(fontWeight = FontWeight.SemiBold),
                            color = pillFg,
                        )
                    }
                }
            } else {
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
