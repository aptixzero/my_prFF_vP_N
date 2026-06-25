package com.neonvpn.app.config

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v3.8 FREE-CONFIG SOURCE — the *only* source for the Free Configs tab.
 *
 * MIGRATION (v3.8): the deprecated `prfgame/cccfigs` feed (configs_NN.txt, 10
 * files) is replaced by `prfgame/CC_new` (configs_NNN.txt, discovered at runtime
 * — see [CcNewFeed]). The low-level fetch / per-file parse / file-count discovery
 * now lives in [CcNewFeed]; this object owns the deterministic cursor.
 *
 * ── DETERMINISTIC 120-PER-PRESS CURSOR ─────────────────────────────────────
 *  • Every "Load More" (Search) returns the NEXT [BATCH_PER_PRESS] (=120) unique
 *    entries in deterministic, persisted catalog order:
 *        tap 1 → entries 1..120, tap 2 → 121..240, tap 3 → 241..360, …
 *  • "Entry N" = the Nth line counted in FILE ORDER (configs_000 lines 1..2200,
 *    then configs_001 lines 1..2200, …) AFTER the vless/vmess parse filter.
 *  • The catalog cursor [KEY_CURSOR] is a single monotonically-advancing integer
 *    persisted in SharedPreferences. When it passes the end of the catalog it
 *    WRAPS to 0. Simple deterministic wrap — no per-day seed, no per-user shuffle.
 *  • Deduplication is by canonical key (host:port:uuid / host:port:id) computed
 *    BEFORE assigning the visible "Server N" number, so duplicates never waste a
 *    slot. The visible "Server N" counter ([KEY_NAME_COUNTER]) keeps advancing
 *    monotonically (Server 1, 2, 3, … across presses) exactly as before.
 *
 * Only one feed file is held in memory at a time (see [CcNewFeed.parseFile]).
 */
object FreeConfigSource {

    private const val TAG = "FreeConfigSource"

    /** Entries returned per "Load More" press. */
    const val BATCH_PER_PRESS = 120

    private const val PREFS = "free_cc_new_v38"
    private const val KEY_CURSOR = "cc_new_cursor"        // global catalog line index
    private const val KEY_SEEDED = "seeded"
    /**
     * PERSISTENT, monotonically-increasing "Server N" name counter. Server 1, 2,
     * 3 … across consecutive presses; it does NOT restart per press and is NOT
     * derived from the in-memory list size. (v3.8: no per-rotation reset — the
     * CC_new feed has no logical "rotation reset" event, so numbering is purely
     * monotonic and survives restarts.)
     */
    private const val KEY_NAME_COUNTER = "name_counter"

    /** A single search batch result. */
    data class Batch(
        val configs: List<ServerConfig>,   // already renamed Server N
        val foundRaw: Int,                  // raw catalog lines consumed this press
        val reachedEnd: Boolean             // true if the cursor wrapped past the last file
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Ensure first-launch state: fresh install starts at cursor 0, Server 1. */
    suspend fun ensureFreshState(ctx: Context) {
        val p = prefs(ctx)
        if (!p.getBoolean(KEY_SEEDED, false)) {
            p.edit().putInt(KEY_CURSOR, 0).putInt(KEY_NAME_COUNTER, 0)
                .putBoolean(KEY_SEEDED, true).apply()
        }
        // Warm the file-count cache so the first press resolves quickly.
        try { CcNewFeed.fileCount(ctx) } catch (_: Throwable) {}
    }

    /** The next Server number that would be assigned (1-based for display). */
    fun peekNextServerNumber(ctx: Context): Int = prefs(ctx).getInt(KEY_NAME_COUNTER, 0) + 1

    /**
     * Pull the NEXT [BATCH_PER_PRESS] unique vless/vmess entries from the device's
     * persisted catalog cursor, advancing it as it goes. Walks the catalog in file
     * order (lazy: one file in memory at a time), skips entries whose canonical key
     * is already in [seenKeys] / already in the catalog dedup set, and wraps to the
     * start when the end is reached.
     *
     * @param onChunk (added so far this press, target=120, statusMessage)
     */
    suspend fun nextBatch(
        ctx: Context,
        startIndex: Int,                      // (legacy, ignored) kept for call-site compat
        seenKeys: MutableSet<String>,         // dedup against already-stored configs
        onChunk: (addedThisPress: Int, target: Int, status: String) -> Unit = { _, _, _ -> }
    ): Batch = withContext(Dispatchers.IO) {
        val p = prefs(ctx)
        val fileCount = CcNewFeed.fileCount(ctx).coerceAtLeast(1)
        val catalogLen = fileCount * CcNewFeed.MAX_LINES_PER_FILE   // upper bound for wrap math

        var cursor = p.getInt(KEY_CURSOR, 0).coerceAtLeast(0)
        var serverIndex = p.getInt(KEY_NAME_COUNTER, 0).coerceAtLeast(0)

        val collected = ArrayList<ServerConfig>(BATCH_PER_PRESS)
        var rawConsumed = 0
        var wrapped = false
        // Bound the walk so a catalog full of duplicates can never loop forever:
        // never scan more than the whole catalog (one full wrap) in a single press.
        var safetyBudget = catalogLen + CcNewFeed.MAX_LINES_PER_FILE

        onChunk(0, BATCH_PER_PRESS, "Loading free configs...")

        // The cursor is a GLOBAL line index. Convert to (file, lineInFile) and
        // walk forward, loading each file lazily and resuming mid-file.
        var fileIdx = (cursor / CcNewFeed.MAX_LINES_PER_FILE) % fileCount
        var lineInFile = cursor % CcNewFeed.MAX_LINES_PER_FILE
        var filesVisited = 0

        while (collected.size < BATCH_PER_PRESS && safetyBudget > 0 && filesVisited <= fileCount) {
            val links = CcNewFeed.parseFile(ctx, fileIdx)
            if (links == null) {
                // Unreachable file (and no cache): skip to the next file safely.
                fileIdx = (fileIdx + 1) % fileCount
                if (fileIdx == 0) wrapped = true
                lineInFile = 0
                cursor = fileIdx * CcNewFeed.MAX_LINES_PER_FILE
                filesVisited++
                continue
            }

            var i = lineInFile.coerceAtMost(links.size)
            while (i < links.size && collected.size < BATCH_PER_PRESS) {
                val link = links[i]
                i++
                cursor++
                rawConsumed++
                safetyBudget--
                val cfg = try { ConfigParser.parseSingleSafe(link) } catch (_: Throwable) { null }
                    ?: continue
                if (cfg.protocol != "vless" && cfg.protocol != "vmess") continue
                val key = ConfigParser.dedupKey(cfg)
                if (!seenKeys.add(key)) continue   // dedup BEFORE assigning N

                serverIndex++
                collected.add(cfg.copy(remark = "${ConfigFetcher.GENERIC_PREFIX} $serverIndex"))

                if (collected.size % 10 == 0 || collected.size == BATCH_PER_PRESS) {
                    onChunk(collected.size, BATCH_PER_PRESS, "Found ${collected.size}/$BATCH_PER_PRESS")
                }
            }

            if (i >= links.size) {
                // file exhausted → advance to the next file (wrap after the last)
                fileIdx = (fileIdx + 1) % fileCount
                if (fileIdx == 0) wrapped = true
                lineInFile = 0
                cursor = fileIdx * CcNewFeed.MAX_LINES_PER_FILE
                filesVisited++
            } else {
                // batch full mid-file — leave the cursor exactly where we stopped
                break
            }
        }

        // Persist the advanced cursor + the Server N counter.
        p.edit().putInt(KEY_CURSOR, cursor).putInt(KEY_NAME_COUNTER, serverIndex).apply()

        onChunk(collected.size, BATCH_PER_PRESS, "Added ${collected.size} configs")
        Log.i(TAG, "press: +${collected.size} (raw $rawConsumed, cursor→$cursor, wrapped=$wrapped)")
        Batch(collected, rawConsumed, wrapped)
    }
}
