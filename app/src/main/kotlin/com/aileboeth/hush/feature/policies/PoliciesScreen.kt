package com.aileboeth.hush.feature.policies

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
import com.aileboeth.hush.design.staggeredCascade
import com.aileboeth.hush.state.HushUiState
import kotlin.math.roundToInt

/**
 * Pure Apple iOS Settings–inspired policy screen with grouped cards, iOS green switches,
 * rolling Odometer slider readouts, and restrained Apple footnotes.
 */
@Composable
fun PoliciesScreen(
    uiState: HushUiState,
    onUpdatePolicy: (PolicyConfig, Boolean) -> Unit,
    onOpenActivitySheet: () -> Unit,
    onRestoreAllRestrictions: () -> Unit,
    onOpenActivation: () -> Unit,
    onStopDaemon: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = HushTheme.colors
    val policy = uiState.policyConfig
    val isRunning = uiState.daemonStatus is DaemonStatus.Running
    var showRestoreDialog by remember { mutableStateOf(false) }
    var showStopDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item(key = "title") {
            Text(
                text = "策略",
                style = HushType.largeTitle,
                color = c.textPrimary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        // Section 1: Master Automation
        item(key = "master") {
            Column(
                modifier = Modifier.staggeredCascade(0),
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
                modifier = Modifier.staggeredCascade(1),
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
                        title = "瘦身包含双开空间",
                        subtitle = "默认关闭，保护双开微信/社交分身",
                        checked = policy.softTrimIncludeDualSpace,
                        onCheckedChange = { onUpdatePolicy(policy.copy(softTrimIncludeDualSpace = it), true) },
                    )
                }
            }
        }

        // Section 3: Memory Pressure Guardrails
        item(key = "pressure") {
            Column(
                modifier = Modifier.staggeredCascade(2),
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

        // Section 4: Daemon & Safety Actions
        item(key = "daemon_ops") {
            Column(
                modifier = Modifier.staggeredCascade(3),
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
                Text(
                    text = "Hush ${BuildConfig.VERSION_NAME} · 零 Root · 本机环回鉴权",
                    style = HushType.micro,
                    color = c.textTertiary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }

    if (showRestoreDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreDialog = false },
            containerColor = c.surface,
            title = { Text("还原全部后台限制？", style = HushType.sectionTitle, color = c.textPrimary) },
            text = { Text("将恢复 Hush 写入过的所有 appops 与待机桶修改，不影响你的应用档位配置。", style = HushType.body, color = c.textSecondary) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRestoreDialog = false
                        onRestoreAllRestrictions()
                    },
                ) { Text("确认还原", color = c.blue, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreDialog = false }) { Text("取消", color = c.textSecondary) }
            },
        )
    }

    if (showStopDialog) {
        AlertDialog(
            onDismissRequest = { showStopDialog = false },
            containerColor = c.surface,
            title = { Text("停止守护进程？", style = HushType.sectionTitle, color = c.textPrimary) },
            text = { Text("停止后将暂停自动清理与内存采样，下次可通过无线调试一键恢复。", style = HushType.body, color = c.textSecondary) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showStopDialog = false
                        onStopDaemon()
                    },
                ) { Text("停止", color = c.red, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { showStopDialog = false }) { Text("取消", color = c.blue) }
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
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = c.green,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = c.trackBg,
                uncheckedBorderColor = Color.Transparent,
            ),
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
                Text(text = trailing, style = HushType.caption, color = c.textTertiary)
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
