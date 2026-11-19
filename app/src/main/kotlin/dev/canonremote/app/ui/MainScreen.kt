package dev.canonremote.app.ui

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
import dev.canonremote.core.CameraSetting
import dev.canonremote.core.ControlsModel
import dev.canonremote.core.FocusStep
import kotlinx.coroutines.flow.Flow

interface CameraActions {
    fun onSet(setting: CameraSetting, value: Int)
    fun onShoot()
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
    connection: @Composable () -> Unit,
    settings: @Composable () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(errors) { errors.collect { snackbar.showSnackbar(it) } }
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
                )
                1 -> connection()
                else -> settings()
            }
        }
    }
}
