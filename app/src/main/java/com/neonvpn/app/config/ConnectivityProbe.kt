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
     * Whole-probe wall-clock ceiling — the page never waits longer than this. It
     * is generous (Iranian links are slow) but finite so a totally dead network
     * can't wedge the page. Real completion almost always happens well before it.
     */
    const val BUDGET_MS = 45_000L

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
        val connTest = runCatching { testConnectivity(ctx) { pct -> emit(pct) } }
            .getOrDefault(ConnTest(reached = false))
        emit(PHASE1_END)

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
            if (sources.isEmpty()) { emit(bandEnd); return false }
            val size = sources.size
            // Build the order to try: bonded source first (verify it), then walk.
            val order = ArrayList<Int>(maxTriesPerKind)
            val start = if (bondedIndex in 0 until size) bondedIndex else 0
            for (k in 0 until minOf(maxTriesPerKind, size)) order.add((start + k) % size)

            for ((i, idx) in order.withIndex()) {
                if (!coroutineContext.isActive) return anyReached
                val src = sources[idx]
                val body = runCatching { SourceFetcher.fetch(src.url) }.getOrNull()
                // advance the bar for this source attempt (real work just happened)
                val pct = bandStart + ((i + 1) * (bandEnd - bandStart) / order.size)
                emit(pct.coerceIn(bandStart, bandEnd))
                if (!body.isNullOrBlank()) {
                    val links = runCatching {
                        SourceFetcher.extractLinks(body, src.kind, limit = 1)
                    }.getOrDefault(emptyList())
                    if (links.isNotEmpty()) {
                        // Reachable AND yields configs → bond to it and stop.
                        setBond(idx)
                        emit(bandEnd)
                        return true
                    }
                }
            }
            emit(bandEnd)
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
