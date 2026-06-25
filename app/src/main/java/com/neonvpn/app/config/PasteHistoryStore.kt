package com.neonvpn.app.config

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * v3.8 — OPT-IN paste history (privacy-first).
 *
 * When the user enables "Remember pasted configs for quick re-import" in Settings,
 * we keep a small local-only list of the vless/vmess links they have pasted INSIDE
 * the app, so a future paste can also re-merge previously-seen links.
 *
 * Hard privacy guarantees (enforced here):
 *   • Only strings that contain at least one `vless://` / `vmess://` substring are
 *     stored, and ONLY the matched link substrings — never arbitrary clipboard text.
 *   • Max [MAX_ENTRIES] (50). Oldest entries are evicted first.
 *   • Each entry expires after [TTL_MS] (14 days).
 *   • Stored locally in SharedPreferences (JSON). Never sent off-device.
 *   • Appended ONLY when the user actively performs Paste in-app — there is NO
 *     background clipboard polling anywhere.
 */
class PasteHistoryStore(context: Context) {

    private val prefs = context.getSharedPreferences("neonvpn_paste_history", Context.MODE_PRIVATE)

    /** A regex that extracts ONLY vless/vmess link substrings (rule 4). */
    private val linkRegex = Regex("""\b(?:vless|vmess)://[^\s'"<>]+""")

    /** Append every vless/vmess substring found in [rawText] (no-op if disabled). */
    fun append(rawText: String) {
        if (rawText.isBlank()) return
        val matches = linkRegex.findAll(rawText).map { it.value }.toList()
        if (matches.isEmpty()) return

        val now = System.currentTimeMillis()
        val entries = loadEntries().toMutableList()
        // de-dup by link value; refresh timestamp if already present
        val byLink = LinkedHashMap<String, Long>()
        for (e in entries) byLink[e.first] = e.second
        for (link in matches) byLink[link] = now

        var list = byLink.entries
            .map { it.key to it.value }
            .filter { now - it.second < TTL_MS }       // drop expired
            .sortedByDescending { it.second }          // newest first
        if (list.size > MAX_ENTRIES) list = list.take(MAX_ENTRIES)

        save(list)
    }

    /** The non-expired stored links (newest first). */
    fun links(): List<String> {
        val now = System.currentTimeMillis()
        return loadEntries()
            .filter { now - it.second < TTL_MS }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    fun clear() = prefs.edit().remove(KEY).apply()

    // ------------------------------------------------------------- internals
    private fun loadEntries(): List<Pair<String, Long>> {
        val json = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            val out = ArrayList<Pair<String, Long>>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val link = o.optString("l", "")
                val ts = o.optLong("t", 0L)
                if (link.isNotBlank()) out.add(link to ts)
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun save(list: List<Pair<String, Long>>) {
        val arr = JSONArray()
        for ((link, ts) in list) {
            arr.put(JSONObject().put("l", link).put("t", ts))
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val KEY = "paste_history_configs"
        const val MAX_ENTRIES = 50
        const val TTL_MS = 14L * 24 * 60 * 60 * 1000   // 14 days
    }
}
