package dev.canonremote.app.ui.camera

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.filled.KeyboardDoubleArrowRight
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.canonremote.app.settings.FocusStepSize
import dev.canonremote.core.FocusModel
import dev.canonremote.core.FocusStep

private const val LIVE_VIEW_HINT = "Proche / Loin : active le live view"
private const val BLUETOOTH_HINT = "Proche / Loin : passe en Wi-Fi (live view requis)"

/**
 * Mise au point: lens AF/MF indicator, Proche / AF / Loin. Proche and Loin move by the step size
 * chosen in Paramètres ([step]); they need the live view.
 */
@Composable
fun FocusCard(
    focus: FocusModel,
    connected: Boolean,
    step: FocusStepSize,
    onAutofocus: () -> Unit,
    onFocus: (FocusStep) -> Unit,
    modifier: Modifier = Modifier,
    /** Over Bluetooth the live view (needed by Proche / Loin) is impossible: say so instead. */
    bluetoothRemote: Boolean = false,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Mise au point",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                focus.lensLabel?.let { lens ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(width = 1.dp, color = MaterialTheme.colorScheme.outline),
                    ) {
                        Text(
                            text = "Objectif : $lens",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onFocus(step.near) },
                    enabled = focus.manualStepsEnabled,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(1f).height(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardDoubleArrowLeft,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "Proche")
                }
                FilledTonalButton(
                    onClick = onAutofocus,
                    enabled = focus.afEnabled,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(1f).height(48.dp),
                ) {
                    Text(text = "AF", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = { onFocus(step.far) },
                    enabled = focus.manualStepsEnabled,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(1f).height(48.dp),
                ) {
                    Text(text = "Loin")
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Filled.KeyboardDoubleArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            val hint = listOfNotNull(
                focus.afHint,
                (if (bluetoothRemote) BLUETOOTH_HINT else LIVE_VIEW_HINT).takeIf { connected && !focus.manualStepsEnabled },
            )
                .joinToString(separator = " · ")
                .takeIf { it.isNotEmpty() }
            hint?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
