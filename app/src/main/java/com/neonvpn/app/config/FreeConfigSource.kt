package com.neonvpn.app.config

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v4.6 FREE-CONFIG SOURCE — reads directly from the 50 LIVE feeds in
 * [LiveSources] (the dead `aptixzero/con_new` mirror is gone).
 *
 * ── 240-PER-PRESS: 120 VLESS + 120 VMESS ────────────────────────────────────
 *  Every batch ([nextBatch]) collects up to [VLESS_PER_PRESS] (=120) unique
 *  VLESS and [VMESS_PER_PRESS] (=120) unique VMESS configs — 240 total — and
 *  INTERLEAVES them (vless, vmess, vless, vmess …) so the resulting list is an
 *  even half-and-half mix, exactly as required.
 *
 * ── DON'T SCAN ALL 50 AT ONCE ───────────────────────────────────────────────
 *  We keep a persistent per-kind SOURCE cursor. Each press walks only as many
 *  sources as it needs to fill its 120/120 quota (typically 1–3 feeds per kind),
 *  then remembers where it stopped. The next press resumes from the next source,
 *  wrapping to the first source at the end. This keeps every press fast and
 *  light on an Iranian mobile link.
 *
 * ── MEMORY + 30-DAY RESET ───────────────────────────────────────────────────
 *  Dedup is by canonical key ([ConfigParser.dedupKey]) persisted in
 *  [SeenConfigStore] so a config that was ever added is never re-added — while
 *  the stored set stays bounded (never bloats the cache). After 30 days the
 *  whole pipeline resets (seen-set cleared, cursors + Server-N restart from the
 *  first source); the user's saved My-Configs are never touched.
 *
 * ── NEUTRAL NAMING ──────────────────────────────────────────────────────────
 *  Configs are renamed to a generic "Server N" (monotonic, persisted). The real
 *  feed name / channel branding is NEVER shown.
 */
object FreeConfigSource {

    private const val TAG = "FreeConfigSource"

    const val VLESS_PER_PRESS = 120
    const val VMESS_PER_PRESS = 120

    /** Total configs a single press yields. */
    const val BATCH_PER_PRESS = VLESS_PER_PRESS + VMESS_PER_PRESS   // 240

    private const val PREFS = "free_live_v46"
    private const val KEY_VLESS_CURSOR = "vless_src_cursor"
    private const val KEY_VMESS_CURSOR = "vmess_src_cursor"
    private const val KEY_NAME_COUNTER = "name_counter"
    private const val KEY_SEEDED = "seeded"

    /**
     * Max source feeds walked per kind in one press. v5.9 — raised from 6 to 12
     * so that when the first few feeds return only already-seen configs, the
     * press keeps walking further sources to still fill its 120/120 quota with
     * FRESH configs (this is part of the "a repeat Auto Test must keep adding
     * configs" fix). There are 25 feeds per kind, so this is still under half.
     */
    private const val MAX_SOURCES_PER_PRESS = 12

    data class Batch(
        val configs: List<ServerConfig>,   // already renamed Server N, interleaved
        val foundRaw: Int,
        val reachedEnd: Boolean,
        /**
         * v5.9 — true if AT LEAST ONE source feed was successfully opened during
         * this press (regardless of whether it yielded NEW configs). This lets a
         * caller tell "empty because offline" (reachedSource=false) apart from
         * "empty because everything we saw was already-seen" (reachedSource=true)
         * — the latter must trigger a dedup-memory reset so a repeat Auto Test
         * can serve currently-live configs again instead of coming back empty.
         */
        val reachedSource: Boolean = false
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Ensure first-launch state + honour the 30-day reset. */
    suspend fun ensureFreshState(ctx: Context) {
        val p = prefs(ctx)
        if (!p.getBoolean(KEY_SEEDED, false)) {
            p.edit().putInt(KEY_VLESS_CURSOR, 0).putInt(KEY_VMESS_CURSOR, 0)
                .putInt(KEY_NAME_COUNTER, 0).putBoolean(KEY_SEEDED, true).apply()
        }
        // 30-day reset: clear seen memory + restart cursors from the first source.
        if (SeenConfigStore.shouldReset(ctx)) {
            SeenConfigStore.performReset(ctx)
            p.edit().putInt(KEY_VLESS_CURSOR, 0).putInt(KEY_VMESS_CURSOR, 0)
                .putInt(KEY_NAME_COUNTER, 0).apply()
            // v6.0 — also drop the sticky source bond so the fresh 30-day cycle
            // re-discovers which feed is reachable from scratch.
            ConnectedSourceStore.clear(ctx)
            Log.i(TAG, "30-day reset performed — restarting from source #1")
        }
    }

    fun peekNextServerNumber(ctx: Context): Int = prefs(ctx).getInt(KEY_NAME_COUNTER, 0) + 1

    /**
     * Pull the next 120 VLESS + 120 VMESS unique configs, INTERLEAVED.
     *
     * @param seenKeys in-memory dedup set (seeded from [SeenConfigStore]); this
     *                 method also persists the union back to [SeenConfigStore].
     */
    suspend fun nextBatch(
        ctx: Context,
        startIndex: Int = 0,                  // legacy, ignored
        seenKeys: MutableSet<String>,
        onChunk: (addedThisPress: Int, target: Int, status: String) -> Unit = { _, _, _ -> }
    ): Batch = withContext(Dispatchers.IO) {
        val p = prefs(ctx)
        var serverIndex = p.getInt(KEY_NAME_COUNTER, 0).coerceAtLeast(0)

        onChunk(0, BATCH_PER_PRESS, "Loading configs…")

        // Collect each kind independently from its own source cursor.
        val vlessOut = ArrayList<ServerConfig>(VLESS_PER_PRESS)
        val vmessOut = ArrayList<ServerConfig>(VMESS_PER_PRESS)
        val reached = java.util.concurrent.atomic.AtomicBoolean(false)

        // v6.0 — STICKY SOURCE. If the user is already bonded to a working source
        // (found by a previous connectivity probe / press), START from THAT source
        // so "the next 240 configs come from the same source we connected to". The
        // bond is only set when a source is actually reached and yields configs, so
        // resuming from it keeps every subsequent batch coming from the same, known-
        // reachable feed. Falls back to the rolling cursor when there is no bond yet.
        val bondedVless = ConnectedSourceStore.vlessSource(ctx)
        val bondedVmess = ConnectedSourceStore.vmessSource(ctx)
        val vlessStart = if (bondedVless >= 0) bondedVless else p.getInt(KEY_VLESS_CURSOR, 0)
        val vmessStart = if (bondedVmess >= 0) bondedVmess else p.getInt(KEY_VMESS_CURSOR, 0)

        // Records which source index actually yielded configs for each kind so we
        // can bond to it (the FIRST reachable source that gives us any config).
        val vlessHitSrc = java.util.concurrent.atomic.AtomicInteger(-1)
        val vmessHitSrc = java.util.concurrent.atomic.AtomicInteger(-1)

        var vlessCursor = collectKind(
            ctx, LiveSources.VLESS, vlessStart,
            VLESS_PER_PRESS, seenKeys, vlessOut, reached, vlessHitSrc
        )
        onChunk(vlessOut.size, BATCH_PER_PRESS, "Found ${vlessOut.size} VLESS")

        var vmessCursor = collectKind(
            ctx, LiveSources.VMESS, vmessStart,
            VMESS_PER_PRESS, seenKeys, vmessOut, reached, vmessHitSrc
        )
        onChunk(vlessOut.size + vmessOut.size, BATCH_PER_PRESS,
            "Found ${vlessOut.size + vmessOut.size} configs")

        // v6.0 — bond to the first source that yielded configs this press (only if
        // we are not already bonded, so the bond stays stable across the 240-batches).
        if (bondedVless < 0 && vlessHitSrc.get() >= 0) {
            ConnectedSourceStore.setVlessSource(ctx, vlessHitSrc.get())
        }
        if (bondedVmess < 0 && vmessHitSrc.get() >= 0) {
            ConnectedSourceStore.setVmessSource(ctx, vmessHitSrc.get())
        }

        // Interleave vless / vmess so the mix is even (vless, vmess, vless …).
        val interleaved = ArrayList<ServerConfig>(vlessOut.size + vmessOut.size)
        val max = maxOf(vlessOut.size, vmessOut.size)
        for (i in 0 until max) {
            if (i < vlessOut.size) interleaved.add(vlessOut[i])
            if (i < vmessOut.size) interleaved.add(vmessOut[i])
        }

        // Assign monotonic Server N names in final (interleaved) order.
        // v5.1 — the name is ALSO baked into the link's #remark fragment so it
        // stays "Server N" when the config is copied into any other v2ray client.
        val named = interleaved.map { cfg ->
            serverIndex++
            val name = "${ConfigFetcher.GENERIC_PREFIX} $serverIndex"
            val relinked = ConfigParser.rewriteRemark(cfg.rawLink, name)
            cfg.copy(remark = name, rawLink = relinked)
        }

        // Persist cursors + name counter + the seen memory.
        p.edit()
            .putInt(KEY_VLESS_CURSOR, vlessCursor)
            .putInt(KEY_VMESS_CURSOR, vmessCursor)
            .putInt(KEY_NAME_COUNTER, serverIndex)
            .apply()
        SeenConfigStore.save(ctx, seenKeys)

        onChunk(named.size, BATCH_PER_PRESS, "Added ${named.size} configs")
        Log.i(TAG, "press: +${named.size} (vless=${vlessOut.size}, vmess=${vmessOut.size}, " +
            "vlessCursor→$vlessCursor, vmessCursor→$vmessCursor, reached=${reached.get()})")
        Batch(named, named.size, reachedEnd = false, reachedSource = reached.get())
    }

    /**
     * Fill [out] with up to [need] unique configs of one [kind], walking [sources]
     * from [startCursor] forward (wrapping), fetching at most
     * [MAX_SOURCES_PER_PRESS] feeds. Returns the NEXT cursor to resume from.
     *
     * @param hitSrc set to the index of the FIRST source that yields at least one
     *               fresh config (used to bond the user to a working source).
     */
    private suspend fun collectKind(
        ctx: Context,
        sources: List<LiveSources.Src>,
        startCursor: Int,
        need: Int,
        seenKeys: MutableSet<String>,
        out: MutableList<ServerConfig>,
        reached: java.util.concurrent.atomic.AtomicBoolean,
        hitSrc: java.util.concurrent.atomic.AtomicInteger? = null
    ): Int {
        if (sources.isEmpty()) return startCursor
        var cursor = ((startCursor % sources.size) + sources.size) % sources.size
        var sourcesWalked = 0
        while (out.size < need && sourcesWalked < MAX_SOURCES_PER_PRESS && sourcesWalked < sources.size) {
            val src = sources[cursor]
            val body = try { SourceFetcher.fetch(src.url) } catch (_: Throwable) { null }
            if (!body.isNullOrBlank()) {
                // v5.9 — we opened a source successfully → the feeds ARE reachable.
                // This is recorded even when every link it yields is already-seen,
                // so a caller can tell "offline" apart from "all duplicates".
                reached.set(true)
                val links = try { SourceFetcher.extractLinks(body, src.kind) } catch (_: Throwable) { emptyList() }
                val before = out.size
                for (link in links) {
                    if (out.size >= need) break
                    val cfg = try { ConfigParser.parseSingleSafe(link) } catch (_: Throwable) { null } ?: continue
                    if (cfg.protocol != "vless" && cfg.protocol != "vmess") continue
                    val key = ConfigParser.dedupKey(cfg)
                    if (!seenKeys.add(key)) continue
                    out.add(cfg)
                }
                // v6.0 — bond to the FIRST source that actually produced configs.
                if (hitSrc != null && out.size > before && hitSrc.get() < 0) {
                    hitSrc.set(cursor)
                }
            }
            // advance to next source (wrap), whether or not it filled the quota
            cursor = (cursor + 1) % sources.size
            sourcesWalked++
        }
        return cursor
    }
}
