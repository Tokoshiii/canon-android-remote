package dev.canonremote.app.ui.camera

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import dev.canonremote.app.ui.CameraActions
import dev.canonremote.core.CameraSetting
import dev.canonremote.core.ControlsModel

@Composable
fun CameraScreen(
    controls: ControlsModel,
    frame: ImageBitmap?,
    actions: CameraActions,
    onStatusClick: () -> Unit,
    statusLabel: String = controls.statusLabel,
    bluetoothRemote: Boolean = false,
    onSwitchToWifi: () -> Unit = {},
) {
    var pickerFor by rememberSaveable { mutableStateOf<CameraSetting?>(null) }
    var bluetoothLiveViewDialog by rememberSaveable { mutableStateOf(false) }
    val picked = controls.tiles.firstOrNull { it.setting == pickerFor && it.enabled }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Canon EOS 90D", style = MaterialTheme.typography.titleLarge)
            AssistChip(
                onClick = onStatusClick,
                label = { Text("● $statusLabel") },
            )
        }

        if (controls.liveViewOn) LiveViewCard(frame)

        controls.tiles.chunked(2).forEach { rowTiles ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rowTiles.forEach { tile ->
                    SettingTile(
                        tile = tile,
                        onClick = { pickerFor = tile.setting },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        FocusRow(
            afEnabled = controls.afEnabled,
            manualEnabled = controls.manualFocusEnabled,
            onAutofocus = actions::onAutofocus,
            onFocus = actions::onFocus,
        )

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Live view", style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = controls.liveViewOn,
                onCheckedChange = { on ->
                    // Over Bluetooth the live view is refused: explain instead of sending the command.
                    if (on && bluetoothRemote) bluetoothLiveViewDialog = true else actions.onLiveView(on)
                },
                enabled = controls.liveViewToggleEnabled,
            )
        }

        ShutterButton(enabled = controls.shutterEnabled, onClick = actions::onShoot)
    }

    if (bluetoothLiveViewDialog) {
        AlertDialog(
            onDismissRequest = { bluetoothLiveViewDialog = false },
            title = { Text(text = "Live view indisponible en Bluetooth") },
            text = {
                Text(
                    text = "Le Bluetooth est trop lent pour le live view. Passe en Wi-Fi (réseau local ou " +
                        "point d'accès du relais) pour l'utiliser.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        bluetoothLiveViewDialog = false
                        onSwitchToWifi()
                    },
                ) { Text(text = "Passer en Wi-Fi") }
            },
            dismissButton = {
                TextButton(onClick = { bluetoothLiveViewDialog = false }) { Text(text = "OK") }
            },
        )
    }

    if (picked != null) {
        ValuePickerSheet(
            tile = picked,
            onPick = { value ->
                actions.onSet(picked.setting, value)
                pickerFor = null
            },
            onDismiss = { pickerFor = null },
        )
    }
}
