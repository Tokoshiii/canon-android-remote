package dev.canonremote.app.ui.camera

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.canonremote.app.ui.CameraActions
import dev.canonremote.core.CameraSetting
import dev.canonremote.core.ControlsModel

@Composable
fun CameraScreen(
    controls: ControlsModel,
    frame: ByteArray?,
    actions: CameraActions,
    onStatusClick: () -> Unit,
) {
    var pickerFor by rememberSaveable { mutableStateOf<CameraSetting?>(null) }
    val picked = controls.tiles.firstOrNull { it.setting == pickerFor }

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
                label = { Text("● ${controls.statusLabel}") },
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
                onCheckedChange = actions::onLiveView,
                enabled = controls.liveViewToggleEnabled,
            )
        }

        ShutterButton(enabled = controls.shutterEnabled, onClick = actions::onShoot)
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
