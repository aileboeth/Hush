package com.aileboeth.hush.feature.apps

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aileboeth.hush.AppleConfirmDialog
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
import com.aileboeth.hush.design.formatRelativeTime
import com.aileboeth.hush.design.standbyBucketLabel
import com.aileboeth.hush.design.tierAccentColor
import com.aileboeth.hush.design.tierDescription
import com.aileboeth.hush.design.tierLabel
import kotlinx.coroutines.launch

/**
 * Full-bleed Apple Detail Page connected to list cards via Compose Shared Element Transition
 * (`sharedBounds` container scaling + `sharedElement` icon & title morph), with smooth
 * pull-down-to-dismiss gesture via `NestedScrollConnection`.
 */
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalLayoutApi::class)
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
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val closeLatest by rememberUpdatedState(onClose)

    var showSoftTrimKillConfirm by remember { mutableStateOf(false) }
    var pull by remember { mutableFloatStateOf(0f) }
    var dismissed by remember { mutableStateOf(false) }
    val restrictionBlocked = SafetyGuardrails.isRestrictionBlocked(app.packageName, app.group)

    val cardKey = if (fromOverview) "app_card_overview_${app.packageName}" else "app_card_${app.packageName}"
    val iconKey = if (fromOverview) "app_icon_overview_${app.packageName}" else "app_icon_${app.packageName}"
    val titleKey = "app_title_${app.packageName}"

    fun triggerDismiss() {
        if (!dismissed) {
            dismissed = true
            closeLatest()
        }
    }

    fun reboundPull() {
        val start = pull
        if (start <= 0.001f) {
            pull = 0f
            return
        }
        scope.launch {
            Animatable(start).animateTo(
                targetValue = 0f,
                animationSpec = spring(dampingRatio = 0.72f, stiffness = 420f),
            ) { pull = value }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val containerHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)

        val pullConnection = remember(containerHeightPx) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (available.y < 0f && pull > 0f) {
                        val next = (pull + available.y / containerHeightPx * 1.45f).coerceAtLeast(0f)
                        val consumedY = (next - pull) * containerHeightPx / 1.45f
                        pull = next
                        return Offset(0f, consumedY)
                    }
                    return Offset.Zero
                }

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (available.y > 0f && source == NestedScrollSource.UserInput && !dismissed) {
                        val prev = pull
                        val next = (pull + available.y / containerHeightPx * 1.45f).coerceIn(0f, 1f)
                        pull = next
                        if (prev < PULL_DISMISS_THRESHOLD && next >= PULL_DISMISS_THRESHOLD) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        return Offset(0f, available.y)
                    }
                    return Offset.Zero
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (pull <= 0f) return Velocity.Zero
                    if (pull >= PULL_DISMISS_THRESHOLD || available.y > PULL_FLING_VELOCITY_PX) {
                        triggerDismiss()
                    } else {
                        reboundPull()
                    }
                    return available
                }
            }
        }

        // Subtle dark backdrop revealed as the user pulls the detail view downward
        if (pull > 0.002f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = (0.28f * (1f - pull * 0.65f)).coerceIn(0f, 0.28f))),
            )
        }

        with(sharedTransitionScope) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .sharedBounds(
                        sharedContentState = rememberSharedContentState(key = cardKey),
                        animatedVisibilityScope = animatedVisibilityScope,
                        boundsTransform = { _, _ -> spring(dampingRatio = 0.80f, stiffness = 360f) },
                    )
                    .graphicsLayer {
                        val p = pull.coerceIn(0f, 1f)
                        val scale = 1f - p * 0.12f
                        scaleX = scale
                        scaleY = scale
                        translationY = p * 64.dp.toPx()
                        transformOrigin = TransformOrigin(0.5f, 0.25f)
                        val cornerPx = p * 34.dp.toPx()
                        shape = RoundedCornerShape(cornerPx)
                        clip = p > 0.002f
                        shadowElevation = if (p > 0.002f) 20.dp.toPx() * (1f - p * 0.4f) else 0f
                    }
                    .nestedScroll(pullConnection),
                color = c.canvas,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding(),
                ) {
                    // Draggable top handle + clean iOS navigation bar
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(containerHeightPx) {
                                val tracker = VelocityTracker()
                                detectVerticalDragGestures(
                                    onDragStart = { tracker.resetTracking() },
                                    onVerticalDrag = { change, dragAmount ->
                                        if (dragAmount > 0f || pull > 0f) {
                                            change.consume()
                                            tracker.addPosition(change.uptimeMillis, change.position)
                                            val prev = pull
                                            val next = (pull + dragAmount / containerHeightPx * 1.45f).coerceIn(0f, 1f)
                                            pull = next
                                            if (prev < PULL_DISMISS_THRESHOLD && next >= PULL_DISMISS_THRESHOLD) {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                        }
                                    },
                                    onDragEnd = {
                                        val vy = tracker.calculateVelocity().y
                                        if (pull >= PULL_DISMISS_THRESHOLD || vy > PULL_FLING_VELOCITY_PX) {
                                            triggerDismiss()
                                        } else {
                                            reboundPull()
                                        }
                                    },
                                    onDragCancel = { reboundPull() },
                                )
                            },
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp, bottom = 2.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(36.dp)
                                    .height(4.dp)
                                    .clip(CircleShape)
                                    .background(c.textTertiary.copy(alpha = 0.28f)),
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
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
                                shadowElevation = if (c.isDark) 0.dp else 1.dp,
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(
                                        imageVector = HushIcons.Close,
                                        contentDescription = "返回",
                                        tint = c.textPrimary,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Text(
                                        text = "返回",
                                        style = HushType.caption.copy(fontWeight = FontWeight.SemiBold),
                                        color = c.textPrimary,
                                    )
                                }
                            }

                            Text(
                                text = "待机桶 · ${standbyBucketLabel(app.standbyBucket)}",
                                style = HushType.micro,
                                color = c.textTertiary,
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        // Centered Apple App Identity Hero
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            AppIconAvatar(
                                packageName = app.packageName,
                                label = app.label,
                                size = 66.dp,
                                modifier = Modifier.sharedElement(
                                    state = rememberSharedContentState(key = iconKey),
                                    animatedVisibilityScope = animatedVisibilityScope,
                                ),
                            )
                            Text(
                                text = app.label,
                                style = HushType.sectionTitle.copy(fontSize = 21.sp),
                                color = c.textPrimary,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = if (!fromOverview) {
                                    Modifier.sharedElement(
                                        state = rememberSharedContentState(key = titleKey),
                                        animatedVisibilityScope = animatedVisibilityScope,
                                    )
                                } else {
                                    Modifier
                                },
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

                            val statusChips = buildDetailStatusChips(app)
                            if (statusChips.isNotEmpty()) {
                                FlowRow(
                                    modifier = Modifier.padding(top = 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    statusChips.forEach { (label, fg, bg) ->
                                        Text(
                                            text = label,
                                            style = HushType.micro.copy(fontWeight = FontWeight.SemiBold),
                                            color = fg,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(100.dp))
                                                .background(bg)
                                                .padding(horizontal = 9.dp, vertical = 3.dp),
                                        )
                                    }
                                }
                            }
                        }

                        // Clean 3-stat memory bar with refined vertical hairlines
                        AppleGroupedCard(contentPadding = PaddingValues(vertical = 14.dp, horizontal = 10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                DetailStatCell(
                                    label = "总内存 PSS",
                                    value = if (app.totalPssKb > 0L) formatMemoryKb(app.totalPssKb) else "—",
                                    modifier = Modifier.weight(1f),
                                )
                                Box(
                                    modifier = Modifier
                                        .width(0.6.dp)
                                        .height(26.dp)
                                        .background(c.separator),
                                )
                                DetailStatCell(
                                    label = "Swap 压缩",
                                    value = if (app.swapPssKb > 0L) formatMemoryKb(app.swapPssKb) else "—",
                                    modifier = Modifier.weight(1f),
                                )
                                Box(
                                    modifier = Modifier
                                        .width(0.6.dp)
                                        .height(26.dp)
                                        .background(c.separator),
                                )
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
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                                    height = 46.dp,
                                    modifier = Modifier.weight(1f),
                                )
                                ApplePillButton(
                                    text = "保消息瘦身",
                                    onClick = onSoftTrim,
                                    enabled = isDaemonOnline && !isBusy,
                                    containerColor = c.blueSoft,
                                    contentColor = c.blue,
                                    icon = HushIcons.Feather,
                                    height = 46.dp,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (!isDaemonOnline) {
                                Text(
                                    text = "守护进程未运行，激活后即可执行立即强杀或瘦身；现在仍可预先设置档位。",
                                    style = HushType.micro,
                                    color = c.textTertiary,
                                    modifier = Modifier.padding(horizontal = 8.dp),
                                )
                            }
                        }

                        // Apple Settings–style Descriptive 4-Tier Picker
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
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(if (enabled) dotColor else dotColor.copy(alpha = 0.35f)),
                                        )
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(2.dp),
                                        ) {
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
                                AppleGroupedCard {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Icon(
                                            imageVector = HushIcons.ShieldCheck,
                                            contentDescription = null,
                                            tint = c.green,
                                            modifier = Modifier.size(20.dp),
                                        )
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
                                    if (app.restriction.hasAnyRestriction && isDaemonOnline && !isBusy) {
                                        AppleInsetDivider()
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .applePressScale(pressedScale = 0.985f, cornerRadius = 12.dp) {
                                                    onUpdateRestriction(AppRestriction())
                                                }
                                                .padding(horizontal = 16.dp, vertical = 13.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = "解除现有限制",
                                                style = HushType.cardTitle,
                                                color = c.blue,
                                            )
                                            Icon(
                                                imageVector = HushIcons.ChevronRight,
                                                contentDescription = null,
                                                tint = c.textTertiary,
                                                modifier = Modifier.size(15.dp),
                                            )
                                        }
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

                        // Compact runtime metadata card
                        if (app.lastForegroundMs > 0L || app.isDualSpace) {
                            AppleGroupedCard {
                                if (app.lastForegroundMs > 0L) {
                                    DetailMetaRow(
                                        label = "上次前台使用",
                                        value = formatRelativeTime(app.lastForegroundMs),
                                    )
                                }
                                if (app.lastForegroundMs > 0L && app.isDualSpace) {
                                    AppleInsetDivider()
                                }
                                if (app.isDualSpace) {
                                    DetailMetaRow(
                                        label = "所属用户空间",
                                        value = "双开空间 · UID ${app.userId}",
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(18.dp))
                    }
                }
            }
        }
    }

    if (showSoftTrimKillConfirm) {
        AppleConfirmDialog(
            title = "确认强杀通讯应用？",
            message = "「${app.label}」在「保消息瘦身」档位。彻底强杀会中断实时推送，推荐优先使用瘦身。",
            confirmText = "强杀",
            destructive = true,
            onDismiss = { showSoftTrimKillConfirm = false },
            onConfirm = {
                showSoftTrimKillConfirm = false
                onForceStop()
            },
        )
    }
}

private const val PULL_DISMISS_THRESHOLD = 0.16f
private const val PULL_FLING_VELOCITY_PX = 1400f

@Composable
private fun buildDetailStatusChips(app: ManagedAppInfo): List<Triple<String, Color, Color>> {
    val c = HushTheme.colors
    return buildList {
        if (app.isPlayingAudio) add(Triple("音频播放中", c.purple, c.purple.copy(alpha = if (c.isDark) 0.24f else 0.12f)))
        if (app.isFrozen) add(Triple("已冻结", c.indigo, c.indigoSoft))
        if (app.running && !app.isFrozen && !app.isPlayingAudio) add(Triple("运行中", c.green, c.greenSoft))
        if (app.restriction.hasAnyRestriction) add(Triple("已限后台", c.orange, c.orangeSoft))
        if (app.isDualSpace) add(Triple("双开", c.blue, c.blueSoft))
        if (app.isSystemProtected) add(Triple("系统保护", c.textSecondary, c.neutralSoft))
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
        Text(
            text = value,
            style = HushType.compactNumber.copy(fontSize = 16.sp),
            color = c.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(text = label, style = HushType.micro, color = c.textTertiary, maxLines = 1)
    }
}

@Composable
private fun DetailMetaRow(label: String, value: String) {
    val c = HushTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = HushType.cardTitle, color = c.textPrimary)
        Text(text = value, style = HushType.caption, color = c.textSecondary)
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
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = HushType.cardTitle,
                color = if (enabled) c.textPrimary else c.textSecondary,
            )
            Text(text = subtitle, style = HushType.micro, color = c.textTertiary)
        }
        IosToggleSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

/**
 * Crisp iOS-proportioned toggle switch (51dp x 31dp, elevated white knob with subtle press stretch).
 */
@Composable
private fun IosToggleSwitch(
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
        label = "iosSwitchTrack",
    )
    val thumbFraction by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 460f),
        label = "iosSwitchPos",
    )
    val stretch by animateDpAsState(
        targetValue = if (pressed && enabled) 6.dp else 0.dp,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 520f),
        label = "iosSwitchStretch",
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
