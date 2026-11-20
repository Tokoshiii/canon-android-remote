package dev.canonremote.app.ui

import android.media.MediaActionSound
import android.view.HapticFeedbackConstants
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView
import dev.canonremote.app.settings.UiPreferences
import dev.canonremote.app.timer.TimerState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import dev.canonremote.app.ui.camera.CameraScreen
import dev.canonremote.app.ui.camera.DevPanelActions
import dev.canonremote.core.CameraSetting
import dev.canonremote.core.ControlsModel
import dev.canonremote.core.FocusStep
import kotlinx.coroutines.flow.Flow

interface CameraActions {
    fun onSet(setting: CameraSetting, value: Int)
    /** Shutter button: shoots, starts the self-timer, or cancels a running countdown. */
    fun onShutter()

    /** Self-timer delay chosen on the mini-wheel (0, 2 or 10 s); cancels a running countdown. */
    fun onTimerDelay(seconds: Int)
    fun onAutofocus()
    fun onFocus(step: FocusStep)
    fun onLiveView(on: Boolean)
}

private data class Tab(val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("Appareil", Icons.Filled.PhotoCamera),
    Tab("Connexion", Icons.Filled.Usb),
    Tab("Paramètres", Icons.Filled.Settings),
)

@Composable
fun MainScreen(
    controls: ControlsModel,
    frame: ImageBitmap?,
    errors: Flow<String>,
    actions: CameraActions,
    statusLabel: String,
    bluetoothRemote: Boolean,
    preferences: UiPreferences,
    timer: TimerState,
    shots: Flow<Unit>,
    devActions: DevPanelActions?,
    connection: @Composable () -> Unit,
    settings: @Composable () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(errors) { errors.collect { snackbar.showSnackbar(it) } }
    ShutterFeedback(shots = shots, haptic = preferences.shutterHaptic, sound = preferences.shutterSound)
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                TABS.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> CameraScreen(
                    controls = controls,
                    frame = frame,
                    actions = actions,
                    onStatusClick = { tab = 1 },
                    preferences = preferences,
                    timer = timer,
                    shots = shots,
                    statusLabel = statusLabel,
                    bluetoothRemote = bluetoothRemote,
                    onSwitchToWifi = { tab = 1 },
                    devActions = devActions,
                )
                1 -> connection()
                else -> settings()
            }
        }
    }
}

/** Vibration and shutter sound at each shot, whatever tab is shown (Paramètres → Prise de vue). */
@Composable
private fun ShutterFeedback(shots: Flow<Unit>, haptic: Boolean, sound: Boolean) {
    val view = LocalView.current
    val player = remember { MediaActionSound() }
    DisposableEffect(player) {
        player.load(MediaActionSound.SHUTTER_CLICK)
        onDispose { player.release() }
    }
    val currentHaptic by rememberUpdatedState(haptic)
    val currentSound by rememberUpdatedState(sound)
    LaunchedEffect(shots) {
        shots.collect {
            // CONFIRM exists from API 30; minSdk is 31.
            if (currentHaptic) view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            if (currentSound) player.play(MediaActionSound.SHUTTER_CLICK)
        }
    }
}
