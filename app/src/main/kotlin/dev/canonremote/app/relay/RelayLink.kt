package dev.canonremote.app.relay

/** Links a relay can offer to a remote; [key] is how the choice is persisted (see AppSettings). */
enum class RelayLink(val label: String, val key: String) {
    WIFI("Wi-Fi local", "wifi_local"),
    HOTSPOT("Point d'accès", "hotspot"),
    BLUETOOTH("Bluetooth", "bluetooth");

    companion object {
        fun fromKey(key: String?): RelayLink? = entries.firstOrNull { it.key == key }
    }
}
