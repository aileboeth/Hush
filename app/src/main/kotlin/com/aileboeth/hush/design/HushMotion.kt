package com.aileboeth.hush.design

import android.graphics.BlurMaskFilter
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.aileboeth.hush.state.MemorySamplePoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Unified Apple-calibrated spring vocabulary shared across surfaces, capsules, sheets, and controls.
 */
object HushSprings {
    /** Heavily damped fluid morph with zero wobble (capsules, sheets, liquid tab indicator). */
    fun <T> fluid(): SpringSpec<T> = spring(dampingRatio = 0.86f, stiffness = 320f)

    /** High-stiffness critically damped finger tracking (3D tilt, active slider drag). */
    fun <T> follow(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 900f)

    /** Soft organic settling on release (3D tilt recovery, pull-down rebound). */
    fun <T> release(): SpringSpec<T> = spring(dampingRatio = 0.56f, stiffness = 270f)

    /** Tactile button release: crisp overshoot past 1.0f before settling. */
    fun <T> overshoot(): SpringSpec<T> = spring(dampingRatio = 0.44f, stiffness = 520f)

    /** Gentle no-bounce settle for secondary transitions. */
    fun <T> gentle(): SpringSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )
}

/**
 * True iOS asymptotic rubber-band curve: resistance grows smoothly the further you pull past the detent.
 */
fun appleRubberBand(overscroll: Float, dimension: Float, resistance: Float = 0.55f): Float {
    if (dimension <= 0f) return 0f
    val sign = if (overscroll < 0f) -1f else 1f
    val x = abs(overscroll)
    return sign * (1f - 1f / (x * resistance / dimension + 1f)) * dimension
}

private fun lerpFloat(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/**
 * True 4-edge inner shadow rendered with `BlurMaskFilter` clipped cleanly to [shape].
 * Reads [alpha] strictly inside the draw phase so press depth animations trigger zero recompositions.
 */
fun Modifier.appleInnerShadow(
    shape: Shape,
    color: Color = Color.Black,
    blur: Dp = 10.dp,
    offsetY: Dp = 2.dp,
    alpha: () -> Float,
): Modifier = drawWithContent {
    drawContent()
    val a = alpha()
    if (a <= 0.003f) return@drawWithContent
    val blurPx = blur.toPx().coerceAtLeast(0.5f)
    val path = Path().apply {
        addOutline(shape.createOutline(size, layoutDirection, this@drawWithContent))
    }
    drawIntoCanvas { canvas ->
        val paint = Paint()
        paint.asFrameworkPaint().apply {
            isAntiAlias = true
            this.color = color.copy(alpha = a.coerceIn(0f, 1f)).toArgb()
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = blurPx * 1.6f
            maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.save()
        canvas.clipPath(path)
        canvas.translate(0f, offsetY.toPx())
        canvas.drawPath(path, paint)
        canvas.restore()
    }
}

/**
 * 1. Physical tactile button press:
 *    - Compresses to [pressedScale] (`0.96f`) with true 4-edge `BlurMaskFilter` inner shadow.
 *    - Joins `pressJob` on release so even quick taps visibly compress before rebounding with
 *      crisp Spring Overshoot (`dampingRatio = 0.44f`).
 */
fun Modifier.applePressScale(
    enabled: Boolean = true,
    pressedScale: Float = 0.96f,
    cornerRadius: Dp = 18.dp,
    shadowAlpha: Float? = null,
    haptic: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val scale = remember { Animatable(1f) }
    val depth = remember { Animatable(0f) }
    val hapticFeedback = LocalHapticFeedback.current
    val isDark = HushTheme.colors.isDark
    val resolvedShadowAlpha = shadowAlpha ?: if (isDark) 0.28f else 0.16f
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }

    LaunchedEffect(interactionSource) {
        var pressJob: Job? = null
        var releaseJob: Job? = null
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    releaseJob?.cancel()
                    pressJob?.cancel()
                    pressJob = launch {
                        launch { depth.animateTo(1f, tween(90)) }
                        scale.animateTo(pressedScale, spring(dampingRatio = 1f, stiffness = 1500f))
                    }
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    releaseJob?.cancel()
                    val activePress = pressJob
                    releaseJob = launch {
                        activePress?.join()
                        launch { depth.animateTo(0f, tween(260)) }
                        scale.animateTo(1f, HushSprings.overshoot())
                    }
                }
            }
        }
    }

    LaunchedEffect(enabled) {
        if (!enabled) {
            launch { depth.animateTo(0f, tween(160)) }
            launch { scale.animateTo(1f, HushSprings.gentle()) }
        }
    }

    this
        .graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
        .appleInnerShadow(shape = shape) { depth.value * resolvedShadowAlpha }
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClick = {
                if (haptic) {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
                onClick()
            },
        )
}

/**
 * 2. Hero Luminous Card combining:
 *    - 3D perspective tilt observing `PointerEventPass.Final`, following smoothly on a
 *      high-stiffness spring, and cancelling cleanly whenever `change.isConsumed` (zero LazyColumn fight!)
 *    - Touch-following radial specular reflection sheen gliding across the surface
 *    - Properly clipped rotating Apple Intelligence `SweepGradient` border (`1.5.dp` inset surface)
 *    - 3 soft squashed radial-gradient ambient glow blobs underneath (zero RenderEffect frame drops)
 */
@Composable
fun AppleLuminousTiltCard(
    isActive: Boolean,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 28.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = HushTheme.colors
    val scope = rememberCoroutineScope()

    val rotX = remember { Animatable(0f) }
    val rotY = remember { Animatable(0f) }
    var sheenCenter by remember { mutableStateOf(Offset.Unspecified) }
    val sheenAlpha = remember { Animatable(0f) }
    val activeStrength = remember { Animatable(if (isActive) 1f else 0f) }

    LaunchedEffect(isActive) {
        activeStrength.animateTo(if (isActive) 1f else 0f, tween(650))
    }

    val infinite = rememberInfiniteTransition(label = "luminousHero")
    val conicAngle by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 7800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "conicAngle",
    )
    val glowBreath by infinite.animateFloat(
        initialValue = 0.68f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glowBreath",
    )

    val borderColors = remember(c.blue, c.indigo, c.purple, c.cyan, c.isDark) {
        val hi = if (c.isDark) 0.86f else 0.72f
        val mid = if (c.isDark) 0.70f else 0.56f
        listOf(
            c.blue.copy(alpha = hi),
            c.indigo.copy(alpha = mid),
            c.purple.copy(alpha = hi),
            c.cyan.copy(alpha = hi),
            c.blue.copy(alpha = hi),
        )
    }
    val sweepBrush = remember(borderColors) { Brush.sweepGradient(colors = borderColors) }
    val backlightColors = remember(c.blue, c.purple, c.cyan) { listOf(c.blue, c.purple, c.cyan) }

    val borderWidth = 1.5.dp
    val outerShape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    val innerShape = remember(cornerRadius) {
        RoundedCornerShape((cornerRadius - borderWidth).coerceAtLeast(0.dp))
    }

    Box(modifier = modifier.fillMaxWidth()) {
        // 3 soft squashed radial-gradient ambient glow blobs underneath (zero RenderEffect / zero lag)
        Box(
            modifier = Modifier
                .matchParentSize()
                .drawBehind {
                    val s = activeStrength.value
                    if (s < 0.01f) return@drawBehind
                    val alpha = (if (c.isDark) 0.28f else 0.20f) * s * glowBreath
                    val cy = size.height - 6.dp.toPx()
                    val radius = size.width * (0.35f + 0.04f * glowBreath)
                    for (i in backlightColors.indices) {
                        val cx = size.width * (0.20f + 0.30f * i)
                        val center = Offset(cx, cy)
                        val blobColor = backlightColors[i]
                        scale(scaleX = 1.05f, scaleY = 0.44f, pivot = center) {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        blobColor.copy(alpha = alpha),
                                        blobColor.copy(alpha = alpha * 0.32f),
                                        Color.Transparent,
                                    ),
                                    center = center,
                                    radius = radius,
                                ),
                                radius = radius,
                                center = center,
                            )
                        }
                    }
                },
        )

        // Clipped outer luminous border container + 3D tilt + inset card surface
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
                        fun aim(pos: Offset) {
                            val w = size.width.coerceAtLeast(1).toFloat()
                            val h = size.height.coerceAtLeast(1).toFloat()
                            val nx = ((pos.x / w).coerceIn(0f, 1f) * 2f) - 1f
                            val ny = ((pos.y / h).coerceIn(0f, 1f) * 2f) - 1f
                            sheenCenter = pos
                            scope.launch { rotY.animateTo(nx * 5.2f, HushSprings.follow()) }
                            scope.launch { rotX.animateTo(-ny * 5.2f, HushSprings.follow()) }
                        }
                        aim(down.position)
                        scope.launch { sheenAlpha.animateTo(1f, tween(150)) }
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Final)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed || change.isConsumed) break
                            aim(change.position)
                        }
                        scope.launch { rotX.animateTo(0f, HushSprings.release()) }
                        scope.launch { rotY.animateTo(0f, HushSprings.release()) }
                        scope.launch { sheenAlpha.animateTo(0f, tween(420)) }
                    }
                }
                .graphicsLayer {
                    rotationX = rotX.value
                    rotationY = rotY.value
                    cameraDistance = 16f * density
                    shape = outerShape
                    clip = true
                    shadowElevation = if (c.isDark) 0f else 6.dp.toPx()
                    ambientShadowColor = Color.Black.copy(alpha = 0.08f)
                    spotShadowColor = Color.Black.copy(alpha = 0.14f)
                }
                .drawBehind {
                    // Clean resting border base
                    drawRect(c.surface)
                    drawRect(if (c.isDark) c.glassBorder else c.separator.copy(alpha = 0.65f))
                    // Rotating SweepGradient circle clipped cleanly to outerShape -> 1.5dp luminous ring!
                    val s = activeStrength.value
                    if (s > 0.003f) {
                        rotate(degrees = conicAngle) {
                            drawCircle(
                                brush = sweepBrush,
                                radius = size.maxDimension,
                                alpha = s,
                            )
                        }
                    }
                }
                .drawWithContent {
                    drawContent()
                    // Touch-following radial specular reflection sheen
                    val g = sheenAlpha.value
                    if (g > 0.005f && sheenCenter != Offset.Unspecified) {
                        drawRect(
                            brush = Brush.radialGradient(
                                0.0f to Color.White.copy(alpha = (if (c.isDark) 0.16f else 0.36f) * g),
                                0.45f to c.blue.copy(alpha = 0.07f * g),
                                1.0f to Color.Transparent,
                                center = sheenCenter,
                                radius = size.maxDimension * 0.72f,
                            ),
                        )
                    }
                }
                .padding(borderWidth),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(innerShape)
                    .background(c.surface)
                    .padding(22.dp),
                content = content,
            )
        }
    }
}

/**
 * 3. Continuous 10-face mechanical rolling Odometer counter (`0..9`), right-aligned via `key(n - i)`
 *    so units stay rock-stable when digit count changes.
 */
@Composable
fun OdometerText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val digitStyle = remember(style) {
        if (style.fontFeatureSettings.isNullOrBlank()) {
            style.copy(fontFeatureSettings = "tnum")
        } else {
            style
        }
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val n = text.length
        text.forEachIndexed { i, ch ->
            key(n - i) {
                if (ch.isDigit()) {
                    RollingDigitWheel(
                        digit = ch - '0',
                        style = digitStyle,
                        color = color,
                    )
                } else {
                    Text(
                        text = ch.toString(),
                        style = style,
                        color = color,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun RollingDigitWheel(
    digit: Int,
    style: TextStyle,
    color: Color,
) {
    val pos = remember { Animatable(digit.toFloat()) }
    LaunchedEffect(digit) {
        pos.animateTo(
            targetValue = digit.toFloat(),
            animationSpec = spring(dampingRatio = 0.80f, stiffness = 200f),
        )
    }
    Box(
        modifier = Modifier.clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        // Reserve stable single-digit tabular width & line height
        Text(
            text = "0",
            style = style,
            color = Color.Transparent,
            maxLines = 1,
            softWrap = false,
        )
        // Continuous 10-face wheel sharing one origin; each face translates by its distance from `pos`
        for (face in 0..9) {
            Text(
                text = face.toString(),
                style = style,
                color = color,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.graphicsLayer {
                    val dist = face - pos.value
                    translationY = dist * size.height
                    alpha = (1f - abs(dist) * 0.88f).coerceIn(0f, 1f)
                },
            )
        }
    }
}

/**
 * 4. Interactive Apple Health–style Memory Trend Line Chart with horizontal finger scrubbing,
 *    magnetic snap to sampled data points, and subtle haptic ticks driving the top Odometer counter.
 */
@Composable
fun AppleScrubMemoryChart(
    samples: List<MemorySamplePoint>,
    selectedIndex: Int?,
    onSelectIndex: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    chartHeight: Dp = 108.dp,
) {
    val c = HushTheme.colors
    val haptic = LocalHapticFeedback.current
    var widthPx by remember { mutableFloatStateOf(1f) }
    var lastHapticIndex by remember { mutableIntStateOf(-1) }

    val activeIdx = selectedIndex ?: (samples.lastIndex).coerceAtLeast(0)
    val animatedSnapX by animateFloatAsState(
        targetValue = if (samples.size <= 1) 1f else activeIdx.toFloat() / (samples.size - 1).toFloat(),
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 480f),
        label = "magneticSnapX",
    )

    fun pickNearest(x: Float) {
        if (samples.size <= 1 || widthPx <= 1f) return
        val pad = 16f
        val usable = (widthPx - pad * 2f).coerceAtLeast(1f)
        val frac = ((x - pad) / usable).coerceIn(0f, 1f)
        val nearest = (frac * (samples.size - 1)).roundToInt().coerceIn(0, samples.lastIndex)
        if (nearest != lastHapticIndex) {
            lastHapticIndex = nearest
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        onSelectIndex(nearest)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(chartHeight)
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(samples.size) {
                detectHorizontalDragGestures(
                    onDragStart = { offset -> pickNearest(offset.x) },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        pickNearest(change.position.x)
                    },
                    onDragEnd = {
                        lastHapticIndex = -1
                        onSelectIndex(null)
                    },
                    onDragCancel = {
                        lastHapticIndex = -1
                        onSelectIndex(null)
                    },
                )
            }
            .pointerInput(samples.size) {
                detectTapGestures(
                    onPress = { offset ->
                        pickNearest(offset.x)
                        tryAwaitRelease()
                        lastHapticIndex = -1
                        onSelectIndex(null)
                    },
                )
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val padX = 10.dp.toPx()
            val padTop = 14.dp.toPx()
            val padBottom = 14.dp.toPx()
            val usableW = (size.width - padX * 2f).coerceAtLeast(1f)
            val usableH = (size.height - padTop - padBottom).coerceAtLeast(1f)

            // Whisper-subtle horizontal reference guidelines (Apple Health style)
            for (i in 0..2) {
                val y = padTop + usableH * (i / 2f)
                drawLine(
                    color = c.separator,
                    start = Offset(padX, y),
                    end = Offset(size.width - padX, y),
                    strokeWidth = 0.8.dp.toPx(),
                )
            }

            if (samples.size < 2) return@Canvas

            val values = samples.map { it.availableMb }
            val minV = (values.minOrNull() ?: 0f)
            val maxV = (values.maxOrNull() ?: 1f)
            val span = (maxV - minV).coerceAtLeast(180f)
            val floorV = (minV - span * 0.25f).coerceAtLeast(0f)
            val ceilV = maxV + span * 0.25f
            val range = (ceilV - floorV).coerceAtLeast(1f)

            val points = samples.mapIndexed { i, pt ->
                val x = padX + usableW * (i.toFloat() / (samples.size - 1).toFloat())
                val normY = ((pt.availableMb - floorV) / range).coerceIn(0f, 1f)
                val y = padTop + usableH * (1f - normY)
                Offset(x, y)
            }

            // Smooth cubic Bezier path
            val linePath = Path().apply {
                moveTo(points.first().x, points.first().y)
                for (i in 0 until points.lastIndex) {
                    val p0 = points[i]
                    val p1 = points[i + 1]
                    val midX = (p0.x + p1.x) / 2f
                    cubicTo(midX, p0.y, midX, p1.y, p1.x, p1.y)
                }
            }

            val fillPath = Path().apply {
                addPath(linePath)
                lineTo(points.last().x, size.height - padBottom)
                lineTo(points.first().x, size.height - padBottom)
                close()
            }

            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        c.blue.copy(alpha = if (c.isDark) 0.28f else 0.18f),
                        c.blue.copy(alpha = 0.0f),
                    ),
                    startY = padTop,
                    endY = size.height - padBottom,
                ),
            )

            drawPath(
                path = linePath,
                brush = Brush.horizontalGradient(
                    colors = listOf(c.cyan, c.blue, c.indigo),
                ),
                style = Stroke(
                    width = 2.6.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )

            // Subtle magnetic anchor dots along curve
            points.forEachIndexed { idx, pt ->
                val isSelected = selectedIndex == idx
                if (selectedIndex != null && !isSelected) {
                    drawCircle(
                        color = c.blue.copy(alpha = 0.25f),
                        radius = 2.2.dp.toPx(),
                        center = pt,
                    )
                }
            }

            // Active / Scrubbed magnetically snapped indicator
            val snappedPt = points.getOrElse(activeIdx) { points.last() }
            val indicatorX = padX + usableW * animatedSnapX

            if (selectedIndex != null) {
                drawLine(
                    color = c.blue.copy(alpha = 0.45f),
                    start = Offset(indicatorX, padTop - 4.dp.toPx()),
                    end = Offset(indicatorX, size.height - padBottom + 2.dp.toPx()),
                    strokeWidth = 1.2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
                )
            }

            // Luminous node halo + crisp Apple white core
            drawCircle(
                color = c.blue.copy(alpha = if (selectedIndex != null) 0.28f else 0.18f),
                radius = if (selectedIndex != null) 11.dp.toPx() else 8.dp.toPx(),
                center = Offset(indicatorX, snappedPt.y),
            )
            drawCircle(
                color = c.blue,
                radius = if (selectedIndex != null) 5.5.dp.toPx() else 4.5.dp.toPx(),
                center = Offset(indicatorX, snappedPt.y),
            )
            drawCircle(
                color = Color.White,
                radius = 2.4.dp.toPx(),
                center = Offset(indicatorX, snappedPt.y),
            )
        }
    }
}

/**
 * 5. Time-windowed Stagger Clock: cascades rows on initial screen entrance within [windowMs] (900ms),
 *    then lets long LazyColumn scrolling compose items instantaneously at 120fps without replaying delays.
 */
@Stable
class StaggerClock(
    val windowMs: Long = 900L,
) {
    private var startMs: Long = SystemClock.uptimeMillis()

    val elapsedMs: Long
        get() = (SystemClock.uptimeMillis() - startMs).coerceAtLeast(0L)

    val isWithinWindow: Boolean
        get() = elapsedMs <= windowMs

    fun restart() {
        startMs = SystemClock.uptimeMillis()
    }
}

@Composable
fun rememberStaggerClock(
    vararg keys: Any?,
    windowMs: Long = 900L,
): StaggerClock = remember(*keys) { StaggerClock(windowMs = windowMs) }

/**
 * Staggered Spring Cascade entrance animation for cards and list rows.
 * Pass [clock] from [rememberStaggerClock] so items scrolled in after the 900ms window appear instantly.
 */
fun Modifier.staggeredCascade(
    index: Int,
    clock: StaggerClock? = null,
    stepMs: Long = 38L,
    windowMs: Long = clock?.windowMs ?: 900L,
): Modifier = composed {
    val skip = clock != null && clock.elapsedMs > windowMs
    val progress = remember { Animatable(if (skip) 1f else 0f) }

    LaunchedEffect(Unit) {
        if (progress.value < 1f) {
            val delayMs = if (clock != null) {
                max(0L, index.coerceAtMost(14) * stepMs - clock.elapsedMs)
            } else {
                index.coerceAtMost(8) * stepMs
            }
            if (delayMs > 0L) delay(delayMs)
            progress.animateTo(
                targetValue = 1f,
                animationSpec = spring(dampingRatio = 0.72f, stiffness = 280f),
            )
        }
    }

    this.graphicsLayer {
        val v = progress.value
        if (v < 0.999f) {
            translationY = (1f - v) * 26.dp.toPx()
            alpha = v.coerceIn(0f, 1f)
            val s = 0.975f + 0.025f * v
            scaleX = s
            scaleY = s
        }
    }
}

/** Convenience overload allowing `Modifier.staggeredCascade(clock, index)`. */
fun Modifier.staggeredCascade(
    clock: StaggerClock,
    index: Int,
    stepMs: Long = 38L,
    windowMs: Long = clock.windowMs,
): Modifier = staggeredCascade(
    index = index,
    clock = clock,
    stepMs = stepMs,
    windowMs = windowMs,
)

/**
 * 6. Root-coordinate anchor for fluid capsule-to-popover morphing.
 */
@Stable
class MorphAnchor {
    var bounds by mutableStateOf(Rect.Zero)
}

/** Records this composable's root coordinates into [anchor] for [AppleCapsuleMorph]. */
fun Modifier.morphAnchor(anchor: MorphAnchor): Modifier =
    onGloballyPositioned { anchor.bounds = it.boundsInRoot() }

/**
 * Fluidly morphs a top-right status capsule from its exact root [anchor] bounds into a compact
 * Apple floating status popover card on one heavily damped [HushSprings.fluid] spring.
 */
@Composable
fun AppleCapsuleMorph(
    visible: Boolean,
    anchor: Rect,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onClosed: () -> Unit = {},
    onCapsuleHiddenChange: (Boolean) -> Unit = {},
    capsule: @Composable () -> Unit,
    panel: @Composable ColumnScope.() -> Unit,
) {
    val c = HushTheme.colors
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    val latestOnClosed by rememberUpdatedState(onClosed)
    val latestOnCapsuleHiddenChange by rememberUpdatedState(onCapsuleHiddenChange)

    var present by remember { mutableStateOf(false) }
    if (visible && !present) present = true

    LaunchedEffect(present) {
        latestOnCapsuleHiddenChange(present)
    }

    if (!present) return

    val p = remember { Animatable(0f) }

    LaunchedEffect(visible) {
        if (visible) {
            p.animateTo(1f, HushSprings.fluid())
        } else {
            p.animateTo(0f, HushSprings.fluid())
            present = false
            latestOnClosed()
        }
    }

    BackHandler(enabled = visible) { latestOnDismiss() }

    val density = LocalDensity.current
    val statusTopPx = WindowInsets.statusBars.getTop(density).toFloat()
    val marginPx = with(density) { 14.dp.toPx() }
    val panelCornerPx = with(density) { 28.dp.toPx() }
    val surfaceBg = if (c.isDark) c.surfaceElevated else c.surface
    val borderColor = if (c.isDark) c.glassBorder else Color.Black.copy(alpha = 0.06f)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val screenW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val safeAnchor = if (anchor.width > 1f && anchor.height > 1f) {
            anchor
        } else {
            val fallbackW = with(density) { 114.dp.toPx() }
            val fallbackH = with(density) { 36.dp.toPx() }
            val fallbackTop = statusTopPx + with(density) { 14.dp.toPx() }
            Rect(
                left = (screenW - marginPx - fallbackW).coerceAtLeast(marginPx),
                top = fallbackTop,
                right = (screenW - marginPx).coerceAtLeast(marginPx + 1f),
                bottom = fallbackTop + fallbackH,
            )
        }
        val targetTop = safeAnchor.top.coerceAtLeast(statusTopPx + with(density) { 6.dp.toPx() })

        // Soft translucent scrim
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = p.value.coerceIn(0f, 1f) }
                .background(if (c.isDark) Color.Black.copy(alpha = 0.56f) else Color.Black.copy(alpha = 0.22f))
                .pointerInput(Unit) { detectTapGestures { latestOnDismiss() } },
        )

        Layout(
            content = {
                Box(
                    modifier = Modifier.graphicsLayer {
                        alpha = (1f - p.value * 3.2f).coerceIn(0f, 1f)
                    },
                ) {
                    capsule()
                }
                Column(
                    modifier = Modifier.graphicsLayer {
                        val v = ((p.value - 0.24f) / 0.76f).coerceIn(0f, 1f)
                        alpha = v
                        translationY = (1f - v) * -8.dp.toPx()
                    },
                    content = panel,
                )
            },
            modifier = Modifier
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        val t = p.value
                        val x = lerpFloat(safeAnchor.left, marginPx, t).roundToInt()
                        val y = lerpFloat(safeAnchor.top, targetTop, t).roundToInt()
                        placeable.place(x, y)
                    }
                }
                .graphicsLayer {
                    val t = p.value.coerceIn(0f, 1f)
                    val radius = lerpFloat(safeAnchor.height / 2f, panelCornerPx, t).coerceAtLeast(0f)
                    shape = RoundedCornerShape(radius)
                    clip = true
                    shadowElevation = (if (c.isDark) 24.dp else 26.dp).toPx() * t
                    ambientShadowColor = Color.Black.copy(alpha = if (c.isDark) 0.45f else 0.14f)
                    spotShadowColor = Color.Black.copy(alpha = if (c.isDark) 0.65f else 0.24f)
                }
                .background(surfaceBg)
                .drawWithContent {
                    drawContent()
                    val t = p.value.coerceIn(0f, 1f)
                    val radius = lerpFloat(safeAnchor.height / 2f, panelCornerPx, t).coerceAtLeast(0f)
                    val sw = 0.75.dp.toPx()
                    drawRoundRect(
                        color = borderColor,
                        topLeft = Offset(sw / 2f, sw / 2f),
                        size = Size((size.width - sw).coerceAtLeast(0f), (size.height - sw).coerceAtLeast(0f)),
                        cornerRadius = CornerRadius(radius, radius),
                        style = Stroke(width = sw),
                    )
                }
                .pointerInput(Unit) { detectTapGestures { } },
        ) { measurables, _ ->
            val t = p.value
            val panelW = (screenW - marginPx * 2f).roundToInt().coerceAtLeast(1)
            val capPlaceable = measurables[0].measure(Constraints())
            val panelPlaceable = measurables[1].measure(Constraints(minWidth = panelW, maxWidth = panelW))
            val curW = lerpFloat(safeAnchor.width, panelW.toFloat(), t).roundToInt().coerceAtLeast(1)
            val curH = lerpFloat(safeAnchor.height, panelPlaceable.height.toFloat(), t).roundToInt().coerceAtLeast(1)
            layout(curW, curH) {
                capPlaceable.place(0, 0)
                panelPlaceable.place(0, 0)
            }
        }
    }
}

/**
 * 7. Physics-driven iOS Rubber-Band Bottom Sheet with velocity-projected detents,
 *    NestedScrollConnection coordination for inner LazyColumns, asymptotic overscroll resistance,
 *    and live [onProgress] (`0f` hidden .. `1f` expanded) to drive iOS backdrop recession in `HushRoot`.
 */
@Composable
fun AppleRubberBandSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    allowHalfDetent: Boolean = true,
    containerColor: Color = HushTheme.colors.surface,
    onProgress: (Float) -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = HushTheme.colors
    val density = LocalDensity.current
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    val latestOnProgress by rememberUpdatedState(onProgress)

    var present by remember { mutableStateOf(false) }
    if (visible && !present) present = true

    LaunchedEffect(present) {
        if (!present) latestOnProgress(0f)
    }

    if (!present) return

    val statusTopPx = WindowInsets.statusBars.getTop(density).toFloat()
    val expandedTopInset = statusTopPx + with(density) { 14.dp.toPx() }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val totalHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val expandedOffset = expandedTopInset.coerceAtMost(totalHeightPx * 0.25f)
        val halfOffset = (totalHeightPx * 0.44f).coerceAtLeast(expandedOffset + 1f)
        val hiddenOffset = totalHeightPx
        val anchors = remember(allowHalfDetent, expandedOffset, halfOffset) {
            if (allowHalfDetent) listOf(expandedOffset, halfOffset) else listOf(expandedOffset)
        }

        val scope = rememberCoroutineScope()
        var pos by remember { mutableFloatStateOf(hiddenOffset) }
        var raw by remember { mutableFloatStateOf(hiddenOffset) }
        val animJob = remember { arrayOfNulls<Job>(1) }

        fun currentProgress(): Float =
            ((hiddenOffset - pos) / (hiddenOffset - expandedOffset).coerceAtLeast(1f)).coerceIn(0f, 1f)

        suspend fun animateTo(target: Float, velocity: Float = 0f) {
            animate(
                initialValue = pos,
                targetValue = target,
                initialVelocity = velocity,
                animationSpec = HushSprings.fluid(),
            ) { value, _ ->
                pos = value
                raw = value
            }
        }

        fun dragBy(delta: Float) {
            animJob[0]?.cancel()
            raw += delta
            pos = if (raw < expandedOffset) {
                expandedOffset - appleRubberBand(expandedOffset - raw, totalHeightPx * 0.14f)
            } else {
                raw.coerceAtMost(hiddenOffset)
            }
        }

        fun settle(velocity: Float) {
            val projected = pos + velocity * 0.18f
            val candidates = anchors + hiddenOffset
            val target = candidates.minBy { abs(it - projected) }
            animJob[0]?.cancel()
            animJob[0] = scope.launch {
                animateTo(target, velocity.coerceIn(-4000f, 4000f))
                if (target >= hiddenOffset - 1f) {
                    latestOnProgress(0f)
                    present = false
                    latestOnDismiss()
                }
            }
        }

        LaunchedEffect(visible) {
            animJob[0]?.cancel()
            if (visible) {
                val initialDetent = if (allowHalfDetent) halfOffset else expandedOffset
                val job = launch { animateTo(initialDetent) }
                animJob[0] = job
                job.join()
            } else if (present) {
                val job = launch { animateTo(hiddenOffset) }
                animJob[0] = job
                job.join()
                if (!visible && pos >= hiddenOffset - 2f) {
                    latestOnProgress(0f)
                    present = false
                }
            }
        }

        LaunchedEffect(Unit) {
            snapshotFlow { currentProgress() }.collect { latestOnProgress(it) }
        }

        DisposableEffect(Unit) {
            onDispose { latestOnProgress(0f) }
        }

        BackHandler(enabled = visible) { latestOnDismiss() }

        val nestedConnection = remember(totalHeightPx, expandedOffset, anchors) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (available.y < 0f && pos > expandedOffset + 0.5f && source == NestedScrollSource.UserInput) {
                        animJob[0]?.cancel()
                        val next = (pos + available.y).coerceAtLeast(expandedOffset)
                        val used = next - pos
                        pos = next
                        raw = next
                        return Offset(0f, used)
                    }
                    return Offset.Zero
                }

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (available.y > 0f && source == NestedScrollSource.UserInput) {
                        dragBy(available.y)
                        return Offset(0f, available.y)
                    }
                    return Offset.Zero
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    val nearest = anchors.minBy { abs(it - pos) }
                    if (abs(nearest - pos) > 1f) {
                        settle(available.y)
                        return available
                    }
                    return Velocity.Zero
                }
            }
        }

        // Frosted dim scrim driven continuously by sheet progress
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = currentProgress() }
                .background(c.scrim)
                .pointerInput(Unit) { detectTapGestures { latestOnDismiss() } },
        )

        val sheetHeightDp = with(density) { (totalHeightPx - expandedOffset).toDp() }
        val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

        Column(
            modifier = Modifier
                .offset { IntOffset(0, pos.roundToInt()) }
                .fillMaxWidth()
                .height(sheetHeightDp + 84.dp) // extra tail so rubber-banding never exposes a gap
                .shadow(
                    elevation = if (c.isDark) 20.dp else 24.dp,
                    shape = sheetShape,
                    ambientColor = Color.Black.copy(alpha = 0.16f),
                    spotColor = Color.Black.copy(alpha = 0.26f),
                )
                .clip(sheetShape)
                .background(containerColor)
                .nestedScroll(nestedConnection)
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta -> dragBy(delta) },
                    onDragStarted = {
                        animJob[0]?.cancel()
                        raw = pos
                    },
                    onDragStopped = { velocity -> settle(velocity) },
                )
                .padding(bottom = 84.dp),
        ) {
            // Iconic Apple grabber handle
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(38.dp)
                        .height(4.5.dp)
                        .clip(CircleShape)
                        .background(c.textTertiary.copy(alpha = 0.36f)),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .navigationBarsPadding(),
                content = content,
            )
        }
    }
}
