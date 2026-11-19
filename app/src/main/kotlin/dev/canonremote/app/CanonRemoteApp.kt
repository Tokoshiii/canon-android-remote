package dev.canonremote.app

import android.app.Application
import dev.canonremote.app.settings.AppSettings
import dev.canonremote.app.settings.SharedPrefsStore
import dev.canonremote.core.LogBus

class CanonRemoteApp : Application() {
    /** Single log bus shared by the whole app. */
    val log = LogBus()

    /** Interface preferences (theme, dynamic colors, screen on). */
    val settings: AppSettings by lazy {
        AppSettings(SharedPrefsStore(getSharedPreferences("settings", MODE_PRIVATE)))
    }

    /** Cameras (USB, relay, remote) shared by the UI and the relay service. */
    val hub: CameraHub by lazy { CameraHub(this, log, settings) }
}
