package dev.canonremote.app.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ThemeMode(val key: String, val label: String) {
    SYSTEM("system", "Système"),
    LIGHT("light", "Clair"),
    DARK("dark", "Sombre");

    companion object {
        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

/** How the app reaches the camera: USB, USB shared with a remote, or through a relay. */
enum class AppMode(val key: String, val label: String) {
    DIRECT("direct", "Direct"),
    RELAY("relay", "Relais"),
    REMOTE("remote", "Télécommande");

    companion object {
        fun fromKey(key: String?): AppMode = entries.firstOrNull { it.key == key } ?: DIRECT
    }
}

/** Distance of one focus nudge, in camera steps (1 = small is the fixed fine button). */
enum class FocusStepSize(val key: String, val steps: Int) {
    MEDIUM("medium", 2),
    LARGE("large", 3);

    companion object {
        fun fromKey(key: String?): FocusStepSize = entries.firstOrNull { it.key == key } ?: LARGE
    }
}

/** Links a relay may be started with; persisted by [key]. */
enum class RelayLinkPref(val key: String) {
    WIFI_LOCAL("wifi_local"),
    HOTSPOT("hotspot"),
    BLUETOOTH("bluetooth");

    companion object {
        fun fromKey(key: String?): RelayLinkPref? = entries.firstOrNull { it.key == key }
    }
}

/** Shutter timer delays offered by the app, in seconds (0 = off). */
val TIMER_DELAYS: List<Int> = listOf(0, 2, 10)

data class UiPreferences(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val keepScreenOn: Boolean = false,
    val mode: AppMode = AppMode.DIRECT,
    val shutterHaptic: Boolean = true,
    val shutterSound: Boolean = false,
    val tickHaptic: Boolean = true,
    val focusStep: FocusStepSize = FocusStepSize.LARGE,
    val timerDelay: Int = 0,
    val rememberRelayLinks: Boolean = true,
    val relayLinks: Set<RelayLinkPref> = setOf(RelayLinkPref.WIFI_LOCAL),
    val devMode: Boolean = false,
)

/** Minimal persistent key-value storage, so the settings logic stays testable on the JVM. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun getBoolean(key: String): Boolean?
    fun getInt(key: String): Int?
    fun putString(key: String, value: String)
    fun putBoolean(key: String, value: Boolean)
    fun putInt(key: String, value: Int)
}

/** Interface preferences: read once from [store], then kept in a [StateFlow] and written through. */
class AppSettings(private val store: KeyValueStore) {
    private val state = MutableStateFlow(
        UiPreferences(
            theme = ThemeMode.fromKey(store.getString(KEY_THEME)),
            dynamicColor = store.getBoolean(KEY_DYNAMIC_COLOR) ?: true,
            keepScreenOn = store.getBoolean(KEY_KEEP_SCREEN_ON) ?: false,
            mode = AppMode.fromKey(store.getString(KEY_MODE)),
            shutterHaptic = store.getBoolean(KEY_SHUTTER_HAPTIC) ?: true,
            shutterSound = store.getBoolean(KEY_SHUTTER_SOUND) ?: false,
            tickHaptic = store.getBoolean(KEY_TICK_HAPTIC) ?: true,
            focusStep = FocusStepSize.fromKey(store.getString(KEY_FOCUS_STEP)),
            timerDelay = store.getInt(KEY_TIMER_DELAY)?.takeIf { it in TIMER_DELAYS } ?: 0,
            rememberRelayLinks = store.getBoolean(KEY_REMEMBER_RELAY_LINKS) ?: true,
            relayLinks = readRelayLinks(store.getString(KEY_RELAY_LINKS)),
            devMode = store.getBoolean(KEY_DEV_MODE) ?: false,
        ),
    )
    val preferences: StateFlow<UiPreferences> = state.asStateFlow()

    fun setTheme(theme: ThemeMode) {
        store.putString(KEY_THEME, theme.key)
        state.update { it.copy(theme = theme) }
    }

    fun setDynamicColor(enabled: Boolean) {
        store.putBoolean(KEY_DYNAMIC_COLOR, enabled)
        state.update { it.copy(dynamicColor = enabled) }
    }

    fun setKeepScreenOn(enabled: Boolean) {
        store.putBoolean(KEY_KEEP_SCREEN_ON, enabled)
        state.update { it.copy(keepScreenOn = enabled) }
    }

    fun setMode(mode: AppMode) {
        store.putString(KEY_MODE, mode.key)
        state.update { it.copy(mode = mode) }
    }

    fun setShutterHaptic(enabled: Boolean) {
        store.putBoolean(KEY_SHUTTER_HAPTIC, enabled)
        state.update { it.copy(shutterHaptic = enabled) }
    }

    fun setShutterSound(enabled: Boolean) {
        store.putBoolean(KEY_SHUTTER_SOUND, enabled)
        state.update { it.copy(shutterSound = enabled) }
    }

    fun setTickHaptic(enabled: Boolean) {
        store.putBoolean(KEY_TICK_HAPTIC, enabled)
        state.update { it.copy(tickHaptic = enabled) }
    }

    fun setFocusStep(step: FocusStepSize) {
        store.putString(KEY_FOCUS_STEP, step.key)
        state.update { it.copy(focusStep = step) }
    }

    /** [seconds] must be one of [TIMER_DELAYS]; anything else is ignored. */
    fun setTimerDelay(seconds: Int) {
        if (seconds !in TIMER_DELAYS) return
        store.putInt(KEY_TIMER_DELAY, seconds)
        state.update { it.copy(timerDelay = seconds) }
    }

    fun setRememberRelayLinks(enabled: Boolean) {
        store.putBoolean(KEY_REMEMBER_RELAY_LINKS, enabled)
        state.update { it.copy(rememberRelayLinks = enabled) }
    }

    fun setRelayLinks(links: Set<RelayLinkPref>) {
        store.putString(KEY_RELAY_LINKS, links.joinToString(",") { it.key })
        state.update { it.copy(relayLinks = links) }
    }

    fun setDevMode(enabled: Boolean) {
        store.putBoolean(KEY_DEV_MODE, enabled)
        state.update { it.copy(devMode = enabled) }
    }

    private fun readRelayLinks(raw: String?): Set<RelayLinkPref> =
        if (raw == null) setOf(RelayLinkPref.WIFI_LOCAL)
        else raw.split(',').mapNotNull { RelayLinkPref.fromKey(it) }.toSet()

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_DYNAMIC_COLOR = "dynamic_color"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_MODE = "mode"
        const val KEY_SHUTTER_HAPTIC = "shutter_haptic"
        const val KEY_SHUTTER_SOUND = "shutter_sound"
        const val KEY_TICK_HAPTIC = "tick_haptic"
        const val KEY_FOCUS_STEP = "focus_step"
        const val KEY_TIMER_DELAY = "timer_delay"
        const val KEY_REMEMBER_RELAY_LINKS = "remember_relay_links"
        const val KEY_RELAY_LINKS = "relay_links"
        const val KEY_DEV_MODE = "dev_mode"
    }
}
