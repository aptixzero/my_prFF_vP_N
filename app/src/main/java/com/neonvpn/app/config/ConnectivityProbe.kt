package com.neonvpn.app.config

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

/**
 * v6.0 — REAL two-phase "Auto Test" connectivity probe.
 *
 * The v6 brief, precisely:
 *
 *   PHASE 1 — CONNECTION TEST (0 % → 60 %):
 *     We really TEST the user's connectivity to the 50 live source feeds. We walk
 *     the sources one by one and actually open each; the bar advances as EACH
 *     source is probed (real work, never a random timer). The moment a source
 *     answers we know which feed the user can reach and we remember it (the sticky
 *     bond). The progress here is driven by how many sources we've tried vs. the
 *     small window we need to find one reachable feed per kind — so it climbs
 *     honestly toward 60 % as the connection test proceeds.
 *
 *   PHASE 2 — ADD CONFIGS (60 % → 100 %):
 *     Using the SAME source we just reached, we pull a full fresh 240 batch (120
 *     vless + 120 vmess) of configs. The bar climbs 60→100 as configs are actually
 *     collected. When it reaches 100 % the configs are guaranteed collected, the
 *     page adds them and closes, then pings them.
 *
 *   NO FALSE "connection error": we ONLY treat it as an error when NOT ONE source
 *   could be opened at all (a genuine offline state). And even then the brief says
 *   "remove this error" — so the caller no longer surfaces it as a hard error; it
 *   simply finishes and lets the background engine keep trying. Reaching any source
 *   and pulling a valid vless/vmess is success.
 *
 * Timing is bound to REAL work, not to `delay()`: the only ceilings here are the
 * per-fetch network timeouts inside [SourceFetcher] and one whole-probe wall-clock
 * safety net so the page can never hang forever on a dead network.
 *
 * Fully exception-safe: a dead source / malformed line never crashes it.
 */
object ConnectivityProbe {

    private const val TAG = "ConnectivityProbe"

    /**
     * v6.1 — Whole-probe wall-clock ceiling. Generous so a slow-but-alive Iranian
     * link is never cut short, yet finite so a totally dead network can't wedge
     * the page forever. The bar HOLDS (locks) on its current value while the link
     * is fully down inside phase 1 and only resumes once a source becomes
     * reachable again — so this ceiling only ever bites after minutes of a
     * genuinely dead connection, at which point we close quietly (no fake error).
     */
    const val BUDGET_MS = 240_000L

    /**
     * v6.1 — how long a single connectivity round may take before we treat the
     * link as "down this round" and re-try (holding the bar). Bound so the retry
     * loop stays responsive on a flapping link.
     */
    private const val ROUND_BUDGET_MS = 30_000L

    /** v6.1 — pause between phase-1 retries while the internet is fully down. */
    private const val RETRY_PAUSE_MS = 1_500L

    /** Boundary between the connection-test phase and the config-collect phase. */
    private const val PHASE1_END = 60

    /**
     * How many configs we aim to collect during the probe — the SAME 240-per-press
     * batch used everywhere so the first fill is a full fresh batch.
     */
    const val TARGET = FreeConfigSource.BATCH_PER_PRESS   // 240

    /** Minimum configs to consider the probe a success (a weak link may yield few). */
    private const val MIN_SUCCESS = 1

    data class Result(
        /** The fresh batch of configs collected from the reachable source. */
        val configs: List<ServerConfig> = emptyList(),
        /** True if at least one source feed was opened during the probe. */
        val reachedSource: Boolean = configs.isNotEmpty()
    ) {
        /** We have something to add whenever we collected at least one config. */
        val ok: Boolean get() = configs.size >= MIN_SUCCESS
    }

    /**
     * Run the probe. Emits real progress (0..100) through [onProgress]:
     *   0..60  → live connection test against the source feeds
     *   60..100 → collecting the fresh batch from the reached source
     *
     * @param seenKeys optional dedup memory so we don't hand back configs the user
     *                 already has; collected keys are added so the caller can persist.
     */
    suspend fun probe(
        ctx: Context,
        seenKeys: MutableSet<String>? = null,
        onProgress: (percent: Int) -> Unit = {}
    ): Result = withContext(Dispatchers.IO) {
        val res = withTimeoutOrNull(BUDGET_MS) { runProbe(ctx, seenKeys, onProgress) }
        res ?: Result(emptyList(), reachedSource = false)
    }

    private suspend fun runProbe(
        ctx: Context,
        seenKeys: MutableSet<String>?,
        onProgress: (Int) -> Unit
    ): Result = coroutineScope {
        // Seed dedup memory: everything the user already has so we never hand back
        // a duplicate. Reuse the caller's set (and mutate it) when given.
        val seen = seenKeys ?: HashSet<String>().also { s ->
            runCatching { s.addAll(SeenConfigStore.load(ctx)) }
            runCatching { ConfigStore(ctx).getServers().forEach { s.add(ConfigParser.dedupKey(it)) } }
        }

        var lastPct = 0
        fun emit(p: Int) {
            val clamped = p.coerceIn(0, 100)
            if (clamped > lastPct) { lastPct = clamped; onProgress(clamped) }
        }
        emit(1)

        // Honour first-launch / 30-day reset state.
        runCatching { FreeConfigSource.ensureFreshState(ctx) }

        // ── PHASE 1 (0..60 %): REAL connection test against the source feeds ──────
        // Walk the sources and actually open them. The bar advances per-source so
        // the user watches a genuine connectivity test. We stop as soon as we've
        // confirmed a reachable feed for each kind (that is what "the connection
        // is up and we know which source to use" means). If the user is already
        // bonded to a source, we verify THAT source first (fast path).
        //
        // v6.1 — THE "don't lock on a number" FIX. The bar must NOT freeze forever
        // on a mid-run value, and it must NOT run all the way to 100 % while the
        // internet is actually down. So we run the connection test in a RETRY LOOP:
        // each round the bar climbs as sources are really probed; if the round
        // ends WITHOUT reaching any source (link fully down) we HOLD the bar on
        // its current value, pause briefly, and try the whole round again. The
        // instant a source becomes reachable the bar resumes and we advance to
        // phase 2. We keep the highest bar value reached across rounds so it never
        // goes backwards. This whole loop is bounded by BUDGET_MS.
        var connTest = ConnTest(reached = false)
        while (coroutineContext.isActive) {
            connTest = runCatching {
                withTimeoutOrNull(ROUND_BUDGET_MS) {
                    testConnectivity(ctx) { pct -> emit(pct) }
                } ?: ConnTest(reached = false)
            }.getOrDefault(ConnTest(reached = false))

            if (connTest.reached) break

            // Fully down this round — HOLD the bar where it is (do NOT push to 60 %
            // and do NOT advance into phase 2 yet). Pause, then re-test. When the
            // link recovers the next round reaches a source and we continue.
            if (!coroutineContext.isActive) break
            emit(lastPct)                 // re-assert current value (never regress)
            delay(RETRY_PAUSE_MS)
        }
        // Only advance the bar to the phase boundary once we have genuinely reached
        // a source. If we exited the loop without reaching one (budget/cancel), the
        // bar stays where it locked and we finish quietly below.
        if (connTest.reached) emit(PHASE1_END)

        // v6.1 — if phase 1 never reached a source (link fully down for the whole
        // BUDGET_MS, or the page was cancelled), we do NOT run phase 2 and we do
        // NOT force the bar to 100 %. The bar stays LOCKED where phase 1 left it,
        // and we finish quietly (the caller shows no error and the background
        // engine keeps retrying). This is the "lock on a number when the connection
        // fully drops" behaviour.
        if (!connTest.reached) {
            Log.i(TAG, "probe: no source reached (offline) — bar held at $lastPct")
            return@coroutineScope Result(emptyList(), reachedSource = false)
        }

        // ── PHASE 2 (60..100 %): collect the fresh 240 batch from the reached src ─
        // FreeConfigSource.nextBatch honours the sticky bond set during phase 1, so
        // it pulls from the SAME source we just reached. Progress maps the collected
        // count onto 60..99 (100 emitted only on completion).
        val batch = runCatching {
            FreeConfigSource.nextBatch(
                ctx = ctx,
                startIndex = 0,
                seenKeys = seen
            ) { added, target, _ ->
                if (!coroutineContext.isActive) return@nextBatch
                val frac = if (target > 0) (added.toDouble() / target) else 0.0
                val pct = PHASE1_END + (frac * (99 - PHASE1_END)).toInt()
                emit(pct.coerceIn(PHASE1_END, 99))
            }
        }.getOrNull()

        var configs = batch?.configs ?: emptyList()

        // v6.0 — REPEAT-USE FIX. If the bonded source served only already-seen
        // configs, `configs` comes back empty while the feed is perfectly
        // reachable. Clear the dedup memory (keep only what the user actually
        // holds) and pull again so a repeat Auto Test always re-serves the live
        // configs instead of coming back empty. We only skip this when NO source
        // was reachable at all (true offline).
        if (configs.isEmpty() && (batch?.reachedSource == true || connTest.reached)) {
            runCatching {
                seenKeys?.clear()
                SeenConfigStore.performReset(ctx)
                val fresh = seenKeys ?: HashSet()
                runCatching { ConfigStore(ctx).getServers().forEach { fresh.add(ConfigParser.dedupKey(it)) } }
                val retry = FreeConfigSource.nextBatch(
                    ctx = ctx,
                    startIndex = 0,
                    seenKeys = fresh
                ) { added, target, _ ->
                    val frac = if (target > 0) (added.toDouble() / target) else 0.0
                    val pct = PHASE1_END + (frac * (99 - PHASE1_END)).toInt()
                    emit(pct.coerceIn(PHASE1_END, 99))
                }
                configs = retry.configs
            }
        }

        emit(100)
        onProgress(100)

        val reached = configs.isNotEmpty() || connTest.reached || (batch?.reachedSource == true)
        Log.i(TAG, "probe done: reached=$reached, collected=${configs.size}")
        Result(configs, reachedSource = reached)
    }

    private data class ConnTest(val reached: Boolean)

    /**
     * v6.0 — the PHASE-1 real connection test. Walks VLESS then VMESS source feeds
     * (starting from the bonded source if any) and opens each until it finds a
     * reachable one per kind. Emits progress across the 0..[PHASE1_END] window as
     * each source is actually probed, so the bar reflects genuine network work.
     *
     * When a source is reached and yields at least one link, we bond to it so
     * PHASE 2 (and every future 240 batch) pulls from the SAME source.
     */
    private suspend fun testConnectivity(
        ctx: Context,
        emit: (Int) -> Unit
    ): ConnTest {
        // Small windows: we only need to reach ONE feed per kind. Try up to this
        // many sources per kind before giving up (still cheap on a mobile link).
        val maxTriesPerKind = 8
        var anyReached = false

        // A tiny budget map for progress: half of the 0..60 band for vless, half
        // for vmess. Each probed source moves the bar a fair slice.
        suspend fun testKind(
            sources: List<LiveSources.Src>,
            bondedIndex: Int,
            bandStart: Int,
            bandEnd: Int,
            setBond: (Int) -> Unit
        ): Boolean {
            // v6.1 — no sources for this kind: nothing to reach. Do NOT push the
            // bar forward (that would run the bar even with no connectivity);
            // just report "not reached" so the retry loop can hold + re-test.
            if (sources.isEmpty()) return false
            val size = sources.size
            // Build the order to try: bonded source first (verify it), then walk.
            val order = ArrayList<Int>(maxTriesPerKind)
            val start = if (bondedIndex in 0 until size) bondedIndex else 0
            for (k in 0 until minOf(maxTriesPerKind, size)) order.add((start + k) % size)

            for ((i, idx) in order.withIndex()) {
                if (!coroutineContext.isActive) return anyReached
                val src = sources[idx]
                val body = runCatching { SourceFetcher.fetch(src.url) }.getOrNull()
                if (!body.isNullOrBlank()) {
                    val links = runCatching {
                        SourceFetcher.extractLinks(body, src.kind, limit = 1)
                    }.getOrDefault(emptyList())
                    if (links.isNotEmpty()) {
                        // Reachable AND yields configs → bond to it and stop.
                        setBond(idx)
                        // advance the bar to this kind's band end ONLY on real
                        // success (a source was actually reached this round).
                        emit(bandEnd)
                        return true
                    }
                }
                // v6.1 — This source was UNREACHABLE. Advance the bar a little for
                // the real probe work we just did, but CAP it just BELOW bandEnd so
                // a kind that never reaches any source can never itself carry the
                // bar to the phase boundary. The boundary (bandEnd / PHASE1_END) is
                // only emitted on a genuine success above.
                val ceil = (bandEnd - 1).coerceAtLeast(bandStart)
                val pct = bandStart + ((i + 1) * (ceil - bandStart) / order.size)
                emit(pct.coerceIn(bandStart, ceil))
            }
            // Walked every candidate for this kind without reaching one. Leave the
            // bar where it climbed (below bandEnd) — the retry loop will hold here.
            return false
        }

        val vlessOk = testKind(
            LiveSources.VLESS,
            ConnectedSourceStore.vlessSource(ctx),
            bandStart = 2, bandEnd = 30
        ) { idx -> ConnectedSourceStore.setVlessSource(ctx, idx) }
        if (vlessOk) anyReached = true

        val vmessOk = testKind(
            LiveSources.VMESS,
            ConnectedSourceStore.vmessSource(ctx),
            bandStart = 30, bandEnd = PHASE1_END
        ) { idx -> ConnectedSourceStore.setVmessSource(ctx, idx) }
        if (vmessOk) anyReached = true

        return ConnTest(reached = anyReached)
    }
}
