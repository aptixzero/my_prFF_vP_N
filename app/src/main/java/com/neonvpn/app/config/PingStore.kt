package com.neonvpn.app.config

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Persists the per-config ping/latency results (id -> ms) so they SURVIVE
 * tab switches, app backgrounding and full restarts.
 *
 * This is the fix for the "I pinged everything, switched tabs / reopened, and
 * the results were gone" bug. The ConfigsFragment / FreeConfigsFragment read
 * the saved map on view-create and write it back after every ping run, so the
 * little green ● latency badges persist exactly like the configs themselves
 * until the user explicitly clears them.
 *
 * Two independent buckets:
 *   - "free"  → results for the auto-collected Free Configs list
 *   - "my"    → results for the user's hand-saved My Configs list
 */
class PingStore(context: Context, bucket: String) {

    private val prefs = context.getSharedPreferences("neonvpn_ping_$bucket", Context.MODE_PRIVATE)
    private val gson = Gson()

    /** Load the full id -> latency(ms) map. */
    fun load(): MutableMap<String, Long> {
        val json = prefs.getString(KEY, null) ?: return mutableMapOf()
        return try {
            val type = object : TypeToken<MutableMap<String, Long>>() {}.type
            gson.fromJson(json, type) ?: mutableMapOf()
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    /** Persist the whole map (only finished results — testing markers are dropped). */
    fun save(map: Map<String, Long>) {
        // never persist the transient "testing…" sentinel
        val clean = map.filterValues { it != Long.MIN_VALUE }
        prefs.edit().putString(KEY, gson.toJson(clean)).apply()
    }

    fun clear() = prefs.edit().remove(KEY).apply().also { clearUnstable() }

    // --- v3.8 §4.4 unstable flag ---------------------------------------
    // Ids that flapped (reachable -> failed) in the last sweep. Persisted so the
    // "unstable" demote survives a tab switch / restart and the row keeps sorting
    // below stable green nodes until it proves itself reachable again.

    /** Load the set of ids currently flagged unstable. */
    fun loadUnstable(): MutableSet<String> {
        val json = prefs.getString(KEY_UNSTABLE, null) ?: return mutableSetOf()
        return try {
            val type = object : TypeToken<MutableSet<String>>() {}.type
            gson.fromJson(json, type) ?: mutableSetOf()
        } catch (_: Exception) {
            mutableSetOf()
        }
    }

    /** Persist the set of ids currently flagged unstable. */
    fun saveUnstable(ids: Set<String>) {
        prefs.edit().putString(KEY_UNSTABLE, gson.toJson(ids)).apply()
    }

    fun clearUnstable() = prefs.edit().remove(KEY_UNSTABLE).apply()

    companion object {
        private const val KEY = "ping_results"
        private const val KEY_UNSTABLE = "ping_unstable"
        const val FREE = "free"
        const val MY = "my"
    }
}
