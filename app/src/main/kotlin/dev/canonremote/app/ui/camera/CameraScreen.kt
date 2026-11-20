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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import dev.canonremote.app.ui.CameraActions
import dev.canonremote.core.CameraSetting
import dev.canonremote.core.ControlsModel
import dev.canonremote.core.SliderModel
import kotlin.math.roundToInt

@Composable
private fun BasicSliderRow(slider: SliderModel, onSet: (CameraSetting, Int) -> Unit) {
    var dragging by remember(slider.setting, slider.index) { mutableStateOf<Float?>(null) }
    val last = (slider.options.size - 1).coerceAtLeast(0)
    val position = dragging ?: slider.index.coerceAtLeast(0).toFloat()
    val shownLabel = dragging?.let { slider.options.getOrNull(it.roundToInt())?.second } ?: slider.valueLabel
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = slider.title, style = MaterialTheme.typography.labelMedium)
            Text(text = shownLabel, style = MaterialTheme.typography.titleMedium)
        }
        Slider(
            value = position,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { v ->
                    slider.options.getOrNull(v.roundToInt())?.let { onSet(slider.setting, it.first) }
                }
                dragging = null
            },
            valueRange = 0f..last.toFloat().coerceAtLeast(1f),
            steps = (slider.options.size - 2).coerceAtLeast(0),
            enabled = slider.enabled,
        )
        slider.lockHint?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }
    }
}

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
    val picked = controls.whiteBalance.takeIf { it.setting == pickerFor && it.enabled }

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

        // Minimal rendering until the v2 UI (Task 5): basic sliders, WB tile.
        controls.sliders.forEach { slider -> BasicSliderRow(slider = slider, onSet = actions::onSet) }

        SettingTile(
            tile = controls.whiteBalance,
            onClick = { pickerFor = controls.whiteBalance.setting },
            modifier = Modifier.fillMaxWidth(),
        )

        FocusRow(
            afEnabled = controls.focus.afEnabled,
            manualEnabled = controls.focus.manualStepsEnabled,
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
