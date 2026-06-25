package com.neonvpn.app.config

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Separate persistence bucket for the auto-collected "Free Configs" list, kept
 * apart from the user's hand-saved "My Configs". A START SEARCH run replaces the
 * whole list with a fresh batch (old 80 are wiped, new 80 take their place).
 */
class FreeConfigStore(context: Context) {

    private val prefs = context.getSharedPreferences("neonvpn_free_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun get(): MutableList<ServerConfig> {
        val json = prefs.getString(KEY, null) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<ServerConfig>>() {}.type
            gson.fromJson(json, type) ?: mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun save(list: List<ServerConfig>) {
        prefs.edit().putString(KEY, gson.toJson(list)).apply()
    }

    /** Wipe the previous batch and store a brand-new one. */
    fun replaceAll(list: List<ServerConfig>) = save(list)

    fun clear() = prefs.edit().remove(KEY).apply()

    companion object {
        private const val KEY = "free_servers"
    }
}
