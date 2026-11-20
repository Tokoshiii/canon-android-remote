package dev.canonremote.app.ui.camera

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.canonremote.app.settings.TIMER_DELAYS
import dev.canonremote.app.timer.TimerState
import kotlin.math.roundToInt

private val BUTTON = 72.dp
private val GAP = 12.dp
private val WHEEL_ROW = 24.dp
private val EMPHASIZED = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private const val EXPAND_MS = 280

private fun delayLabel(seconds: Int): String = if (seconds == 0) "Désactivé" else "$seconds s"

/**
 * Déclencher + retardateur (mini-roue). A round timer button (stopwatch, greyed when off, delay
 * written inside when on) sits left of the shutter pill. Touching it expands it to the right,
 * pushing the shutter into a circle: its stopwatch folds it back, its vertical wheel picks
 * Désactivé / 2 s / 10 s. Touching the round shutter folds the timer and shoots (or starts the
 * countdown) at once. While counting, the shutter reads « Annuler · n » on errorContainer and a
 * touch cancels; with the timer expanded it shows only the number.
 */
@Composable
fun ShutterBar(
    enabled: Boolean,
    delaySeconds: Int,
    timer: TimerState,
    justShot: Boolean,
    tickHaptic: Boolean,
    onShutter: () -> Unit,
    onDelayChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val remaining = (timer as? TimerState.Counting)?.remaining

    BoxWithConstraints(modifier = modifier.fillMaxWidth().height(BUTTON)) {
        val expandedWidth = (maxWidth - BUTTON - GAP).coerceAtLeast(BUTTON)
        val timerWidth by animateDpAsState(
            targetValue = if (expanded) expandedWidth else BUTTON,
            animationSpec = tween(durationMillis = EXPAND_MS, easing = EMPHASIZED),
            label = "timerWidth",
        )
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(GAP),
        ) {
            TimerButton(
                expanded = expanded,
                expandedWidth = expandedWidth,
                delaySeconds = delaySeconds,
                tickHaptic = tickHaptic,
                onExpandedChange = { expanded = it },
                onDelayChange = onDelayChange,
                modifier = Modifier.width(timerWidth).fillMaxHeight(),
            )
            ShutterPill(
                expanded = expanded,
                enabled = enabled,
                remaining = remaining,
                justShot = justShot,
                onClick = {
                    if (remaining != null) {
                        onShutter() // cancels the countdown
                    } else {
                        expanded = false
                        onShutter()
                    }
                },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun TimerButton(
    expanded: Boolean,
    expandedWidth: Dp,
    delaySeconds: Int,
    tickHaptic: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onDelayChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val on = delaySeconds > 0
    val colors = MaterialTheme.colorScheme
    val background by animateColorAsState(
        targetValue = if (on && !expanded) colors.secondaryContainer else colors.surfaceContainerHighest,
        label = "timerBackground",
    )
    Surface(
        shape = CircleShape,
        color = background,
        contentColor = if (on || expanded) colors.onSecondaryContainer else colors.onSurfaceVariant,
        modifier = modifier,
    ) {
        if (!expanded) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(role = Role.Button, onClickLabel = "Régler le retardateur") { onExpandedChange(true) }
                    .semantics { contentDescription = "Retardateur : ${delayLabel(delaySeconds)}" },
            ) {
                StopwatchIcon(
                    size = 34.dp,
                    digit = if (on) delaySeconds.toString() else null,
                    modifier = Modifier.alpha(if (on) 1f else 0.5f),
                )
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                // Laid out at its final width from the start: the growing button reveals it.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .wrapContentWidth(align = Alignment.Start, unbounded = true)
                        .width(expandedWidth)
                        .fillMaxHeight()
                        .padding(start = 6.dp, end = 12.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(60.dp)
                            .clip(CircleShape)
                            .clickable(role = Role.Button, onClickLabel = "Valider") { onExpandedChange(false) }
                            .semantics { contentDescription = "Valider le retardateur" },
                    ) {
                        StopwatchIcon(size = 28.dp, digit = null)
                    }
                    Text(
                        text = "Retardateur",
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).alpha(0.8f),
                    )
                    TimerWheel(
                        selected = delaySeconds,
                        tickHaptic = tickHaptic,
                        onSelect = onDelayChange,
                    )
                }
            }
        }
    }
}

/** Vertical mini-wheel: drag to pick the delay; the current value sits in the centre pill. */
@Composable
private fun TimerWheel(selected: Int, tickHaptic: Boolean, onSelect: (Int) -> Unit) {
    val view = LocalView.current
    val rowPx = with(LocalDensity.current) { WHEEL_ROW.toPx() }
    val index = TIMER_DELAYS.indexOf(selected).coerceAtLeast(0)
    val position by animateFloatAsState(targetValue = index.toFloat(), label = "wheel")
    val currentIndex by rememberUpdatedState(index)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentTick by rememberUpdatedState(tickHaptic)

    fun pick(i: Int) {
        if (currentTick) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        currentOnSelect(TIMER_DELAYS[i])
    }

    Box(
        modifier = Modifier
            .size(width = 128.dp, height = BUTTON)
            // Fades the neighbours out at the top and bottom edges.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.3f to Color.Black,
                        0.7f to Color.Black,
                        1f to Color.Transparent,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            }
            .pointerInput(Unit) {
                var start = 0
                var last = 0
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = {
                        start = currentIndex
                        last = start
                        total = 0f
                    },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        total += dragAmount
                        // Dragging down brings the previous value to the centre, like a wheel.
                        val i = (start - (total / rowPx).roundToInt()).coerceIn(0, TIMER_DELAYS.lastIndex)
                        if (i != last) {
                            last = i
                            pick(i)
                        }
                    },
                )
            }
            .semantics {
                contentDescription = "Délai du retardateur"
                stateDescription = delayLabel(selected)
                customActions = listOf(
                    CustomAccessibilityAction(label = "Délai suivant") {
                        if (index < TIMER_DELAYS.lastIndex) pick(index + 1)
                        true
                    },
                    CustomAccessibilityAction(label = "Délai précédent") {
                        if (index > 0) pick(index - 1)
                        true
                    },
                )
            },
    ) {
        Box(
            modifier = Modifier
                .offset(y = 21.dp)
                .fillMaxWidth()
                .height(30.dp)
                .background(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(15.dp)),
        )
        TIMER_DELAYS.forEachIndexed { i, seconds ->
            val current = i == index
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .offset(y = WHEEL_ROW + WHEEL_ROW * (i - position))
                    .fillMaxWidth()
                    .height(WHEEL_ROW)
                    .alpha(if (current) 1f else 0.45f),
            ) {
                Text(
                    text = delayLabel(seconds),
                    fontSize = if (current) 17.sp else 13.sp,
                    fontWeight = if (current) FontWeight.Bold else FontWeight.Medium,
                    color = if (current) MaterialTheme.colorScheme.onSecondaryContainer else LocalContentColor.current,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ShutterPill(
    expanded: Boolean,
    enabled: Boolean,
    remaining: Int?,
    justShot: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val counting = remaining != null
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        targetValue = if (counting) colors.errorContainer else colors.primary,
        label = "shutterBackground",
    )
    val label = when {
        counting -> "Annuler · $remaining"
        justShot -> "Photo prise"
        else -> "Déclencher"
    }
    Button(
        onClick = onClick,
        // A running countdown can always be cancelled.
        enabled = enabled || counting,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = if (counting) colors.onErrorContainer else colors.onPrimary,
        ),
        contentPadding = PaddingValues(0.dp),
        modifier = modifier.semantics {
            contentDescription = when {
                counting -> "Annuler le retardateur ($remaining s)"
                else -> "Déclencher"
            }
        },
    ) {
        if (expanded && counting) {
            Text(text = "$remaining", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        } else {
            Icon(
                imageVector = Icons.Filled.PhotoCamera,
                contentDescription = null,
                modifier = Modifier.size(26.dp),
            )
        }
        if (!expanded) {
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = label,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

/**
 * Stopwatch drawn like the mockup's icon (24-unit viewport). With [digit], the delay is written
 * inside the dial; without, the hand is drawn.
 */
@Composable
private fun StopwatchIcon(size: Dp, digit: String?, modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Box(modifier = modifier.size(size)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val u = this.size.width / 24f
            val stroke = 1.8f * u
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(
                color = color,
                start = Offset(x1 * u, y1 * u),
                end = Offset(x2 * u, y2 * u),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            line(10f, 2f, 14f, 2f)
            line(12f, 2f, 12f, 4.5f)
            line(18.5f, 6.5f, 19.7f, 5.3f)
            drawCircle(color = color, radius = 8f * u, center = Offset(12f * u, 13.5f * u), style = Stroke(width = stroke))
            if (digit == null) line(12f, 13.5f, 12f, 9.5f)
        }
        if (digit != null) {
            Text(
                text = digit,
                fontSize = if (digit.length > 1) 10.sp else 12.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = size * (12f / 34f)),
            )
        }
    }
}
