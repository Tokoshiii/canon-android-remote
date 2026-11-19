package dev.canonremote.app.ui.camera

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.canonremote.core.FocusStep

private val STEPS = listOf(
    "◀◀" to FocusStep.NEAR_2,
    "◀" to FocusStep.NEAR_1,
    "▶" to FocusStep.FAR_1,
    "▶▶" to FocusStep.FAR_2,
)

@Composable
fun FocusRow(
    afEnabled: Boolean,
    manualEnabled: Boolean,
    onAutofocus: () -> Unit,
    onFocus: (FocusStep) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Mise au point", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = onAutofocus,
                enabled = afEnabled,
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) { Text("AF") }
            STEPS.forEach { (label, step) ->
                OutlinedButton(
                    onClick = { onFocus(step) },
                    enabled = manualEnabled,
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) { Text(label) }
            }
        }
    }
}
