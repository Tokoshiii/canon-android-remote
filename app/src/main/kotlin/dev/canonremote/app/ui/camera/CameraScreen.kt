package dev.canonremote.app.ui.camera

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import dev.canonremote.app.settings.UiPreferences
import dev.canonremote.app.timer.TimerState
import dev.canonremote.app.ui.CameraActions
import dev.canonremote.core.CameraSetting
import dev.canonremote.core.ControlsModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

@Composable
fun CameraScreen(
    controls: ControlsModel,
    frame: ImageBitmap?,
    actions: CameraActions,
    onStatusClick: () -> Unit,
    preferences: UiPreferences,
    timer: TimerState,
    shots: Flow<Unit>,
    statusLabel: String = controls.statusLabel,
    bluetoothRemote: Boolean = false,
    onSwitchToWifi: () -> Unit = {},
) {
    var tab by rememberSaveable { mutableStateOf(CameraTab.BASIC) }
    var pickerFor by rememberSaveable { mutableStateOf<CameraSetting?>(null) }
    var bluetoothLiveViewDialog by rememberSaveable { mutableStateOf(false) }
    val picked = controls.whiteBalance.takeIf { it.setting == pickerFor && it.enabled }

    // « Photo prise » on the shutter for a moment after each shot.
    var justShot by remember { mutableStateOf(false) }
    LaunchedEffect(shots) {
        shots.collectLatest {
            justShot = true
            delay(JUST_SHOT_MS)
            justShot = false
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        CameraHeader(
            tab = tab,
            onTabChange = { tab = it },
            modeLabel = controls.modeLabel,
            statusLabel = statusLabel,
            connected = controls.connected,
            onStatusClick = onStatusClick,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
        )

        when (tab) {
            CameraTab.BASIC -> {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (controls.liveViewOn) LiveViewCard(frame)

                    controls.sliders.forEach { slider ->
                        ExposureSliderCard(
                            slider = slider,
                            tickHaptic = preferences.tickHaptic,
                            onSet = actions::onSet,
                        )
                    }

                    FocusCard(
                        focus = controls.focus,
                        connected = controls.connected,
                        step = preferences.focusStep,
                        onAutofocus = actions::onAutofocus,
                        onFocus = actions::onFocus,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        SettingTile(
                            tile = controls.whiteBalance,
                            onClick = { pickerFor = controls.whiteBalance.setting },
                            modifier = Modifier.weight(1f),
                        )
                        SwitchTile(
                            title = "Live view",
                            checked = controls.liveViewOn,
                            enabled = controls.liveViewToggleEnabled,
                            onCheckedChange = { on ->
                                // Over Bluetooth the live view is refused: explain instead of sending the command.
                                if (on && bluetoothRemote) bluetoothLiveViewDialog = true else actions.onLiveView(on)
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                ShutterBar(
                    enabled = controls.shutterEnabled,
                    delaySeconds = preferences.timerDelay,
                    timer = timer,
                    justShot = justShot,
                    tickHaptic = preferences.tickHaptic,
                    onShutter = actions::onShutter,
                    onDelayChange = actions::onTimerDelay,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 16.dp),
                )
            }
            CameraTab.ADVANCED -> AdvancedTab(modifier = Modifier.padding(horizontal = 16.dp))
        }
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

private const val JUST_SHOT_MS = 900L

/** Avancé: placeholder until the timelapse project. */
@Composable
private fun AdvancedTab(modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = "Timelapse — bientôt", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "Intervalle, nombre de photos et durée : dans une prochaine version.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
