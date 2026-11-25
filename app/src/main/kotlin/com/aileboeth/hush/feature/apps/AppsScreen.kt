package com.aileboeth.hush.feature.apps

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aileboeth.hush.AppleConfirmDialog
import com.aileboeth.hush.contract.AppGroup
import com.aileboeth.hush.contract.DaemonStatus
import com.aileboeth.hush.contract.ManagedAppInfo
import com.aileboeth.hush.design.AppIconAvatar
import com.aileboeth.hush.design.AppleGroupedCard
import com.aileboeth.hush.design.HushIcons
import com.aileboeth.hush.design.HushTheme
import com.aileboeth.hush.design.HushType
import com.aileboeth.hush.design.StaggerClock
import com.aileboeth.hush.design.applePressScale
import com.aileboeth.hush.design.formatMemoryKb
import com.aileboeth.hush.design.rememberStaggerClock
import com.aileboeth.hush.design.staggeredCascade
import com.aileboeth.hush.design.tierAccentColor
import com.aileboeth.hush.design.tierShortLabel
import com.aileboeth.hush.state.AppFilterMode
import com.aileboeth.hush.state.AppSortMode
import com.aileboeth.hush.state.HushUiState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun AppsScreen(
    uiState: HushUiState,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onRefresh: () -> Unit,
    onFilterChange: (AppFilterMode) -> Unit,
    onSortChange: (AppSortMode) -> Unit,
    onSearchChange: (String) -> Unit,
    onSelectApp: (String) -> Unit,
    onRestoreAllRestrictions: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val c = HushTheme.colors
    val apps = uiState.filteredApps
    val isRunning = uiState.daemonStatus is DaemonStatus.Running
    val stagger = rememberStaggerClock(uiState.appFilter, uiState.appSort)
    val refreshState = rememberPullToRefreshState()
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var showRestoreDialog by remember { mutableStateOf(false) }

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
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = statusTop + 10.dp,
                bottom = 120.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "apps_header") {
                Column(
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            val countCaption = if (uiState.appFilter == AppFilterMode.ALL && uiState.appSearchQuery.isBlank()) {
                                "${uiState.managedApps.size} 个应用"
                            } else {
                                "显示 ${apps.size} / ${uiState.managedApps.size} 个应用"
                            }
                            Text(
                                text = countCaption,
                                style = HushType.caption.copy(fontWeight = FontWeight.SemiBold),
                                color = c.textSecondary,
                            )
                            Text(text = "应用", style = HushType.largeTitle, color = c.textPrimary)
                        }

                        Row(
                            modifier = Modifier.padding(bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (uiState.restrictedAppCount > 0 && isRunning) {
                                Text(
                                    text = "全部还原",
                                    style = HushType.caption.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (uiState.isGlobalActionBusy) c.textTertiary else c.blue,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(100.dp))
                                        .applePressScale(
                                            enabled = !uiState.isGlobalActionBusy,
                                            cornerRadius = 100.dp,
                                        ) {
                                            showRestoreDialog = true
                                        }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                )
                            }

                            // Minimal sort cycle pill
                            Surface(
                                modifier = Modifier
                                    .applePressScale(cornerRadius = 100.dp) {
                                        val entries = AppSortMode.entries
                                        val next = entries[(entries.indexOf(uiState.appSort) + 1) % entries.size]
                                        onSortChange(next)
                                    },
                                shape = RoundedCornerShape(100.dp),
                                color = if (c.isDark) c.surfaceElevated else c.surface,
                                border = BorderStroke(0.7.dp, c.glassBorder),
                                shadowElevation = if (c.isDark) 0.dp else 1.5.dp,
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    Icon(
                                        imageVector = HushIcons.Sort,
                                        contentDescription = null,
                                        tint = c.blue,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Text(
                                        text = uiState.appSort.label,
                                        style = HushType.micro.copy(fontWeight = FontWeight.SemiBold),
                                        color = c.textPrimary,
                                    )
                                }
                            }
                        }
                    }

                    // Pure iOS Search Capsule
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(if (c.isDark) c.surface else c.trackBg.copy(alpha = 0.65f))
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = HushIcons.Search,
                            contentDescription = null,
                            tint = c.textTertiary,
                            modifier = Modifier.size(17.dp),
                        )
                        Box(modifier = Modifier.weight(1f)) {
                            if (uiState.appSearchQuery.isEmpty()) {
                                Text("搜索应用或包名", style = HushType.body, color = c.textTertiary)
                            }
                            BasicTextField(
                                value = uiState.appSearchQuery,
                                onValueChange = onSearchChange,
                                singleLine = true,
                                textStyle = HushType.body.copy(color = c.textPrimary),
                                cursorBrush = SolidColor(c.blue),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (uiState.appSearchQuery.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(c.textTertiary.copy(alpha = 0.55f))
                                    .applePressScale(cornerRadius = 100.dp, shadowAlpha = 0f) {
                                        onSearchChange("")
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = HushIcons.Close,
                                    contentDescription = "清除",
                                    tint = c.surface,
                                    modifier = Modifier.size(11.dp),
                                )
                            }
                        }
                    }

                    // Compact horizontal filter strip with live counts
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        AppFilterMode.entries.forEach { mode ->
                            val selected = uiState.appFilter == mode
                            val count = when (mode) {
                                AppFilterMode.ALL -> uiState.managedApps.size
                                AppFilterMode.RUNNING -> uiState.managedApps.count { it.running || it.totalPssKb > 0L }
                                AppFilterMode.FORCE_STOP -> uiState.tierCounts[AppGroup.FORCE_STOP] ?: 0
                                AppFilterMode.SOFT_TRIM -> uiState.tierCounts[AppGroup.SOFT_TRIM] ?: 0
                                AppFilterMode.PROTECTED -> uiState.tierCounts[AppGroup.PROTECTED] ?: 0
                            }
                            Surface(
                                modifier = Modifier
                                    .applePressScale(cornerRadius = 100.dp) { onFilterChange(mode) },
                                shape = RoundedCornerShape(100.dp),
                                color = if (selected) c.blue else c.surface,
                                border = if (!selected) {
                                    BorderStroke(0.6.dp, if (c.isDark) c.glassBorder else c.separator)
                                } else {
                                    null
                                },
                                shadowElevation = if (!selected && !c.isDark) 0.5.dp else 0.dp,
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = mode.label,
                                        style = HushType.caption.copy(fontWeight = FontWeight.SemiBold),
                                        color = if (selected) c.onBlue else c.textPrimary,
                                    )
                                    Text(
                                        text = count.toString(),
                                        style = HushType.micro.copy(fontFeatureSettings = "tnum"),
                                        color = if (selected) c.onBlue.copy(alpha = 0.84f) else c.textTertiary,
                                    )
                                }
                            }
                        }
                    }

                    if (!isRunning && uiState.managedApps.isNotEmpty()) {
                        Text(
                            text = "守护未运行 · 可先完成分档，激活后实时读取内存与后台操作",
                            style = HushType.micro,
                            color = c.textTertiary,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }

            if (apps.isEmpty()) {
                item(key = "empty_apps") {
                    AppleGroupedCard(contentPadding = PaddingValues(28.dp)) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("没有匹配的应用", style = HushType.cardTitle, color = c.textPrimary)
                            Text("切换筛选条件或清空搜索词再试试", style = HushType.caption, color = c.textTertiary)
                        }
                    }
                }
            } else {
                itemsIndexed(
                    items = apps,
                    key = { _, app -> "${app.packageName}:${app.userId}" },
                ) { index, app ->
                    AppSharedElementRowCard(
                        app = app,
                        index = index,
                        stagger = stagger,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        onClick = { onSelectApp(app.packageName) },
                    )
                }
            }
        }
    }

    if (showRestoreDialog) {
        AppleConfirmDialog(
            title = "还原全部后台限制？",
            message = "将恢复 Hush 写入过的 ${uiState.restrictedAppCount} 个应用的 appops 与待机桶修改，不影响你的应用档位配置。",
            confirmText = "确认还原",
            destructive = false,
            onDismiss = { showRestoreDialog = false },
            onConfirm = {
                showRestoreDialog = false
                onRestoreAllRestrictions()
            },
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun AppSharedElementRowCard(
    app: ManagedAppInfo,
    index: Int,
    stagger: StaggerClock,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onClick: () -> Unit,
) {
    val c = HushTheme.colors
    val shape = RoundedCornerShape(20.dp)
    val accent = tierAccentColor(app.group)
    val statusBadge = primaryStatusBadge(app)

    with(sharedTransitionScope) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .staggeredCascade(index, stagger)
                .sharedBounds(
                    sharedContentState = rememberSharedContentState(key = "app_card_${app.packageName}"),
                    animatedVisibilityScope = animatedVisibilityScope,
                    boundsTransform = { _, _ -> spring(dampingRatio = 0.80f, stiffness = 360f) },
                    clipInOverlayDuringTransition = OverlayClip(shape),
                )
                .applePressScale(pressedScale = 0.975f, cornerRadius = 20.dp, onClick = onClick),
            shape = shape,
            color = c.surface,
            border = if (c.isDark) BorderStroke(0.6.dp, c.glassBorder) else null,
            shadowElevation = if (c.isDark) 0.dp else 1.2.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 15.dp, vertical = 12.5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppIconAvatar(
                    packageName = app.packageName,
                    label = app.label,
                    size = 42.dp,
                    modifier = Modifier.sharedElement(
                        state = rememberSharedContentState(key = "app_icon_${app.packageName}"),
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
                        modifier = Modifier.sharedElement(
                            state = rememberSharedContentState(key = "app_title_${app.packageName}"),
                            animatedVisibilityScope = animatedVisibilityScope,
                        ),
                    )
                    // Pure memory/runtime line (no extra status words crammed in, preventing truncation!)
                    val memoryLine = when {
                        app.totalPssKb > 0L && app.swapPssKb > 0L ->
                            "${formatMemoryKb(app.totalPssKb)} · Swap ${formatMemoryKb(app.swapPssKb)}"
                        app.totalPssKb > 0L -> formatMemoryKb(app.totalPssKb)
                        app.running -> "后台运行中"
                        else -> "未运行"
                    }
                    Text(
                        text = memoryLine,
                        style = HushType.micro.copy(fontFeatureSettings = "tnum"),
                        color = c.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Clean right-aligned tier + status badge stack + chevron (zero clipping)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    if (app.group != AppGroup.UNSET || statusBadge != null) {
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            if (app.group != AppGroup.UNSET) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Box(Modifier.size(6.dp).clip(CircleShape).background(accent))
                                    Text(
                                        text = tierShortLabel(app.group),
                                        style = HushType.micro.copy(fontWeight = FontWeight.SemiBold),
                                        color = accent,
                                        maxLines = 1,
                                    )
                                }
                            }
                            if (statusBadge != null) {
                                val (tagText, fg, bg) = statusBadge
                                Text(
                                    text = tagText,
                                    style = HushType.micro.copy(
                                        fontSize = 10.5.sp,
                                        lineHeight = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    ),
                                    color = fg,
                                    maxLines = 1,
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(bg)
                                        .padding(horizontal = 7.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }

                    Icon(
                        imageVector = HushIcons.ChevronRight,
                        contentDescription = null,
                        tint = c.textTertiary,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun primaryStatusBadge(app: ManagedAppInfo): Triple<String, Color, Color>? {
    val c = HushTheme.colors
    return when {
        app.isPlayingAudio -> Triple("音频中", c.purple, c.purple.copy(alpha = if (c.isDark) 0.24f else 0.12f))
        app.restriction.hasAnyRestriction -> Triple("已限后台", c.orange, c.orangeSoft)
        app.isFrozen -> Triple("冻结", c.indigo, c.indigoSoft)
        app.isDualSpace -> Triple("双开", c.blue, c.blueSoft)
        app.running -> Triple("运行中", c.green, c.greenSoft)
        else -> null
    }
}
