package dev.canonremote.app.settings

import dev.canonremote.app.relay.RelayLink
import dev.canonremote.core.FocusStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {
    private class MemoryStore : KeyValueStore {
        val values = mutableMapOf<String, Any>()
        override fun getString(key: String): String? = values[key] as String?
        override fun getBoolean(key: String): Boolean? = values[key] as Boolean?
        override fun getInt(key: String): Int? = values[key] as Int?
        override fun putInt(key: String, value: Int) { values[key] = value }
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

    @Test
    fun newPreferencesHaveSpecDefaults() {
        val prefs = AppSettings(MemoryStore()).preferences.value
        assertTrue(prefs.shutterHaptic)
        assertFalse(prefs.shutterSound)
        assertTrue(prefs.tickHaptic)
        assertEquals(FocusStepSize.LARGE, prefs.focusStep)
        assertEquals(3, prefs.focusStep.steps)
        assertEquals(2, FocusStepSize.MEDIUM.steps)
        assertEquals(0, prefs.timerDelay)
        assertTrue(prefs.rememberRelayLinks)
        assertEquals(setOf(RelayLink.WIFI), prefs.relayLinks)
        assertFalse(prefs.devMode)
    }

    @Test
    fun newPreferencesArePersistedAndReloaded() {
        val store = MemoryStore()
        val settings = AppSettings(store)
        settings.setShutterHaptic(false)
        settings.setShutterSound(true)
        settings.setTickHaptic(false)
        settings.setFocusStep(FocusStepSize.MEDIUM)
        settings.setTimerDelay(10)
        settings.setRememberRelayLinks(false)
        settings.setRelayLinks(setOf(RelayLink.HOTSPOT, RelayLink.BLUETOOTH))
        settings.setDevMode(true)
        val reloaded = AppSettings(store).preferences.value
        assertEquals(settings.preferences.value, reloaded)
        assertFalse(reloaded.shutterHaptic)
        assertTrue(reloaded.shutterSound)
        assertEquals(FocusStepSize.MEDIUM, reloaded.focusStep)
        assertEquals(10, reloaded.timerDelay)
        assertEquals(setOf(RelayLink.HOTSPOT, RelayLink.BLUETOOTH), reloaded.relayLinks)
    }

    @Test
    fun emptyRelayLinkSetIsKeptAndUnknownKeysIgnored() {
        val store = MemoryStore()
        AppSettings(store).setRelayLinks(emptySet())
        assertEquals(emptySet<RelayLink>(), AppSettings(store).preferences.value.relayLinks)
        store.values["relay_links"] = "hotspot,laser"
        assertEquals(setOf(RelayLink.HOTSPOT), AppSettings(store).preferences.value.relayLinks)
    }

    @Test
    fun invalidTimerDelayIsRejectedAndStoredGarbageFallsBack() {
        val store = MemoryStore()
        val settings = AppSettings(store)
        settings.setTimerDelay(7)
        assertEquals(0, settings.preferences.value.timerDelay)
        assertFalse(store.values.containsKey("timer_delay"))
        store.values["timer_delay"] = 5
        assertEquals(0, AppSettings(store).preferences.value.timerDelay)
    }

    @Test
    fun unknownFocusStepFallsBackToLarge() {
        val store = MemoryStore().apply { values["focus_step"] = "huge" }
        assertEquals(FocusStepSize.LARGE, AppSettings(store).preferences.value.focusStep)
    }

    @Test
    fun relayLinksAreStoredUnderStableKeys() {
        val store = MemoryStore()
        AppSettings(store).setRelayLinks(setOf(RelayLink.WIFI, RelayLink.HOTSPOT, RelayLink.BLUETOOTH))
        assertEquals(setOf("wifi_local", "hotspot", "bluetooth"), (store.values["relay_links"] as String).split(',').toSet())
        assertEquals(RelayLink.WIFI, RelayLink.fromKey("wifi_local"))
        assertEquals(null, RelayLink.fromKey("WIFI"))
    }

    @Test
    fun relayLinksAreRestoredOnlyWhenRemembered() {
        val saved = UiPreferences(relayLinks = setOf(RelayLink.BLUETOOTH), rememberRelayLinks = true)
        assertEquals(setOf(RelayLink.BLUETOOTH), saved.initialRelayLinks())
        assertEquals(DEFAULT_RELAY_LINKS, saved.copy(rememberRelayLinks = false).initialRelayLinks())
        assertEquals(setOf(RelayLink.WIFI), DEFAULT_RELAY_LINKS)
    }

    @Test
    fun focusStepSizeMapsToCameraSteps() {
        assertEquals(FocusStep.NEAR_2, FocusStepSize.MEDIUM.near)
        assertEquals(FocusStep.FAR_2, FocusStepSize.MEDIUM.far)
        assertEquals(FocusStep.NEAR_3, FocusStepSize.LARGE.near)
        assertEquals(FocusStep.FAR_3, FocusStepSize.LARGE.far)
    }
}
