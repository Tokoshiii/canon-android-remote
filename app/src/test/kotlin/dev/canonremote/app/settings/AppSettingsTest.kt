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
    }

    @Test
    fun changesAreEmittedAndPersisted() {
        val store = MemoryStore()
        val settings = AppSettings(store)
        settings.setTheme(ThemeMode.DARK)
        settings.setDynamicColor(false)
        settings.setKeepScreenOn(true)
        assertEquals(UiPreferences(ThemeMode.DARK, dynamicColor = false, keepScreenOn = true), settings.preferences.value)
        assertEquals(settings.preferences.value, AppSettings(store).preferences.value)
    }

    @Test
    fun unknownStoredThemeFallsBackToSystem() {
        val store = MemoryStore().apply { values["theme"] = "violet" }
        assertEquals(ThemeMode.SYSTEM, AppSettings(store).preferences.value.theme)
    }
}
