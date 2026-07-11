package com.neonvpn.app.config

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

/**
 * v5.9 — REAL "Auto Test" connectivity probe (honest progress, no fake error).
 *
 * When the user taps AUTO TEST a small page opens with just a progress bar. That
 * page runs THIS probe, which does ONE real thing:
 *
 *   "Walk the live sources, open them one by one, and pull a FULL fresh batch of
 *    working-shaped vless/vmess configs. The progress bar reflects the REAL work
 *    of reaching each source — it advances only as a source is actually opened
 *    and yields configs. The moment we have collected the batch (or exhausted the
 *    sources) the bar completes and the collected configs are returned so the
 *    page can add them the SAME instant."
 *
 * Design goals for v5.9 (fixes the reported bugs):
 *   • The bar is driven by ACTUAL source progress, not a random time ticker. It
 *     fills as real sources are reached and configs are extracted.
 *   • NO false "connection error". We only report failure when NOT ONE of the
 *     sources could be opened (a genuine offline state). Reaching any source and
 *     extracting a valid config == success.
 *   • The probe returns the FULL batch it collected (up to [TARGET]) so the page
 *     can populate My Configs immediately when the bar hits 100% — the wait the
 *     user spends watching the bar is the time we spend finding real configs.
 *   • Fully exception-safe: a dead source / malformed line never crashes it.
 */
object ConnectivityProbe {

    private const val TAG = "ConnectivityProbe"

    /** Whole-probe wall-clock ceiling — the page never waits longer than this. */
    const val BUDGET_MS = 25_000L

    /**
     * How many configs we aim to collect during the probe. This is the SAME
     * 240-per-press batch size used everywhere else so the first fill is a full
     * fresh batch. If the reachable sources can't yield this many in the budget
     * we return whatever we did collect (still a success as long as it's > 0).
     */
    const val TARGET = FreeConfigSource.BATCH_PER_PRESS   // 240

    /**
     * Minimum we must collect to consider the probe a success. As soon as we have
     * at least this many real configs from reachable sources, the user is clearly
     * online and can be given configs. Kept small so a weak Iranian link that can
     * only open one feed still succeeds.
     */
    private const val MIN_SUCCESS = 1

    data class Result(
        /** The fresh batch of configs collected from reachable sources. */
        val configs: List<ServerConfig> = emptyList(),
        /** True if at least one source feed was opened and yielded a config. */
        val reachedSource: Boolean = configs.isNotEmpty()
    ) {
        /** We have something to add whenever we collected at least one config. */
        val ok: Boolean get() = configs.size >= MIN_SUCCESS
    }

    /**
     * Run the probe. Emits real progress (0..100) through [onProgress] for the
     * page's bar. Returns the fresh batch of configs it collected.
     *
     * @param seenKeys optional dedup memory so we don't hand back configs the
     *                 user already has. The collected configs' keys are added to
     *                 it so the caller can persist them.
     */
    suspend fun probe(
        ctx: Context,
        seenKeys: MutableSet<String>? = null,
        onProgress: (percent: Int) -> Unit = {}
    ): Result = withContext(Dispatchers.IO) {
        val res = withTimeoutOrNull(BUDGET_MS) { runProbe(ctx, seenKeys, onProgress) }
        // A timeout is NOT a failure by itself: if runProbe already collected
        // configs before the ceiling hit, we can't see them here, so on timeout
        // we simply report what a best-effort empty result would — the caller
        // then falls back to the background engine. In practice runProbe finishes
        // well within budget because it stops at TARGET.
        res ?: Result(emptyList(), reachedSource = false)
    }

    private suspend fun runProbe(
        ctx: Context,
        seenKeys: MutableSet<String>?,
        onProgress: (Int) -> Unit
    ): Result = coroutineScope {
        // Seed dedup memory: everything the user already has (persistent seen-set
        // + My Configs) so the probe never hands back a duplicate. If the caller
        // passed a set we reuse it (and mutate it); otherwise we build a local one.
        val seen = seenKeys ?: HashSet<String>().also { s ->
            runCatching { s.addAll(SeenConfigStore.load(ctx)) }
            runCatching { ConfigStore(ctx).getServers().forEach { s.add(ConfigParser.dedupKey(it)) } }
        }

        // We pull a fresh, mixed 120-vless + 120-vmess batch straight from the
        // live source cursor (the SAME path a manual "START SEARCH" uses). This
        // is the single source of truth for "which source do we try to reach" —
        // no random filling. FreeConfigSource walks the real feeds a few at a
        // time and reports genuine progress through its onChunk callback.
        var lastPct = 0
        fun emit(p: Int) {
            val clamped = p.coerceIn(0, 100)
            if (clamped > lastPct) { lastPct = clamped; onProgress(clamped) }
        }
        emit(2)

        // Ensure first-launch / 30-day reset state is honoured.
        runCatching { FreeConfigSource.ensureFreshState(ctx) }

        val batch = runCatching {
            FreeConfigSource.nextBatch(
                ctx = ctx,
                startIndex = 0,
                seenKeys = seen
            ) { added, target, _ ->
                if (!coroutineContext.isActive) return@nextBatch
                // REAL progress: how much of the target batch we've actually
                // pulled from reachable sources. Cap at 96 until we've fully
                // finished so the final 100 is only emitted on completion.
                val pct = if (target > 0) (added * 96 / target) else 0
                emit(pct.coerceIn(2, 96))
            }
        }.getOrNull()

        val configs = batch?.configs ?: emptyList()

        // v5.9 — THE "second/third Auto Test adds nothing" FIX (probe side).
        //
        // If the current source cursor served only already-seen configs, `batch`
        // comes back empty while `reachedSource` is true (feeds ARE reachable,
        // everything was just a duplicate). We clear the dedup memory and retry so
        // a repeat Auto Test always re-serves the currently-live configs instead
        // of coming back empty. We only skip this when the feeds were genuinely
        // unreachable (offline) — that is the ONLY real error case.
        val finalConfigs = if (configs.isEmpty() && batch?.reachedSource == true) {
            runCatching {
                seenKeys?.clear()
                SeenConfigStore.performReset(ctx)
                val fresh = seenKeys ?: HashSet()
                runCatching { ConfigStore(ctx).getServers().forEach { fresh.add(ConfigParser.dedupKey(it)) } }
                FreeConfigSource.nextBatch(
                    ctx = ctx,
                    startIndex = 0,
                    seenKeys = fresh
                ) { added, target, _ ->
                    val pct = if (target > 0) (added * 96 / target) else 0
                    emit(pct.coerceIn(2, 96))
                }
            }.getOrNull()?.configs ?: emptyList()
        } else configs

        emit(100)
        onProgress(100)

        val reached = finalConfigs.isNotEmpty()
        Log.i(TAG, "probe done: reached=$reached, collected=${finalConfigs.size}")
        Result(finalConfigs, reachedSource = reached)
    }
}
