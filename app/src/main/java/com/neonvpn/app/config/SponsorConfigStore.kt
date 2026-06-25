package com.neonvpn.app.config

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Persistence bucket for the "Sponsor Configs" list — premium / sponsored
 * servers, kept separate from Free and My Configs. Currently no sponsor configs
 * are bundled, so the list starts empty; populate it via [replaceAll] when
 * sponsor servers become available.
 */
class SponsorConfigStore(context: Context) {

    private val prefs = context.getSharedPreferences("neonvpn_sponsor_prefs", Context.MODE_PRIVATE)
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

    fun replaceAll(list: List<ServerConfig>) = save(list)

    fun clear() = prefs.edit().remove(KEY).apply()

    companion object {
        private const val KEY = "sponsor_servers"
    }
}
