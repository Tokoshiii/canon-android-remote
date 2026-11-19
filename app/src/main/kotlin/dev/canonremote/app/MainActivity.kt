package dev.canonremote.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.canonremote.app.ui.CameraActions
import dev.canonremote.app.ui.MainScreen
import dev.canonremote.app.ui.connection.ConnectionScreen
import dev.canonremote.app.ui.logs.LogsScreen
import dev.canonremote.app.ui.theme.CanonRemoteTheme
import dev.canonremote.core.CameraSetting
import dev.canonremote.core.CameraState
import dev.canonremote.core.FocusStep
import dev.canonremote.core.toControls

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val noActions = object : CameraActions {
            override fun onSet(setting: CameraSetting, value: Int) {}
            override fun onShoot() {}
            override fun onAutofocus() {}
            override fun onFocus(step: FocusStep) {}
            override fun onLiveView(on: Boolean) {}
        }
        setContent {
            CanonRemoteTheme {
                MainScreen(
                    controls = CameraState().toControls(),
                    frame = null,
                    actions = noActions,
                    connection = { ConnectionScreen() },
                    logs = { LogsScreen() },
                )
            }
        }
    }
}
