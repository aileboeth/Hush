package com.aileboeth.hush.feature.policies

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aileboeth.hush.AppleConfirmDialog
import com.aileboeth.hush.BuildConfig
import com.aileboeth.hush.contract.DaemonStatus
import com.aileboeth.hush.contract.PolicyConfig
import com.aileboeth.hush.design.AppleGroupedCard
import com.aileboeth.hush.design.AppleInsetDivider
import com.aileboeth.hush.design.HushIcons
import com.aileboeth.hush.design.HushTheme
import com.aileboeth.hush.design.HushType
import com.aileboeth.hush.design.OdometerText
import com.aileboeth.hush.design.applePressScale
import com.aileboeth.hush.design.formatMemoryMb
import com.aileboeth.hush.design.StaggerClock
import com.aileboeth.hush.design.openExternalUrl
import com.aileboeth.hush.design.staggeredCascade
import com.aileboeth.hush.state.HushUiState
import com.aileboeth.hush.update.AppUpdater
import com.aileboeth.hush.update.UpdateState
import kotlin.math.roundToInt

/**
 * Pure Apple iOS Settings–inspired policy screen with grouped cards, sleek iOS toggles,
 * classic pill-thumb sliders, rolling [OdometerText] readouts, and natural-language footnotes.
 */
@Composable
fun PoliciesScreen(
    uiState: HushUiState,
    onUpdatePolicy: (PolicyConfig, Boolean) -> Unit,
    onOpenActivitySheet: () -> Unit,
    onRestoreAllRestrictions: () -> Unit,
    onOpenActivation: () -> Unit,
    onStopDaemon: () -> Unit,
    onCheckUpdates: () -> Unit = {},
    onOpenUpdateDialog: () -> Unit = {},
    onOpenDisclaimer: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    modifier: Modifier = Modifier,
) {
    val c = HushTheme.colors
    val context = LocalContext.current
    val policy = uiState.policyConfig
    val isRunning = uiState.daemonStatus is DaemonStatus.Running
    val stagger = remember { StaggerClock(windowMs = if (listState.firstVisibleItemIndex == 0) 900L else 0L) }
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var showRestoreDialog by remember { mutableStateOf(false) }
    var showStopDialog by remember { mutableStateOf(false) }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = statusTop + 10.dp,
            bottom = 120.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item(key = "title") {
            Column(
                modifier = Modifier.padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = "自动化",
                    style = HushType.caption.copy(fontWeight = FontWeight.SemiBold),
                    color = c.textSecondary,
                )
                Text(
                    text = "策略",
                    style = HushType.largeTitle,
                    color = c.textPrimary,
                )
            }
        }

        // Section 1: Master Automation
        item(key = "master") {
            Column(
                modifier = Modifier.staggeredCascade(0, stagger),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppleGroupedCard {
                    AppleSwitchRow(
                        title = "自动后台治理",
                        subtitle = if (policy.enabled) "按应用档位静默接管" else "已暂停自动动作",
                        checked = policy.enabled,
                        onCheckedChange = { onUpdatePolicy(policy.copy(enabled = it), true) },
                    )
                }
                Text(
                    text = buildFootnote(policy),
                    style = HushType.micro,
                    color = c.textTertiary,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }

        // Section 2: Timing & Exemptions
        item(key = "timing") {
            Column(
                modifier = Modifier.staggeredCascade(1, stagger),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SectionCaption("时机与豁免")
                AppleGroupedCard {
                    AppleSliderRow(
                        label = "后台容忍时长",
                        valueText = "${policy.backgroundTimeoutMinutes} 分钟",
                        value = policy.backgroundTimeoutMinutes.toFloat(),
                        valueRange = 1f..30f,
                        steps = 28,
                        onValueChange = {
                            onUpdatePolicy(policy.copy(backgroundTimeoutMinutes = it.roundToInt()), false)
                        },
                    )
                    AppleInsetDivider()
                    AppleSwitchRow(
                        title = "音频播放免杀",
                        subtitle = "听歌、听书或播客播放期间自动跳过",
                        checked = policy.exemptActiveAudio,
                        onCheckedChange = { onUpdatePolicy(policy.copy(exemptActiveAudio = it), true) },
                    )
                    AppleInsetDivider()
                    AppleSwitchRow(
                        title = "息屏静默清理",
                        subtitle = "锁屏后自动收拢后台进程",
                        checked = policy.screenOffCleanupEnabled,
                        onCheckedChange = { onUpdatePolicy(policy.copy(screenOffCleanupEnabled = it), true) },
                    )
                    AnimatedVisibility(visible = policy.screenOffCleanupEnabled) {
                        Column {
                            AppleInsetDivider()
                            AppleSliderRow(
                                label = "息屏延迟",
                                valueText = if (policy.screenOffDelayMinutes == 0) "立即" else "${policy.screenOffDelayMinutes} 分钟",
                                value = policy.screenOffDelayMinutes.toFloat(),
                                valueRange = 0f..15f,
                                steps = 14,
                                onValueChange = {
                                    onUpdatePolicy(policy.copy(screenOffDelayMinutes = it.roundToInt()), false)
                                },
                            )
                        }
                    }
                    AppleInsetDivider()
                    AppleSwitchRow(
                        title = "瘦身联动双开分身",
                        subtitle = "主应用设瘦身时自动联动其双开分身（也可在应用页单独配置）",
                        checked = policy.softTrimIncludeDualSpace,
                        onCheckedChange = { onUpdatePolicy(policy.copy(softTrimIncludeDualSpace = it), true) },
                    )
                }
            }
        }

        // Section 3: Memory Pressure Guardrails
        item(key = "pressure") {
            Column(
                modifier = Modifier.staggeredCascade(2, stagger),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SectionCaption("内存水位护航")
                AppleGroupedCard {
                    AppleSwitchRow(
                        title = "低内存高压回收",
                        subtitle = "可用内存跌破水位时按最久未用顺序释放",
                        checked = policy.memoryPressureEnabled,
                        onCheckedChange = { onUpdatePolicy(policy.copy(memoryPressureEnabled = it), true) },
                    )
                    AnimatedVisibility(visible = policy.memoryPressureEnabled) {
                        Column {
                            AppleInsetDivider()
                            AppleSliderRow(
                                label = "最低可用内存水位",
                                valueText = formatMemoryMb(policy.minAvailableRamMb),
                                value = policy.minAvailableRamMb.toFloat(),
                                valueRange = 512f..3072f,
                                steps = 19,
                                onValueChange = {
                                    val snapped = ((it / 128f).roundToInt() * 128).coerceIn(512, 3072)
                                    onUpdatePolicy(policy.copy(minAvailableRamMb = snapped), false)
                                },
                            )
                            AppleInsetDivider()
                            AppleSliderRow(
                                label = "受管应用总预算",
                                valueText = formatMemoryMb(policy.managedBudgetMb),
                                value = policy.managedBudgetMb.toFloat(),
                                valueRange = 256f..4096f,
                                steps = 14,
                                onValueChange = {
                                    val snapped = ((it / 256f).roundToInt() * 256).coerceIn(256, 4096)
                                    onUpdatePolicy(policy.copy(managedBudgetMb = snapped), false)
                                },
                            )
                        }
                    }
                    AppleInsetDivider()
                    AppleSliderRow(
                        label = "巡检间隔",
                        valueText = "${policy.pollIntervalSeconds} 秒",
                        value = policy.pollIntervalSeconds.toFloat(),
                        valueRange = 15f..120f,
                        steps = 6,
                        onValueChange = {
                            val snapped = ((it / 15f).roundToInt() * 15).coerceIn(15, 120)
                            onUpdatePolicy(policy.copy(pollIntervalSeconds = snapped), false)
                        },
                    )
                }
            }
        }

        // Section 3.5: Multi-User Secondary Space Auto-Stop
        item(key = "secondary_spaces") {
            Column(
                modifier = Modifier.staggeredCascade(3, stagger),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SectionCaption("多用户空间")
                AppleGroupedCard {
                    AppleSwitchRow(
                        title = "离开第二系统自动停机",
                        subtitle = "切回主系统后彻底关闭后台第二系统，释放全部内存",
                        checked = policy.autoStopSecondaryUsers,
                        onCheckedChange = { onUpdatePolicy(policy.copy(autoStopSecondaryUsers = it), true) },
                    )
                    AnimatedVisibility(visible = policy.autoStopSecondaryUsers) {
                        Column {
                            AppleInsetDivider()
                            AppleSliderRow(
                                label = "离开停机延迟",
                                valueText = if (policy.secondaryUserStopDelayMinutes == 0) "立即" else "${policy.secondaryUserStopDelayMinutes} 分钟",
                                value = policy.secondaryUserStopDelayMinutes.toFloat(),
                                valueRange = 0f..15f,
                                steps = 14,
                                onValueChange = {
                                    onUpdatePolicy(policy.copy(secondaryUserStopDelayMinutes = it.roundToInt()), false)
                                },
                            )
                        }
                    }
                    AppleInsetDivider()
                    AppleSwitchRow(
                        title = "息屏自动停机第二系统",
                        subtitle = "锁屏后将后台运行中的第二系统整机休眠（不影响双开分身）",
                        checked = policy.screenOffStopSecondaryUsers,
                        onCheckedChange = { onUpdatePolicy(policy.copy(screenOffStopSecondaryUsers = it), true) },
                    )
                }
            }
        }

        // Section 4: Daemon & Safety Actions
        item(key = "daemon_ops") {
            Column(
                modifier = Modifier.staggeredCascade(4, stagger),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SectionCaption("守护与安全")
                AppleGroupedCard {
                    AppleNavActionRow(
                        title = "查看清理记录",
                        trailing = "${uiState.cleanupLogs.size} 条",
                        onClick = onOpenActivitySheet,
                    )
                    AppleInsetDivider()
                    AppleNavActionRow(
                        title = "无线激活与 USB 引导",
                        trailing = if (isRunning) "已就绪" else "未激活",
                        onClick = onOpenActivation,
                    )
                    AppleInsetDivider()
                    AppleNavActionRow(
                        title = "一键还原全部后台限制",
                        trailing = if (uiState.restrictedAppCount > 0) "${uiState.restrictedAppCount} 个" else null,
                        titleColor = c.blue,
                        enabled = isRunning && !uiState.isGlobalActionBusy,
                        onClick = { showRestoreDialog = true },
                    )
                    AppleInsetDivider()
                    AppleNavActionRow(
                        title = "停止后台守护进程",
                        titleColor = c.red,
                        enabled = isRunning && !uiState.isGlobalActionBusy,
                        showChevron = false,
                        onClick = { showStopDialog = true },
                    )
                }
            }
        }

        // Section 5: About, Author GitHub, Auto-Update & Disclaimer
        item(key = "about_update") {
            val updateTrailing = when (val u = uiState.updateState) {
                is UpdateState.Checking -> "检查中…"
                is UpdateState.Available -> "发现 v${u.release.versionName}"
                is UpdateState.Downloading -> {
                    val pct = if (u.progress >= 0f) "${(u.progress * 100).roundToInt()}%" else "下载中"
                    "下载 $pct"
                }
                is UpdateState.ReadyToInstall -> "待安装 v${u.release.versionName}"
                is UpdateState.UpToDate -> "v${BuildConfig.VERSION_NAME} · 最新"
                else -> "v${BuildConfig.VERSION_NAME}"
            }
            val hasPendingUpdate = uiState.updateState is UpdateState.Available ||
                uiState.updateState is UpdateState.Downloading ||
                uiState.updateState is UpdateState.ReadyToInstall

            Column(
                modifier = Modifier.staggeredCascade(5, stagger),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SectionCaption("关于与更新")
                AppleGroupedCard {
                    AppleNavActionRow(
                        title = "检查应用更新",
                        trailing = updateTrailing,
                        trailingColor = if (hasPendingUpdate) c.blue else c.textTertiary,
                        onClick = {
                            if (hasPendingUpdate) {
                                onOpenUpdateDialog()
                            } else {
                                onCheckUpdates()
                            }
                        },
                    )
                    AppleInsetDivider()
                    AppleNavActionRow(
                        title = "作者",
                        trailing = "${AppUpdater.AUTHOR_NAME} · GitHub",
                        onClick = { openExternalUrl(context, AppUpdater.AUTHOR_GITHUB_URL) },
                    )
                    AppleInsetDivider()
                    AppleNavActionRow(
                        title = "开源仓库",
                        trailing = "aileboeth/Hush",
                        onClick = { openExternalUrl(context, AppUpdater.REPO_GITHUB_URL) },
                    )
                    AppleInsetDivider()
                    AppleNavActionRow(
                        title = "诊断日志",
                        trailing = "查看 / 导出",
                        onClick = onOpenDiagnostics,
                    )
                    AppleInsetDivider()
                    AppleNavActionRow(
                        title = "免责声明与安全说明",
                        onClick = onOpenDisclaimer,
                    )
                }
                Text(
                    text = "Hush v${BuildConfig.VERSION_NAME} · by ${AppUpdater.AUTHOR_NAME} · 零 Root · 开源透明",
                    style = HushType.micro,
                    color = c.textTertiary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }

    if (showRestoreDialog) {
        AppleConfirmDialog(
            title = "还原全部后台限制？",
            message = "将恢复 Hush 写入过的所有 appops、待机桶修改，并自动重新启用通过 Hush 停用的应用，不影响你的应用档位配置。",
            confirmText = "确认还原",
            destructive = false,
            onDismiss = { showRestoreDialog = false },
            onConfirm = {
                showRestoreDialog = false
                onRestoreAllRestrictions()
            },
        )
    }

    if (showStopDialog) {
        AppleConfirmDialog(
            title = "停止守护进程？",
            message = "停止后将暂停自动清理与内存采样，下次可通过无线调试一键恢复。",
            confirmText = "停止",
            destructive = true,
            onDismiss = { showStopDialog = false },
            onConfirm = {
                showStopDialog = false
                onStopDaemon()
            },
        )
    }
}

private fun buildFootnote(policy: PolicyConfig): String {
    if (!policy.enabled) return "自动治理已暂停，你仍可随时在应用页执行手动强杀或瘦身。"
    val parts = mutableListOf("切后台 ${policy.backgroundTimeoutMinutes} 分钟后调度")
    if (policy.screenOffCleanupEnabled) {
        parts += if (policy.screenOffDelayMinutes == 0) "息屏立即清理" else "息屏 ${policy.screenOffDelayMinutes} 分钟后清理"
    }
    if (policy.exemptActiveAudio) parts += "音频播放免杀"
    if (policy.memoryPressureEnabled) parts += "可用低于 ${formatMemoryMb(policy.minAvailableRamMb)} 时按 LRU 回收"
    if (policy.autoStopSecondaryUsers) {
        parts += if (policy.secondaryUserStopDelayMinutes == 0) {
            "离开副系统立即停机"
        } else {
            "离开副系统 ${policy.secondaryUserStopDelayMinutes} 分钟后停机"
        }
    }
    return parts.joinToString(" · ")
}

@Composable
private fun SectionCaption(text: String) {
    Text(
        text = text,
        style = HushType.caption.copy(fontWeight = FontWeight.SemiBold),
        color = HushTheme.colors.textSecondary,
        modifier = Modifier.padding(start = 12.dp),
    )
}

@Composable
private fun AppleSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val c = HushTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, style = HushType.cardTitle, color = c.textPrimary)
            Text(text = subtitle, style = HushType.micro, color = c.textSecondary)
        }
        PolicyIosSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun PolicyIosSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val c = HushTheme.colors
    val haptic = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val onChangeLatest by rememberUpdatedState(onCheckedChange)

    val trackColor by animateColorAsState(
        targetValue = if (checked) c.green else c.trackBg,
        animationSpec = tween(200),
        label = "policySwitchTrack",
    )
    val thumbFraction by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 460f),
        label = "policySwitchPos",
    )
    val stretch by animateDpAsState(
        targetValue = if (pressed && enabled) 6.dp else 0.dp,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 520f),
        label = "policySwitchStretch",
    )

    Box(
        modifier = Modifier
            .graphicsLayer { alpha = if (enabled) 1f else 0.42f }
            .size(width = 51.dp, height = 31.dp)
            .clip(CircleShape)
            .background(trackColor)
            .pointerInput(enabled, checked) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        pressed = true
                        val released = tryAwaitRelease()
                        pressed = false
                        if (released) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onChangeLatest(!checked)
                        }
                    },
                )
            },
    ) {
        val knobW = 27.dp + stretch
        val travel = 51.dp - 4.dp - knobW
        Box(
            modifier = Modifier
                .padding(2.dp)
                .offset(x = travel * thumbFraction)
                .size(width = knobW, height = 27.dp)
                .shadow(
                    elevation = 3.dp,
                    shape = CircleShape,
                    ambientColor = Color.Black.copy(alpha = 0.18f),
                    spotColor = Color.Black.copy(alpha = 0.24f),
                )
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

@Composable
private fun AppleSliderRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    val c = HushTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, style = HushType.cardTitle, color = c.textPrimary)
            OdometerText(
                text = valueText,
                style = HushType.compactNumber,
                color = c.blue,
            )
        }
        Spacer(Modifier.height(2.dp))
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = if (c.isDark) Color.White else c.blue,
                activeTrackColor = c.blue,
                inactiveTrackColor = c.trackBg,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun AppleNavActionRow(
    title: String,
    onClick: () -> Unit,
    trailing: String? = null,
    titleColor: Color = HushTheme.colors.textPrimary,
    trailingColor: Color = HushTheme.colors.textTertiary,
    enabled: Boolean = true,
    showChevron: Boolean = true,
) {
    val c = HushTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .applePressScale(enabled = enabled, pressedScale = 0.985f, cornerRadius = 12.dp, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = HushType.cardTitle,
            color = if (enabled) titleColor else c.textTertiary,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (trailing != null) {
                Text(
                    text = trailing,
                    style = HushType.caption.copy(
                        fontWeight = if (trailingColor == c.blue) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = trailingColor,
                )
            }
            if (showChevron) {
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
