package dev.canonremote.app

import android.app.Application
import dev.canonremote.core.LogBus

class CanonRemoteApp : Application() {
    /** Single log bus shared by the whole app. */
    val log = LogBus()
}
