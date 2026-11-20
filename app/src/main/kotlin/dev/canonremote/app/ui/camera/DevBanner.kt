package dev.canonremote.app.ui.camera

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Commands of the developer panel (simulated camera only). */
interface DevPanelActions {
    /** Simulated mode dial: Canon shooting-mode code. */
    fun onDevShootingMode(code: Int)

    /** Simulated lens switch: 0 = AF, 3 = MF. */
    fun onDevFocusMode(code: Int)
}

/** Simulated mode dial: label → Canon code (as read in 0xD105 / shootingMode). */
private val DEV_MODES = listOf("M" to 3, "Av" to 2, "Tv" to 1, "P" to 0, "B" to 4, "A+" to 9)
private val DEV_LENS = listOf("AF" to 0, "MF" to 3)

/** Top of the Appareil tab while the simulated camera is used. */
@Composable
fun DevBanner(onOpenPanel: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(imageVector = Icons.Filled.DeveloperMode, contentDescription = null)
            Text(
                text = "Mode développeur — aucune commande n'est envoyée",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenPanel) { Text(text = "Simuler") }
        }
    }
}

/** Developer panel: simulated mode dial, lens AF/MF and live view (test image). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevPanelSheet(
    modeLabel: String?,
    lensLabel: String?,
    liveViewOn: Boolean,
    actions: DevPanelActions,
    onLiveView: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "Appareil simulé", style = MaterialTheme.typography.titleLarge)

            Text(text = "Mode", style = MaterialTheme.typography.titleSmall)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                DEV_MODES.forEachIndexed { index, (label, code) ->
                    SegmentedButton(
                        selected = modeLabel == label,
                        onClick = { actions.onDevShootingMode(code) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = DEV_MODES.size),
                        icon = {},
                        label = { Text(text = label) },
                    )
                }
            }

            Text(text = "Objectif", style = MaterialTheme.typography.titleSmall)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                DEV_LENS.forEachIndexed { index, (label, code) ->
                    SegmentedButton(
                        selected = lensLabel == label,
                        onClick = { actions.onDevFocusMode(code) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = DEV_LENS.size),
                        label = { Text(text = label) },
                    )
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Live view simulé", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "Image de test générée sur le téléphone",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = liveViewOn, onCheckedChange = onLiveView)
            }

            Text(
                text = "Chaque commande est notée dans le journal, préfixée « [dev] ». Un vrai appareil " +
                    "reprend la main dès qu'il se connecte.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
