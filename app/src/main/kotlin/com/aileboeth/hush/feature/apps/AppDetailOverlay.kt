package com.aileboeth.hush.feature.apps

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aileboeth.hush.contract.AppGroup
import com.aileboeth.hush.contract.AppRestriction
import com.aileboeth.hush.contract.ManagedAppInfo
import com.aileboeth.hush.contract.SafetyGuardrails
import com.aileboeth.hush.design.AppIconAvatar
import com.aileboeth.hush.design.AppleGroupedCard
import com.aileboeth.hush.design.AppleInsetDivider
import com.aileboeth.hush.design.ApplePillButton
import com.aileboeth.hush.design.HushIcons
import com.aileboeth.hush.design.HushTheme
import com.aileboeth.hush.design.HushType
import com.aileboeth.hush.design.applePressScale
import com.aileboeth.hush.design.copyTextToClipboard
import com.aileboeth.hush.design.formatMemoryKb
import com.aileboeth.hush.design.standbyBucketLabel
import com.aileboeth.hush.design.tierAccentColor
import com.aileboeth.hush.design.tierDescription
import com.aileboeth.hush.design.tierLabel

/**
 * Full-bleed Apple Detail Page connected to list cards via Compose Shared Element Transition
 * (`sharedBounds` container scaling + `sharedElement` icon & title morph).
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AppDetailOverlay(
    app: ManagedAppInfo,
    fromOverview: Boolean,
    isDaemonOnline: Boolean,
    isBusy: Boolean,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onClose: () -> Unit,
    onSelectGroup: (AppGroup) -> Unit,
    onForceStop: () -> Unit,
    onSoftTrim: () -> Unit,
    onUpdateRestriction: (AppRestriction) -> Unit,
    onCopiedNotice: (String) -> Unit,
) {
    val c = HushTheme.colors
    val context = LocalContext.current
    var showSoftTrimKillConfirm by remember { mutableStateOf(false) }
    val restrictionBlocked = SafetyGuardrails.isRestrictionBlocked(app.packageName, app.group)

    val cardKey = if (fromOverview) "app_card_overview_${app.packageName}" else "app_card_${app.packageName}"
    val iconKey = if (fromOverview) "app_icon_overview_${app.packageName}" else "app_icon_${app.packageName}"
    val titleKey = "app_title_${app.packageName}"

    with(sharedTransitionScope) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .sharedBounds(
                    sharedContentState = rememberSharedContentState(key = cardKey),
                    animatedVisibilityScope = animatedVisibilityScope,
                    boundsTransform = { _, _ -> spring(dampingRatio = 0.80f, stiffness = 360f) },
                ),
            color = c.canvas,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
            ) {
                // Top clean iOS navigation bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(100.dp))
                            .applePressScale(cornerRadius = 100.dp, onClick = onClose),
                        shape = RoundedCornerShape(100.dp),
                        color = c.surface,
                        border = BorderStroke(0.6.dp, c.glassBorder),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = HushIcons.Close,
                                contentDescription = "返回",
                                tint = c.textPrimary,
                                modifier = Modifier.size(15.dp),
                            )
                            Text("返回", style = HushType.caption.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary)
                        }
                    }

                    Text(
                        text = standbyBucketLabel(app.standbyBucket),
                        style = HushType.micro,
                        color = c.textTertiary,
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    // Centered Apple App Identity Hero
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AppIconAvatar(
                            packageName = app.packageName,
                            label = app.label,
                            size = 68.dp,
                            modifier = Modifier.sharedElement(
                                state = rememberSharedContentState(key = iconKey),
                                animatedVisibilityScope = animatedVisibilityScope,
                            ),
                        )
                        Text(
                            text = app.label,
                            style = HushType.sectionTitle.copy(fontSize = 22.sp),
                            color = c.textPrimary,
                            modifier = if (!fromOverview) {
                                Modifier.sharedElement(
                                    state = rememberSharedContentState(key = titleKey),
                                    animatedVisibilityScope = animatedVisibilityScope,
                                )
                            } else Modifier,
                        )
                        Text(
                            text = app.packageName,
                            style = HushType.monoCode,
                            color = c.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .applePressScale(cornerRadius = 8.dp) {
                                    copyTextToClipboard(context, "包名", app.packageName)
                                    onCopiedNotice("已复制包名")
                                }
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }

                    // Clean 3-stat memory bar
                    AppleGroupedCard(contentPadding = PaddingValues(vertical = 16.dp, horizontal = 12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            DetailStatCell("总内存 PSS", formatMemoryKb(app.totalPssKb), Modifier.weight(1f))
                            DetailStatCell("Swap 压缩", formatMemoryKb(app.swapPssKb), Modifier.weight(1f))
                            DetailStatCell(
                                label = "进程状态",
                                value = when {
                                    app.isPlayingAudio -> "${app.processCount} · 音频"
                                    app.isFrozen -> "${app.processCount} · 冻结"
                                    app.running -> "${app.processCount} · 运行"
                                    else -> "未运行"
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    // Instant tactile pill actions (scale 0.96 + spring overshoot)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ApplePillButton(
                            text = if (isBusy) "处理中…" else "立即强杀",
                            onClick = {
                                if (app.group == AppGroup.SOFT_TRIM) {
                                    showSoftTrimKillConfirm = true
                                } else {
                                    onForceStop()
                                }
                            },
                            enabled = isDaemonOnline && !isBusy && !app.isSystemProtected,
                            containerColor = c.redSoft,
                            contentColor = c.red,
                            icon = HushIcons.Bolt,
                            modifier = Modifier.weight(1f),
                        )
                        ApplePillButton(
                            text = "保消息瘦身",
                            onClick = onSoftTrim,
                            enabled = isDaemonOnline && !isBusy,
                            containerColor = c.blueSoft,
                            contentColor = c.blue,
                            icon = HushIcons.Feather,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    // Apple Settings–style Tier Picker
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "治理档位",
                            style = HushType.caption.copy(fontWeight = FontWeight.SemiBold),
                            color = c.textSecondary,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                        AppleGroupedCard {
                            val groups = listOf(
                                AppGroup.FORCE_STOP,
                                AppGroup.SOFT_TRIM,
                                AppGroup.PROTECTED,
                                AppGroup.UNSET,
                            )
                            groups.forEachIndexed { idx, g ->
                                val selected = app.group == g
                                val enabled = !(app.isSystemProtected && g == AppGroup.FORCE_STOP)
                                val dotColor = tierAccentColor(g)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .applePressScale(enabled = enabled, pressedScale = 0.985f, cornerRadius = 12.dp) {
                                            onSelectGroup(g)
                                        }
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            text = tierLabel(g),
                                            style = HushType.cardTitle,
                                            color = if (enabled) c.textPrimary else c.textTertiary,
                                        )
                                        Text(
                                            text = tierDescription(g),
                                            style = HushType.micro,
                                            color = c.textSecondary,
                                        )
                                    }
                                    if (selected) {
                                        Icon(
                                            imageVector = HushIcons.Check,
                                            contentDescription = "已选",
                                            tint = c.blue,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                                if (idx < groups.lastIndex) {
                                    AppleInsetDivider(startIndent = 36.dp)
                                }
                            }
                        }
                    }

                    // Apple Settings–style Reversible Background Constraints
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "高级后台限制（完全可逆）",
                            style = HushType.caption.copy(fontWeight = FontWeight.SemiBold),
                            color = c.textSecondary,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                        if (restrictionBlocked) {
                            AppleGroupedCard(contentPadding = PaddingValues(16.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Icon(HushIcons.ShieldCheck, contentDescription = null, tint = c.green, modifier = Modifier.size(20.dp))
                                    Text(
                                        text = if (app.isSystemProtected) {
                                            "系统核心组件已受硬编码保护，禁止修改后台限制。"
                                        } else {
                                            "当前处于「${tierLabel(app.group)}」，为保障即时推送已自动锁定限制开关。"
                                        },
                                        style = HushType.caption,
                                        color = c.textSecondary,
                                    )
                                }
                            }
                        } else {
                            AppleGroupedCard {
                                DetailSwitchRow(
                                    title = "禁止后台运行",
                                    subtitle = "appops RUN_IN_BACKGROUND",
                                    checked = app.restriction.restrictBackground,
                                    enabled = isDaemonOnline && !isBusy,
                                    onCheckedChange = {
                                        onUpdateRestriction(app.restriction.copy(restrictBackground = it))
                                    },
                                )
                                AppleInsetDivider()
                                DetailSwitchRow(
                                    title = "限制唤醒锁",
                                    subtitle = "appops WAKE_LOCK",
                                    checked = app.restriction.restrictWakeLock,
                                    enabled = isDaemonOnline && !isBusy,
                                    onCheckedChange = {
                                        onUpdateRestriction(app.restriction.copy(restrictWakeLock = it))
                                    },
                                )
                                AppleInsetDivider()
                                DetailSwitchRow(
                                    title = "受限待机桶",
                                    subtitle = "Standby Bucket RESTRICTED",
                                    checked = app.restriction.restrictedBucket,
                                    enabled = isDaemonOnline && !isBusy,
                                    onCheckedChange = {
                                        onUpdateRestriction(app.restriction.copy(restrictedBucket = it))
                                    },
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }

    if (showSoftTrimKillConfirm) {
        AlertDialog(
            onDismissRequest = { showSoftTrimKillConfirm = false },
            containerColor = c.surface,
            title = { Text("确认强杀通讯应用？", style = HushType.sectionTitle, color = c.textPrimary) },
            text = {
                Text(
                    text = "「${app.label}」在「保消息瘦身」档位。彻底强杀会中断实时推送，推荐优先使用瘦身。",
                    style = HushType.body,
                    color = c.textSecondary,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSoftTrimKillConfirm = false
                        onForceStop()
                    },
                ) { Text("强杀", color = c.red, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { showSoftTrimKillConfirm = false }) {
                    Text("取消", color = c.blue)
                }
            },
        )
    }
}

@Composable
private fun DetailStatCell(label: String, value: String, modifier: Modifier = Modifier) {
    val c = HushTheme.colors
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(text = value, style = HushType.compactNumber.copy(fontSize = 16.sp), color = c.textPrimary, maxLines = 1)
        Text(text = label, style = HushType.micro, color = c.textTertiary, maxLines = 1)
    }
}

@Composable
private fun DetailSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
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
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = HushType.cardTitle, color = c.textPrimary)
            Text(text = subtitle, style = HushType.micro, color = c.textTertiary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
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
