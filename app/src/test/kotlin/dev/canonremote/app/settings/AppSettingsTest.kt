package dev.canonremote.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {
    private class MemoryStore : KeyValueStore {
        val values = mutableMapOf<String, Any>()
        override fun getString(key: String): String? = values[key] as String?
        override fun getBoolean(key: String): Boolean? = values[key] as Boolean?
        override fun putString(key: String, value: String) { values[key] = value }
        override fun putBoolean(key: String, value: Boolean) { values[key] = value }
    }

    @Test
    fun defaultsFollowSystemWithDynamicColors() {
        val prefs = AppSettings(MemoryStore()).preferences.value
        assertEquals(ThemeMode.SYSTEM, prefs.theme)
        assertTrue(prefs.dynamicColor)
        assertFalse(prefs.keepScreenOn)
        assertEquals(AppMode.DIRECT, prefs.mode)
    }

    @Test
    fun changesAreEmittedAndPersisted() {
        val store = MemoryStore()
        val settings = AppSettings(store)
        settings.setTheme(ThemeMode.DARK)
        settings.setDynamicColor(false)
        settings.setKeepScreenOn(true)
        settings.setMode(AppMode.REMOTE)
        assertEquals(
            UiPreferences(ThemeMode.DARK, dynamicColor = false, keepScreenOn = true, mode = AppMode.REMOTE),
            settings.preferences.value,
        )
        assertEquals("remote", store.values["mode"])
        assertEquals(settings.preferences.value, AppSettings(store).preferences.value)
    }

    @Test
    fun unknownStoredThemeFallsBackToSystem() {
        val store = MemoryStore().apply { values["theme"] = "violet" }
        assertEquals(ThemeMode.SYSTEM, AppSettings(store).preferences.value.theme)
    }

    @Test
    fun modeIsStoredUnderModeKey() {
        val store = MemoryStore()
        AppSettings(store).setMode(AppMode.RELAY)
        assertEquals("relay", store.values["mode"])
        assertEquals(AppMode.RELAY, AppSettings(store).preferences.value.mode)
    }

    @Test
    fun unknownStoredModeFallsBackToDirect() {
        val store = MemoryStore().apply { values["mode"] = "teleport" }
        assertEquals(AppMode.DIRECT, AppSettings(store).preferences.value.mode)
    }
}
