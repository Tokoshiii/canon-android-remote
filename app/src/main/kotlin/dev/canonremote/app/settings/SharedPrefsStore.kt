package dev.canonremote.app.settings

import android.content.SharedPreferences

class SharedPrefsStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun getBoolean(key: String): Boolean? =
        if (prefs.contains(key)) prefs.getBoolean(key, false) else null
    override fun getInt(key: String): Int? =
        if (prefs.contains(key)) prefs.getInt(key, 0) else null
    override fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun putBoolean(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
    override fun putInt(key: String, value: Int) { prefs.edit().putInt(key, value).apply() }
}
