package dev.canonremote.app

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.canonremote.app.relay.statusChipLabel
import dev.canonremote.app.ui.MainScreen
import dev.canonremote.app.ui.connection.ConnectionScreen
import dev.canonremote.app.ui.connection.ConnectionUi
import dev.canonremote.app.ui.logs.LogsScreen
import dev.canonremote.app.ui.settings.AboutInfo
import dev.canonremote.app.ui.settings.SettingsTab
import dev.canonremote.app.ui.theme.CanonRemoteTheme
import dev.canonremote.app.ui.theme.isDarkTheme

class MainActivity : ComponentActivity() {
    private val viewModel: CameraViewModel by viewModels()
    private val settings by lazy { (application as CanonRemoteApp).settings }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val about = AboutInfo(
            version = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            phone = "${Build.MANUFACTURER} ${Build.MODEL} — Android ${Build.VERSION.RELEASE}",
        )
        setContent {
            val prefs by settings.preferences.collectAsStateWithLifecycle()
            val controls by viewModel.controls.collectAsStateWithLifecycle()
            val frame by viewModel.frame.collectAsStateWithLifecycle()
            val usb by viewModel.usbStatus.collectAsStateWithLifecycle()
            val usbCamera by viewModel.usbCameraState.collectAsStateWithLifecycle()
            val mode by viewModel.mode.collectAsStateWithLifecycle()
            val relay by viewModel.relayState.collectAsStateWithLifecycle()
            val remoteLink by viewModel.remoteLink.collectAsStateWithLifecycle()
            val bluetoothRemote by viewModel.bluetoothRemote.collectAsStateWithLifecycle()
            val logEntries by viewModel.log.entries.collectAsStateWithLifecycle()
            val dark = isDarkTheme(prefs.theme)

            // Status/navigation bar icons follow the app theme, not only the system one.
            LaunchedEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            val view = LocalView.current
            DisposableEffect(prefs.keepScreenOn) {
                view.keepScreenOn = prefs.keepScreenOn
                onDispose { view.keepScreenOn = false }
            }

            CanonRemoteTheme(dark = dark, dynamicColor = prefs.dynamicColor) {
                MainScreen(
                    controls = controls,
                    frame = frame,
                    errors = viewModel.errors,
                    actions = viewModel,
                    statusLabel = statusChipLabel(mode, controls.statusLabel),
                    bluetoothRemote = bluetoothRemote,
                    connection = {
                        ConnectionScreen(
                            ui = ConnectionUi(
                                mode = mode,
                                usbStatus = usb,
                                cameraModel = usbCamera.model,
                                serial = usbCamera.serial,
                                relay = relay,
                                remoteLink = remoteLink,
                            ),
                            actions = viewModel,
                        )
                    },
                    settings = {
                        SettingsTab(
                            preferences = prefs,
                            about = about,
                            onThemeChange = settings::setTheme,
                            onDynamicColorChange = settings::setDynamicColor,
                            onKeepScreenOnChange = settings::setKeepScreenOn,
                            logs = {
                                LogsScreen(
                                    entries = logEntries,
                                    onCopy = ::copyLogs,
                                    onClear = viewModel.log::clear,
                                )
                            },
                        )
                    },
                )
            }
        }
    }

    private fun copyLogs() {
        val header = "Canon Remote ${BuildConfig.VERSION_NAME} — " +
            "${Build.MANUFACTURER} ${Build.MODEL} — Android ${Build.VERSION.RELEASE}"
        // Keep the clip well under the binder transaction limit.
        val text = viewModel.log.export(header, maxChars = MAX_CLIP_CHARS)
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Canon Remote logs", text))
    }

    private companion object {
        const val MAX_CLIP_CHARS = 500_000
    }
}
