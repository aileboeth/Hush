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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aileboeth.hush.contract.AppGroup
import com.aileboeth.hush.contract.ManagedAppInfo
import com.aileboeth.hush.design.AppIconAvatar
import com.aileboeth.hush.design.AppleGroupedCard
import com.aileboeth.hush.design.HushIcons
import com.aileboeth.hush.design.HushTheme
import com.aileboeth.hush.design.HushType
import com.aileboeth.hush.design.applePressScale
import com.aileboeth.hush.design.formatMemoryKb
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
    modifier: Modifier = Modifier,
) {
    val c = HushTheme.colors
    val apps = uiState.filteredApps

    PullToRefreshBox(
        isRefreshing = uiState.isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "apps_header") {
                Column(
                    modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = "应用", style = HushType.largeTitle, color = c.textPrimary)

                        // Minimal sort pill
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(100.dp))
                                .applePressScale(cornerRadius = 100.dp) {
                                    val entries = AppSortMode.entries
                                    val next = entries[(entries.indexOf(uiState.appSort) + 1) % entries.size]
                                    onSortChange(next)
                                },
                            shape = RoundedCornerShape(100.dp),
                            color = c.surface,
                            border = BorderStroke(0.7.dp, c.glassBorder),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
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

                    // Pure iOS Search Capsule
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (c.isDark) c.surface else c.trackBg.copy(alpha = 0.65f))
                            .padding(horizontal = 13.dp, vertical = 10.dp),
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
                            Icon(
                                imageVector = HushIcons.Close,
                                contentDescription = "清除",
                                tint = c.textTertiary,
                                modifier = Modifier
                                    .size(16.dp)
                                    .applePressScale(cornerRadius = 100.dp) { onSearchChange("") },
                            )
                        }
                    }

                    // Clean horizontal filter pills
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                                    .clip(RoundedCornerShape(100.dp))
                                    .applePressScale(cornerRadius = 100.dp) { onFilterChange(mode) },
                                shape = RoundedCornerShape(100.dp),
                                color = if (selected) c.blue else c.surface,
                                border = if (!selected && c.isDark) BorderStroke(0.6.dp, c.glassBorder) else null,
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    Text(
                                        text = mode.label,
                                        style = HushType.caption.copy(fontWeight = FontWeight.SemiBold),
                                        color = if (selected) c.onBlue else c.textPrimary,
                                    )
                                    Text(
                                        text = count.toString(),
                                        style = HushType.micro,
                                        color = if (selected) c.onBlue.copy(alpha = 0.8f) else c.textTertiary,
                                    )
                                }
                            }
                        }
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
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        onClick = { onSelectApp(app.packageName) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun AppSharedElementRowCard(
    app: ManagedAppInfo,
    index: Int,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onClick: () -> Unit,
) {
    val c = HushTheme.colors
    val shape = RoundedCornerShape(20.dp)
    val accent = tierAccentColor(app.group)

    with(sharedTransitionScope) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .staggeredCascade(index)
                .sharedBounds(
                    sharedContentState = rememberSharedContentState(key = "app_card_${app.packageName}"),
                    animatedVisibilityScope = animatedVisibilityScope,
                    boundsTransform = { _, _ -> spring(dampingRatio = 0.80f, stiffness = 360f) },
                    clipInOverlayDuringTransition = OverlayClip(shape),
                )
                .clip(shape)
                .applePressScale(pressedScale = 0.975f, cornerRadius = 20.dp, onClick = onClick),
            shape = shape,
            color = c.surface,
            border = if (c.isDark) BorderStroke(0.6.dp, c.glassBorder) else null,
            shadowElevation = if (c.isDark) 0.dp else 1.2.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 15.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(13.dp),
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
                    val meta = buildString {
                        if (app.totalPssKb > 0L) {
                            append(formatMemoryKb(app.totalPssKb))
                            if (app.swapPssKb > 0L) append(" · Swap ${formatMemoryKb(app.swapPssKb)}")
                        } else {
                            append(if (app.running) "后台运行中" else "未运行")
                        }
                        if (app.isFrozen) append(" · 冻结")
                        if (app.isPlayingAudio) append(" · 音频免杀")
                        if (app.isDualSpace) append(" · 双开")
                        if (app.restriction.hasAnyRestriction) append(" · 已限后台")
                    }
                    Text(
                        text = meta,
                        style = HushType.micro,
                        color = c.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Ultra-clean dot + tier text + chevron
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (app.group != AppGroup.UNSET) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(accent))
                        Text(
                            text = tierShortLabel(app.group),
                            style = HushType.micro.copy(fontWeight = FontWeight.SemiBold),
                            color = accent,
                        )
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
