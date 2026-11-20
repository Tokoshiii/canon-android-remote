package dev.canonremote.app.ui.camera

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.canonremote.core.CameraSetting
import dev.canonremote.core.SliderModel
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Opacity of a setting locked by the camera's mode dial (« Auto »). */
private const val LOCKED_ALPHA = 0.38f

/** How long a released value is shown while the camera confirms it; then the camera's value wins. */
private const val PENDING_MS = 2_000L

/**
 * One exposure setting (Vitesse, Ouverture, ISO): title, current value in large, and a Material 3
 * [Slider] with one notch per value the camera offers (dots on the major values). The label
 * follows the finger; the value is sent once, on release (or on a tap on the track). Locked by
 * the mode: disabled, dimmed, « Auto » over it with a hint; the thumb still follows the camera.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExposureSliderCard(
    slider: SliderModel,
    tickHaptic: Boolean,
    onSet: (CameraSetting, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val last = (slider.options.size - 1).coerceAtLeast(0)
    // Notch under the finger while dragging.
    var dragIndex by remember(slider.setting) { mutableStateOf<Int?>(null) }
    // Released notch not yet confirmed by the camera; dropped when the camera's value changes or
    // after PENDING_MS (the setting failed: back to the camera's value).
    var pendingIndex by remember(slider.setting, slider.index) { mutableStateOf<Int?>(null) }
    LaunchedEffect(pendingIndex) {
        if (pendingIndex != null) {
            delay(PENDING_MS)
            pendingIndex = null
        }
    }
    val localIndex = dragIndex ?: pendingIndex
    val shownIndex = localIndex ?: slider.index
    val valueLabel = localIndex?.let { slider.options.getOrNull(it)?.second } ?: slider.valueLabel
    // Locked by the mode: the camera chooses, so « Auto » in large (Bulb stays « Bulb »).
    val headline = if (slider.locked && slider.valueLabel != BULB) AUTO else valueLabel
    val colors = SliderDefaults.colors()
    val interactionSource = remember { MutableInteractionSource() }
    // Locked with no known value: no thumb, rather than one on the first notch that reads as a value.
    val hideThumb = slider.locked && localIndex == null && slider.index < 0

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Box {
            Column(
                modifier = Modifier
                    .alpha(if (slider.locked) LOCKED_ALPHA else 1f)
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(
                        text = slider.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = headline,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Slider(
                    value = shownIndex.coerceIn(0, last).toFloat(),
                    onValueChange = { raw ->
                        val notch = raw.roundToInt().coerceIn(0, last)
                        val before = dragIndex ?: pendingIndex ?: slider.index
                        if (notch != before && tickHaptic) {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                        dragIndex = notch
                    },
                    onValueChangeFinished = {
                        val notch = dragIndex
                        dragIndex = null
                        // Compared to what was last sent (still pending), not only to the camera's
                        // value: going back to the original notch before it confirms is sent too.
                        if (notch != null && notch != (pendingIndex ?: slider.index)) {
                            slider.options.getOrNull(notch)?.let { (value, _) ->
                                pendingIndex = notch
                                onSet(slider.setting, value)
                            }
                        }
                    },
                    enabled = slider.enabled,
                    valueRange = 0f..last.coerceAtLeast(1).toFloat(),
                    steps = (slider.options.size - 2).coerceAtLeast(0),
                    colors = colors,
                    interactionSource = interactionSource,
                    thumb = {
                        if (hideThumb) {
                            Spacer(modifier = Modifier.size(20.dp))
                        } else {
                            SliderDefaults.Thumb(
                                interactionSource = interactionSource,
                                colors = colors,
                                enabled = slider.enabled,
                            )
                        }
                    },
                    track = { state ->
                        SliderDefaults.Track(
                            sliderState = state,
                            enabled = slider.enabled,
                            colors = colors,
                            drawTick = { offset, color ->
                                // Ticks sit at i / last of the track width: dots on major values only.
                                val notch = if (last > 0 && size.width > 0f) {
                                    (offset.x / size.width * last).roundToInt()
                                } else {
                                    0
                                }
                                if (notch in slider.majorIndices) {
                                    drawCircle(color = color, radius = 2.dp.toPx(), center = offset)
                                }
                            },
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { stateDescription = valueLabel },
                )
            }
            if (slider.locked) {
                Column(
                    modifier = Modifier.matchParentSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        // Bulb is locked too, but is not an automatic value.
                        text = if (slider.valueLabel == BULB) BULB else AUTO,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .background(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(16.dp),
                            )
                            .padding(horizontal = 18.dp, vertical = 6.dp),
                    )
                    slider.lockHint?.let { hint ->
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private const val BULB = "Bulb"
private const val AUTO = "Auto"
