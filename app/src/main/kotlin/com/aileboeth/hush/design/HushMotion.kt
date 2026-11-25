package com.aileboeth.hush.design

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.aileboeth.hush.state.MemorySamplePoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 1. Physical tactile button press: scale(0.96f) compression + subtle inner depth shadow,
 *    releasing with spring overshoot rebound (`dampingRatio = 0.45f`).
 */
fun Modifier.applePressScale(
    enabled: Boolean = true,
    pressedScale: Float = 0.96f,
    cornerRadius: Dp = 18.dp,
    onClick: () -> Unit,
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val haptic = LocalHapticFeedback.current
    val isDark = HushTheme.colors.isDark

    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = if (pressed) {
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 900f)
        } else {
            // Subtle crisp overshoot on release
            spring(dampingRatio = 0.46f, stiffness = 520f)
        },
        label = "applePressScale",
    )
    val shadowAlpha by animateFloatAsState(
        targetValue = if (pressed && enabled) (if (isDark) 0.24f else 0.10f) else 0f,
        animationSpec = tween(110),
        label = "innerShadowAlpha",
    )

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .drawWithContent {
            drawContent()
            if (shadowAlpha > 0.005f) {
                val r = cornerRadius.toPx()
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = shadowAlpha * 1.4f),
                        0.28f to Color.Black.copy(alpha = shadowAlpha * 0.25f),
                        1.0f to Color.Transparent,
                    ),
                    cornerRadius = CornerRadius(r, r),
                )
            }
        }
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
        )
}

/**
 * 2. Hero Luminous Card combining:
 *    - 3D perspective tilt tracking finger position + spring damping recovery on release
 *    - Touch-following radial specular reflection sheen gliding across the surface
 *    - Rotating Apple Intelligence Conic Gradient Border
 *    - Soft breathing diffuse ambient backlight underneath
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
    var cardSize by remember { mutableStateOf(IntSize.Zero) }

    val rotX = remember { Animatable(0f) }
    val rotY = remember { Animatable(0f) }
    var sheenCenter by remember { mutableStateOf(Offset.Unspecified) }
    val sheenAlpha = remember { Animatable(0f) }

    val infinite = rememberInfiniteTransition(label = "luminousHero")
    val conicAngle by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "conicAngle",
    )
    val glowBreath by infinite.animateFloat(
        initialValue = 0.65f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glowBreath",
    )

    val conicColors = if (isActive) {
        listOf(
            c.blue.copy(alpha = 0.58f),
            c.indigo.copy(alpha = 0.45f),
            c.purple.copy(alpha = 0.42f),
            c.cyan.copy(alpha = 0.52f),
            Color.Transparent,
            c.blue.copy(alpha = 0.58f),
        )
    } else {
        listOf(
            c.separator,
            c.glassBorder,
            c.separator,
        )
    }

    val shape = RoundedCornerShape(cornerRadius)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { cardSize = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    fun updateTilt(pos: Offset) {
                        val w = cardSize.width.coerceAtLeast(1).toFloat()
                        val h = cardSize.height.coerceAtLeast(1).toFloat()
                        val nx = ((pos.x / w) - 0.5f).coerceIn(-0.5f, 0.5f) * 2f
                        val ny = ((pos.y / h) - 0.5f).coerceIn(-0.5f, 0.5f) * 2f
                        sheenCenter = pos
                        scope.launch {
                            rotX.snapTo(-ny * 5.2f)
                            rotY.snapTo(nx * 5.2f)
                            sheenAlpha.snapTo(1f)
                        }
                    }
                    updateTilt(down.position)
                    while (true) {
                        val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                        val active = event.changes.firstOrNull { it.pressed }
                        if (active != null) {
                            updateTilt(active.position)
                        } else {
                            break
                        }
                    }
                    scope.launch {
                        launch {
                            rotX.animateTo(0f, spring(dampingRatio = 0.58f, stiffness = 280f))
                        }
                        launch {
                            rotY.animateTo(0f, spring(dampingRatio = 0.58f, stiffness = 280f))
                        }
                        launch {
                            sheenAlpha.animateTo(0f, tween(340))
                        }
                    }
                }
            }
            .graphicsLayer {
                rotationX = rotX.value
                rotationY = rotY.value
                cameraDistance = 14f * density
            },
    ) {
        // Breathing diffuse ambient backlight positioned cleanly beneath the hero card
        if (isActive) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(horizontal = 18.dp)
                    .offset(y = 10.dp)
                    .blur(26.dp)
                    .drawBehind {
                        val alphaScale = (if (c.isDark) 0.24f else 0.16f) * glowBreath
                        drawRoundRect(
                            brush = Brush.horizontalGradient(
                                listOf(
                                    c.blue.copy(alpha = alphaScale),
                                    c.purple.copy(alpha = alphaScale * 0.85f),
                                    c.cyan.copy(alpha = alphaScale),
                                ),
                            ),
                            cornerRadius = CornerRadius(cornerRadius.toPx(), cornerRadius.toPx()),
                        )
                    },
            )
        }

        // Main card container with rotating Conic Gradient Border + Radial Touch Specular Sheen
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .drawWithContent {
                    drawContent()
                    val r = cornerRadius.toPx()

                    // Touch-following radial specular reflection flow
                    if (sheenAlpha.value > 0.01f && sheenCenter != Offset.Unspecified) {
                        val radius = size.maxDimension * 0.62f
                        drawRoundRect(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = (if (c.isDark) 0.16f else 0.42f) * sheenAlpha.value),
                                    c.blue.copy(alpha = 0.07f * sheenAlpha.value),
                                    Color.Transparent,
                                ),
                                center = sheenCenter,
                                radius = radius,
                            ),
                            cornerRadius = CornerRadius(r, r),
                        )
                    }

                    // Rotating Conic Gradient Border
                    val strokePx = if (isActive) 1.5.dp.toPx() else 0.8.dp.toPx()
                    rotate(degrees = if (isActive) conicAngle else 0f) {
                        drawRoundRect(
                            brush = Brush.sweepGradient(colors = conicColors),
                            topLeft = Offset(strokePx / 2f, strokePx / 2f),
                            size = Size(size.width - strokePx, size.height - strokePx),
                            cornerRadius = CornerRadius(r, r),
                            style = Stroke(width = strokePx),
                        )
                    }
                },
            shape = shape,
            color = c.surface,
            shadowElevation = if (c.isDark) 0.dp else 4.dp,
            tonalElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                content = content,
            )
        }
    }
}

/**
 * 3. Odometer / Rolling Counter: each digit slides independently up/down with crisp spring physics.
 */
@Composable
fun OdometerText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    var previousText by remember { mutableStateOf(text) }
    val increasing = text >= previousText
    LaunchedEffect(text) {
        previousText = text
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        text.forEachIndexed { idx, ch ->
            AnimatedContent(
                targetState = ch,
                transitionSpec = {
                    val dir = if (increasing) 1 else -1
                    (slideInVertically(
                        animationSpec = spring(dampingRatio = 0.72f, stiffness = 420f),
                        initialOffsetY = { full -> (full * 0.65f * dir).roundToInt() },
                    ) + fadeIn(tween(130))) togetherWith (slideOutVertically(
                        animationSpec = spring(dampingRatio = 0.82f, stiffness = 460f),
                        targetOffsetY = { full -> (-full * 0.65f * dir).roundToInt() },
                    ) + fadeOut(tween(110)))
                },
                label = "odometer_$idx",
            ) { digit ->
                Text(
                    text = digit.toString(),
                    style = style,
                    color = color,
                )
            }
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
 * 5. Staggered Spring Cascade entrance animation for list rows.
 */
fun Modifier.staggeredCascade(index: Int): Modifier = composed {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val capped = index.coerceAtMost(8)
        delay(capped * 34L)
        visible = true
    }
    val offsetY by animateFloatAsState(
        targetValue = if (visible) 0f else 26f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 360f),
        label = "cascadeY",
    )
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(220),
        label = "cascadeAlpha",
    )
    this.graphicsLayer {
        translationY = offsetY * density
        this.alpha = alpha
    }
}

/**
 * 6. Physics-driven iOS Rubber-Band Bottom Sheet with velocity-based anchor snapping
 *    (Detents: Hidden, Half, Expanded) and true asymptotic overscroll resistance.
 */
@Composable
fun AppleRubberBandSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = HushTheme.colors
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(220)),
        exit = fadeOut(tween(180)),
        modifier = modifier.fillMaxSize(),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val totalHeightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
            val expandedOffset = totalHeightPx * 0.11f
            val halfOffset = totalHeightPx * 0.42f
            val hiddenOffset = totalHeightPx

            val sheetOffsetY = remember { Animatable(hiddenOffset) }

            LaunchedEffect(visible) {
                if (visible) {
                    sheetOffsetY.animateTo(
                        targetValue = halfOffset,
                        animationSpec = spring(dampingRatio = 0.76f, stiffness = 360f),
                    )
                }
            }

            fun dismissSheet() {
                scope.launch {
                    sheetOffsetY.animateTo(
                        targetValue = hiddenOffset,
                        animationSpec = spring(dampingRatio = 0.85f, stiffness = 420f),
                    )
                    onDismiss()
                }
            }

            // Frosted dim scrim
            val scrimFraction = (1f - ((sheetOffsetY.value - expandedOffset) / (hiddenOffset - expandedOffset)).coerceIn(0f, 1f))
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(c.scrim.copy(alpha = c.scrim.alpha * scrimFraction))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { dismissSheet() },
                    ),
            )

            // Draggable rubber-band sheet container
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset(0, sheetOffsetY.value.roundToInt()) }
                    .pointerInput(totalHeightPx) {
                        val velocityTracker = VelocityTracker()
                        var rawDragY = sheetOffsetY.value
                        detectVerticalDragGestures(
                            onDragStart = {
                                velocityTracker.resetTracking()
                                rawDragY = sheetOffsetY.value
                            },
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                velocityTracker.addPosition(change.uptimeMillis, Offset(0f, rawDragY + dragAmount))
                                rawDragY += dragAmount
                                // True iOS rubber-band resistance above expandedOffset!
                                val appliedY = if (rawDragY < expandedOffset) {
                                    val overshoot = expandedOffset - rawDragY
                                    val maxStretch = 68f * density.density
                                    val damped = (1f - 1f / (overshoot * 0.55f / maxStretch + 1f)) * maxStretch
                                    expandedOffset - damped
                                } else {
                                    rawDragY.coerceAtMost(hiddenOffset)
                                }
                                scope.launch { sheetOffsetY.snapTo(appliedY) }
                            },
                            onDragEnd = {
                                val velocityY = velocityTracker.calculateVelocity().y
                                val current = sheetOffsetY.value
                                // Velocity-weighted anchor calculation (like UISheetPresentationController)
                                val projected = current + velocityY * 0.18f
                                val target = when {
                                    velocityY > 1400f || projected > (halfOffset + hiddenOffset) * 0.52f -> hiddenOffset
                                    velocityY < -900f || projected < (expandedOffset + halfOffset) * 0.5f -> expandedOffset
                                    else -> halfOffset
                                }
                                scope.launch {
                                    sheetOffsetY.animateTo(
                                        targetValue = target,
                                        initialVelocity = velocityY.coerceIn(-3000f, 3000f),
                                        animationSpec = spring(dampingRatio = 0.72f, stiffness = 360f),
                                    )
                                    if (target >= hiddenOffset - 1f) {
                                        onDismiss()
                                    }
                                }
                            },
                            onDragCancel = {
                                scope.launch {
                                    sheetOffsetY.animateTo(
                                        targetValue = halfOffset,
                                        animationSpec = spring(dampingRatio = 0.72f, stiffness = 360f),
                                    )
                                }
                            },
                        )
                    },
                shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
                color = c.surface,
                shadowElevation = if (c.isDark) 0.dp else 16.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding(),
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
                                .background(c.textTertiary.copy(alpha = 0.35f)),
                        )
                    }
                    content()
                }
            }
        }
    }
}
