package com.aileboeth.hush.design

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.Settings
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.aileboeth.hush.contract.AppGroup
import com.aileboeth.hush.contract.CleanupAction
import com.aileboeth.hush.contract.CleanupTrigger
import com.aileboeth.hush.contract.StandbyBucket
import com.aileboeth.hush.state.HushTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// --- Formatting & Semantic Labels ---

fun formatMemoryKb(kb: Long): String {
    if (kb <= 0L) return "0 MB"
    return formatMemoryMbFloat(kb / 1024f)
}

fun formatMemoryMbFloat(mb: Float): String {
    if (mb <= 0f) return "0 MB"
    return when {
        mb >= 1024f -> String.format(Locale.US, "%.2f GB", mb / 1024f)
        mb >= 100f -> String.format(Locale.US, "%.0f MB", mb)
        else -> String.format(Locale.US, "%.1f MB", mb)
    }
}

fun formatMemoryMb(mb: Int): String = when {
    mb >= 1024 -> String.format(Locale.US, "%.2f GB", mb / 1024.0)
    else -> "$mb MB"
}

fun formatUptime(startedAtMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    if (startedAtMs <= 0L) return "刚刚"
    val diffSec = ((nowMs - startedAtMs) / 1000L).coerceAtLeast(0L)
    val days = diffSec / 86_400L
    val hours = (diffSec % 86_400L) / 3_600L
    val mins = (diffSec % 3_600L) / 60L
    return when {
        days > 0L -> "${days}天${hours}时"
        hours > 0L -> "${hours}时${mins}分"
        mins > 0L -> "${mins}分钟"
        else -> "${diffSec}秒"
    }
}

fun formatClockShort(timestampMs: Long): String {
    if (timestampMs <= 0L) return "--:--"
    return SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(timestampMs))
}

fun formatClockTime(timestampMs: Long): String {
    if (timestampMs <= 0L) return "--:--:--"
    return SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(timestampMs))
}

fun formatRelativeTime(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    if (timestampMs <= 0L) return "刚刚"
    val diffSec = ((nowMs - timestampMs) / 1000L).coerceAtLeast(0L)
    return when {
        diffSec < 60L -> "刚刚"
        diffSec < 3600L -> "${diffSec / 60L} 分钟前"
        diffSec < 86_400L -> "${diffSec / 3600L} 小时前"
        else -> "${diffSec / 86_400L} 天前"
    }
}

fun tierLabel(group: AppGroup): String = when (group) {
    AppGroup.FORCE_STOP -> "强杀"
    AppGroup.SOFT_TRIM -> "保消息瘦身"
    AppGroup.PROTECTED -> "常驻保护"
    AppGroup.UNSET -> "未分类"
}

fun tierShortLabel(group: AppGroup): String = when (group) {
    AppGroup.FORCE_STOP -> "强杀"
    AppGroup.SOFT_TRIM -> "瘦身"
    AppGroup.PROTECTED -> "保护"
    AppGroup.UNSET -> "未分类"
}

fun tierDescription(group: AppGroup): String = when (group) {
    AppGroup.FORCE_STOP -> "切后台超时、息屏或高压时彻底停止，杜绝暗耗"
    AppGroup.SOFT_TRIM -> "绝不强杀、绝不锁后台，温和释放界面缓存并保留推送"
    AppGroup.PROTECTED -> "所有自动清理策略永久跳过，适合密码与核心工具"
    AppGroup.UNSET -> "仅查看实时内存占用，不参与自动清理"
}

@Composable
fun tierAccentColor(group: AppGroup): Color {
    val c = HushTheme.colors
    return when (group) {
        AppGroup.FORCE_STOP -> c.red
        AppGroup.SOFT_TRIM -> c.blue
        AppGroup.PROTECTED -> c.indigo
        AppGroup.UNSET -> c.neutral
    }
}

@Composable
fun tierSoftColor(group: AppGroup): Color {
    val c = HushTheme.colors
    return when (group) {
        AppGroup.FORCE_STOP -> c.redSoft
        AppGroup.SOFT_TRIM -> c.blueSoft
        AppGroup.PROTECTED -> c.indigoSoft
        AppGroup.UNSET -> c.neutralSoft
    }
}

fun standbyBucketLabel(bucket: StandbyBucket): String = when (bucket) {
    StandbyBucket.ACTIVE -> "活跃"
    StandbyBucket.WORKING_SET -> "常用"
    StandbyBucket.FREQUENT -> "较常"
    StandbyBucket.RARE -> "极少"
    StandbyBucket.RESTRICTED -> "受限"
    StandbyBucket.UNKNOWN -> "未采样"
}

fun triggerLabel(trigger: CleanupTrigger): String = when (trigger) {
    CleanupTrigger.MANUAL -> "手动"
    CleanupTrigger.BACKGROUND_TIMEOUT -> "后台超时"
    CleanupTrigger.SCREEN_OFF -> "息屏"
    CleanupTrigger.MEMORY_PRESSURE -> "内存高压"
}

fun actionLabel(action: CleanupAction): String = when (action) {
    CleanupAction.FORCE_STOP -> "彻底停止"
    CleanupAction.SOFT_TRIM -> "保消息瘦身"
    CleanupAction.RESTRICT_CHANGED -> "限制变更"
    CleanupAction.RESTORE_ALL -> "一键还原"
    CleanupAction.STOP_USER -> "停机休眠"
}

// --- Android Platform Helpers ---

fun copyTextToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

fun openDeveloperOptions(context: Context): Boolean {
    val devIntent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return runCatching {
        context.startActivity(devIntent)
        true
    }.getOrElse {
        runCatching {
            val fallback = Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
        }
        false
    }
}

fun openAppNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}

fun openExternalUrl(context: Context, url: String): Boolean = runCatching {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
    true
}.getOrDefault(false)

@Suppress("DEPRECATION")
fun isWifiConnected(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val activeCaps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
    if (activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) return true
    for (network in cm.allNetworks) {
        val caps = cm.getNetworkCapabilities(network) ?: continue
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        ) {
            return true
        }
    }
    return false
}

// --- Apple Minimalist Surfaces & Controls ---

@Composable
fun AppleGroupedCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 22.dp,
    containerColor: Color = HushTheme.colors.surface,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = HushTheme.colors
    val shape = RoundedCornerShape(cornerRadius)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = containerColor,
        border = if (c.isDark) BorderStroke(0.6.dp, c.glassBorder) else null,
        shadowElevation = if (c.isDark) 0.dp else 1.5.dp,
        tonalElevation = 0.dp,
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

@Composable
fun AppleInsetDivider(
    startIndent: Dp = 16.dp,
) {
    HorizontalDivider(
        modifier = Modifier.padding(start = startIndent),
        thickness = 0.6.dp,
        color = HushTheme.colors.separator,
    )
}

@Composable
fun ApplePillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = HushTheme.colors.blue,
    contentColor: Color = HushTheme.colors.onBlue,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    height: Dp = 48.dp,
) {
    val shape = RoundedCornerShape(100.dp)
    Box(
        modifier = modifier
            .height(height)
            .clip(shape)
            .background(if (enabled) containerColor else containerColor.copy(alpha = 0.35f))
            .applePressScale(enabled = enabled, cornerRadius = 100.dp, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                text = text,
                style = HushType.cardTitle,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Sleek iOS Settings–style slider: thin rounded track + elevated white circular thumb,
 * tactile haptic detent ticks, smooth tap & drag, and vertical-scroll-friendly gesture arbitration.
 */
@Composable
fun AppleSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    activeTrackColor: Color = HushTheme.colors.blue,
    inactiveTrackColor: Color = HushTheme.colors.trackBg,
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    val latestOnFinished by rememberUpdatedState(onValueChangeFinished)

    val thumbDiameter = 26.dp
    val thumbPx = with(density) { thumbDiameter.toPx() }
    var widthPx by remember { mutableFloatStateOf(1f) }
    var isDragging by remember { mutableStateOf(false) }

    val start = valueRange.start
    val end = valueRange.endInclusive
    val span = (end - start).coerceAtLeast(1e-6f)
    val clamped = value.coerceIn(start, end)
    val latestClamped by rememberUpdatedState(clamped)
    val rawFraction = ((clamped - start) / span).coerceIn(0f, 1f)

    val animatedFraction by animateFloatAsState(
        targetValue = rawFraction,
        animationSpec = if (isDragging) HushSprings.follow() else HushSprings.fluid(),
        label = "appleSliderFraction",
    )
    val thumbScale by animateFloatAsState(
        targetValue = if (isDragging) 1.08f else 1f,
        animationSpec = HushSprings.overshoot(),
        label = "appleSliderThumbScale",
    )

    fun snapValue(xPx: Float): Float {
        val usable = (widthPx - thumbPx).coerceAtLeast(1f)
        val frac = ((xPx - thumbPx / 2f) / usable).coerceIn(0f, 1f)
        val snappedFrac = if (steps > 0) {
            val intervals = steps + 1
            (frac * intervals).roundToInt().toFloat() / intervals.toFloat()
        } else {
            frac
        }
        return (start + snappedFrac * span).coerceIn(start, end)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.42f }
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(enabled, start, end, steps) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val slop = viewConfiguration.touchSlop
                    var dragging = false
                    var cancelled = false
                    var lastEmitted = latestClamped

                    fun emitAt(x: Float, allowHaptic: Boolean) {
                        val next = snapValue(x)
                        if (abs(next - lastEmitted) > 1e-5f) {
                            if (allowHaptic) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            lastEmitted = next
                            latestOnValueChange(next)
                        }
                    }

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || change.isConsumed) {
                            cancelled = true
                            break
                        }
                        if (!change.pressed) {
                            if (!dragging) {
                                emitAt(change.position.x, allowHaptic = true)
                            }
                            break
                        }
                        val dx = change.position.x - down.position.x
                        val dy = change.position.y - down.position.y
                        if (!dragging) {
                            if (abs(dx) > slop && abs(dx) >= abs(dy)) {
                                dragging = true
                                isDragging = true
                                change.consume()
                                emitAt(change.position.x, allowHaptic = true)
                            } else if (abs(dy) > slop && abs(dy) > abs(dx)) {
                                // Vertical scroll gesture: yield cleanly to parent LazyColumn
                                cancelled = true
                                break
                            }
                        } else {
                            change.consume()
                            emitAt(change.position.x, allowHaptic = true)
                        }
                    }

                    if (isDragging) {
                        isDragging = false
                    }
                    if (!cancelled) {
                        latestOnFinished?.invoke()
                    }
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.CenterStart,
        ) {
            val travelWidth = (maxWidth - thumbDiameter).coerceAtLeast(0.dp)
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
            ) {
                val capRadius = thumbDiameter.toPx() / 2f
                val centerY = size.height / 2f
                val startX = capRadius
                val endX = (size.width - capRadius).coerceAtLeast(startX)
                val activeEndX = startX + (endX - startX) * animatedFraction
                drawLine(
                    color = inactiveTrackColor,
                    start = Offset(startX, centerY),
                    end = Offset(endX, centerY),
                    strokeWidth = size.height,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = activeTrackColor,
                    start = Offset(startX, centerY),
                    end = Offset(activeEndX, centerY),
                    strokeWidth = size.height,
                    cap = StrokeCap.Round,
                )
            }

            Box(
                modifier = Modifier
                    .offset(x = travelWidth * animatedFraction)
                    .size(thumbDiameter)
                    .graphicsLayer {
                        scaleX = thumbScale
                        scaleY = thumbScale
                    }
                    .shadow(
                        elevation = if (isDragging) 6.dp else 4.dp,
                        shape = CircleShape,
                        ambientColor = Color.Black.copy(alpha = 0.16f),
                        spotColor = Color.Black.copy(alpha = 0.28f),
                    )
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(0.6.dp, Color.Black.copy(alpha = 0.08f), CircleShape),
            )
        }
    }
}

/** Slim animated Apple capsule progress bar (ideal for RAM / ZRAM gauges). */
@Composable
fun AppleMeterBar(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 5.dp,
    trackColor: Color = HushTheme.colors.trackBg,
) {
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = HushSprings.fluid(),
        label = "appleMeterBar",
    )
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(trackColor),
    ) {
        if (animated > 0.002f) {
            Box(
                modifier = Modifier
                    .width((maxWidth * animated).coerceAtLeast(height))
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

@Composable
fun BreathingDot(
    color: Color,
    isPulsing: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 8.dp,
) {
    val transition = rememberInfiniteTransition(label = "appleDot")
    val pulseScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (isPulsing) 2.3f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulseScale",
    )
    val pulseAlpha by transition.animateFloat(
        initialValue = if (isPulsing) 0.42f else 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulseAlpha",
    )

    Box(
        modifier = modifier.size(size * 2.1f),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r = size.toPx() / 2f
            if (isPulsing) {
                drawCircle(color = color.copy(alpha = pulseAlpha), radius = r * pulseScale)
            }
            drawCircle(color = color, radius = r)
        }
    }
}

// --- Async Cached App Squircle Icon ---

private object AppIconBitmapCache {
    private val cache = LruCache<String, ImageBitmap>(96)
    fun get(pkg: String): ImageBitmap? = cache.get(pkg)
    fun put(pkg: String, bmp: ImageBitmap) {
        cache.put(pkg, bmp)
    }
}

@Composable
fun AppIconAvatar(
    packageName: String,
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
) {
    val context = LocalContext.current
    val c = HushTheme.colors

    val bitmap by produceState<ImageBitmap?>(
        initialValue = AppIconBitmapCache.get(packageName),
        key1 = packageName,
    ) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val drawable = context.packageManager.getApplicationIcon(packageName)
                    val bmp = drawable.toBitmap(width = 112, height = 112).asImageBitmap()
                    AppIconBitmapCache.put(packageName, bmp)
                    bmp
                }.getOrNull()
            }
        }
    }

    val shape = RoundedCornerShape(size * 0.24f)
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!,
            contentDescription = label,
            modifier = modifier
                .size(size)
                .clip(shape)
                .border(0.5.dp, c.separator, shape),
        )
    } else {
        val fallbackColors = listOf(c.blue, c.indigo, c.purple, c.cyan, c.green, c.orange)
        val tint = fallbackColors[Math.floorMod(packageName.hashCode(), fallbackColors.size)]
        val initial = label.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "A"
        Box(
            modifier = modifier
                .size(size)
                .clip(shape)
                .background(tint.copy(alpha = if (c.isDark) 0.22f else 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = initial, style = HushType.sectionTitle, color = tint)
        }
    }
}

// --- Apple Liquid Glass Floating 3-Tab Pill Dock with Liquid-Stretching Indicator ---

@Composable
fun AppleFloatingTabBar(
    activeTab: HushTab,
    onSelectTab: (HushTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = HushTheme.colors
    val tabs = HushTab.entries
    val selectedIndex = tabs.indexOf(activeTab).coerceAtLeast(0)

    val pos by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = HushSprings.fluid(),
        label = "tabLiquidPos",
    )

    Box(
        modifier = modifier
            .width(268.dp)
            .height(60.dp)
            .shadow(
                elevation = if (c.isDark) 20.dp else 18.dp,
                shape = CircleShape,
                ambientColor = Color.Black.copy(alpha = if (c.isDark) 0.45f else 0.10f),
                spotColor = Color.Black.copy(alpha = if (c.isDark) 0.62f else 0.16f),
            )
            .clip(CircleShape)
            .background(c.dockBackground)
            .border(0.75.dp, c.dockBorder, CircleShape),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(5.dp),
        ) {
            val slotWidth = maxWidth / tabs.size
            // Liquid stretch peak mid-travel between tabs, relaxing back to exact slot width at rest
            val travel = abs(pos - pos.roundToInt())
            val stretch = 24.dp * (travel * 2f).coerceIn(0f, 1f)
            val pillWidth = (slotWidth + stretch).coerceAtMost(maxWidth)
            val pillLeft = (slotWidth * pos - stretch / 2f)
                .coerceIn(0.dp, (maxWidth - pillWidth).coerceAtLeast(0.dp))

            // Liquid-stretching translucent indicator pill
            Box(
                modifier = Modifier
                    .offset(x = pillLeft)
                    .width(pillWidth)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(c.blueSoft)
                    .border(
                        width = 0.5.dp,
                        color = c.blue.copy(alpha = if (c.isDark) 0.22f else 0.10f),
                        shape = CircleShape,
                    ),
            )

            Row(modifier = Modifier.fillMaxSize()) {
                tabs.forEach { tab ->
                    val selected = tab == activeTab
                    val tint by animateColorAsState(
                        targetValue = if (selected) c.blue else c.textSecondary,
                        animationSpec = tween(180),
                        label = "tabTint",
                    )
                    val icon = when (tab) {
                        HushTab.OVERVIEW -> HushIcons.Pulse
                        HushTab.APPS -> HushIcons.Layers
                        HushTab.POLICIES -> HushIcons.Sliders
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .applePressScale(
                                pressedScale = 0.92f,
                                cornerRadius = 100.dp,
                                shadowAlpha = 0f,
                            ) {
                                onSelectTab(tab)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = tab.label,
                                tint = tint,
                                modifier = Modifier.size(19.dp),
                            )
                            Text(
                                text = tab.label,
                                style = HushType.micro.copy(
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                ),
                                color = tint,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}
