package dev.canonremote.app

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.canonremote.app.ui.MainScreen
import dev.canonremote.app.ui.connection.ConnectionScreen
import dev.canonremote.app.ui.logs.LogsScreen
import dev.canonremote.app.ui.theme.CanonRemoteTheme

class MainActivity : ComponentActivity() {
    private val viewModel: CameraViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val controls by viewModel.controls.collectAsStateWithLifecycle()
            val frame by viewModel.frame.collectAsStateWithLifecycle()
            val usb by viewModel.usbStatus.collectAsStateWithLifecycle()
            val camera by viewModel.cameraState.collectAsStateWithLifecycle()
            val logEntries by viewModel.log.entries.collectAsStateWithLifecycle()
            CanonRemoteTheme {
                MainScreen(
                    controls = controls,
                    frame = frame,
                    errors = viewModel.errors,
                    actions = viewModel,
                    connection = {
                        ConnectionScreen(
                            status = usb,
                            cameraModel = camera.model,
                            serial = camera.serial,
                            onConnect = viewModel::connect,
                            onDisconnect = viewModel::disconnect,
                        )
                    },
                    logs = {
                        LogsScreen(
                            entries = logEntries,
                            onCopy = ::copyLogs,
                            onClear = viewModel.log::clear,
                        )
                    },
                )
            }
        }
    }

    private fun copyLogs() {
        val header = "Canon Remote ${BuildConfig.VERSION_NAME} — " +
            "${Build.MANUFACTURER} ${Build.MODEL} — Android ${Build.VERSION.RELEASE}"
        val text = viewModel.log.export(header)
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Canon Remote logs", text))
    }
}
