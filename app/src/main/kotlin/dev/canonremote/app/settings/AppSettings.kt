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

data class UiPreferences(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val keepScreenOn: Boolean = false,
)

/** Minimal persistent key-value storage, so the settings logic stays testable on the JVM. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun getBoolean(key: String): Boolean?
    fun putString(key: String, value: String)
    fun putBoolean(key: String, value: Boolean)
}

/** Interface preferences: read once from [store], then kept in a [StateFlow] and written through. */
class AppSettings(private val store: KeyValueStore) {
    private val state = MutableStateFlow(
        UiPreferences(
            theme = ThemeMode.fromKey(store.getString(KEY_THEME)),
            dynamicColor = store.getBoolean(KEY_DYNAMIC_COLOR) ?: true,
            keepScreenOn = store.getBoolean(KEY_KEEP_SCREEN_ON) ?: false,
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

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_DYNAMIC_COLOR = "dynamic_color"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    }
}
