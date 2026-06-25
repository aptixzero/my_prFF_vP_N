package com.neonvpn.app.config

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.coroutineContext

/**
 * Downloads the bundled [ConfigSources] one by one, parses every line/blob into
 * [ServerConfig]s (handling base64 subscriptions, concatenated links and
 * emoji/symbol noise via [ConfigParser]), de-duplicates across ALL sources and
 * stops as soon as [ConfigSources.TARGET_COUNT] unique configs are collected.
 *
 * Progress is streamed back through [onProgress] so the UI can animate a
 * filling bar (0..target). Network and parse errors on any single source are
 * swallowed so one dead URL never aborts the whole search.
 */
object ConfigFetcher {

    private const val TAG = "ConfigFetcher"

    data class Result(val configs: List<ServerConfig>, val sourcesTried: Int)

    /**
     * @param target          how many unique configs to collect (default 80)
     * @param onProgress      (collected, target, statusMessage) — called on a
     *                        background thread; marshal to UI yourself.
     */
    /** How many configs are allowed to share the same server location (IP /
     *  SNI host + port). Public lists often publish 10-20 clones of one server;
     *  beyond this cap they're just duplicates that waste the user's time. */
    const val MAX_PER_LOCATION = 3

    /** Max sources downloaded at the SAME time. Bounded so a low-RAM phone and a
     *  fragile mobile link aren't overwhelmed, but high enough that the whole
     *  source set is pulled in a few concurrent waves instead of one-by-one —
     *  this is the main "free configs load fast" win. */
    private const val FETCH_CONCURRENCY = 6

    suspend fun collect(
        target: Int = ConfigSources.TARGET_COUNT,
        onProgress: (collected: Int, target: Int, status: String) -> Unit = { _, _, _ -> }
    ): Result = withContext(Dispatchers.IO) {
        // FRESHNESS-AWARE ORDER. Sources are grouped into tiers (0 = freshest,
        // higher = older). Within each tier we cheaply probe each source's
        // `Last-Modified` header and prefer the genuinely-newest one — so
        // whichever mirror was updated most recently is preferred automatically.
        onProgress(0, target, "Ranking sources by freshness…")
        val sources = orderSourcesByFreshness(ConfigSources.SOURCES_TIERED)
        if (sources.isEmpty()) return@withContext Result(emptyList(), 0)

        // ---- PARALLEL DOWNLOAD ----
        // Old behaviour fetched every source sequentially: one slow mirror stalled
        // the whole search for many seconds. Now we download up to
        // FETCH_CONCURRENCY sources at once (bounded by a semaphore) and keep the
        // freshness ORDER of the results, so the merge below is identical to the
        // sequential one — just dramatically faster.
        val done = AtomicInteger(0)
        val gate = Semaphore(FETCH_CONCURRENCY)
        val bodies: List<String?> = coroutineScope {
            sources.map { url ->
                async(Dispatchers.IO) {
                    if (!coroutineContext.isActive) return@async null
                    val body = gate.withPermit {
                        try { fetch(url) } catch (e: Throwable) {
                            Log.w(TAG, "fetch failed for $url: ${e.message}"); null
                        }
                    }
                    val n = done.incrementAndGet()
                    onProgress(0, target, "Fetching sources… $n/${sources.size}")
                    body
                }
            }.awaitAll()
        }

        // ---- MERGE in freshness order ----
        val collected = LinkedHashMap<String, ServerConfig>()   // dedupKey -> cfg
        val locationCounts = HashMap<String, Int>()             // loc -> kept count
        var sourcesTried = 0
        var serverIndex = 0                                     // generic naming

        for (body in bodies) {
            if (!coroutineContext.isActive) break
            if (collected.size >= target) break
            if (body.isNullOrBlank()) continue
            sourcesTried++

            val parsed = try {
                ConfigParser.parseMany(body)
            } catch (e: Throwable) {
                Log.w(TAG, "parse failed: ${e.message}")
                emptyList()
            }

            for (cfg in parsed) {
                if (collected.size >= target) break
                val key = ConfigParser.dedupKey(cfg)
                if (collected.containsKey(key)) continue        // exact duplicate

                // Cap per-location: never add more than MAX_PER_LOCATION configs
                // that resolve to the same IP / SNI host + port.
                val loc = ConfigParser.locationKey(cfg)
                val have = locationCounts[loc] ?: 0
                if (have >= MAX_PER_LOCATION) continue

                // NORMALISE THE NAME. Public feeds embed channel / provider /
                // source branding in the remark (e.g. "🔥 @somechannel | DE").
                // We strip ALL of that and assign a neutral generic label so the
                // source is never exposed in the app: Server 1, Server 2, …
                serverIndex++
                collected[key] = cfg.copy(remark = "$GENERIC_PREFIX $serverIndex")
                locationCounts[loc] = have + 1
                onProgress(collected.size, target, "Found ${collected.size}/$target configs")
            }
        }

        Result(collected.values.toList(), sourcesTried)
    }

    /** Generic, brand-free server label prefix. */
    const val GENERIC_PREFIX = "Server"

    /** Re-number a list so names are always sequential Server 1..N (used after a
     *  batch is collected / after sorting so the visible labels stay clean). */
    fun renumber(list: List<ServerConfig>): List<ServerConfig> =
        list.mapIndexed { i, c -> c.copy(remark = "$GENERIC_PREFIX ${i + 1}") }

    /**
     * BATCHED / STREAMING collect — built for a predictable, stable Auto Test.
     *
     * Instead of returning the whole pool at once, this collects unique configs
     * and emits them in small [batchSize] batches through [onBatch] as soon as
     * each batch is ready (already renamed to generic Server N and de-duplicated
     * / per-location capped). The caller stores each batch immediately and can
     * keep the UI responsive, then start deeper testing only once enough configs
     * are collected.
     *
     * This is single-shot and deterministic: it ranks sources, downloads them in
     * bounded parallel waves, then walks the merged results in freshness order,
     * flushing a batch every [batchSize] configs and a final partial batch at the
     * end. No randomness in the collection path, so one press always makes
     * forward progress.
     *
     * @return the full ordered list collected (also already delivered via batches)
     */
    suspend fun collectBatched(
        target: Int = ConfigSources.TARGET_COUNT,
        batchSize: Int = 15,
        onProgress: (collected: Int, target: Int) -> Unit = { _, _ -> },
        onBatch: suspend (batch: List<ServerConfig>, totalSoFar: Int) -> Unit = { _, _ -> }
    ): List<ServerConfig> = withContext(Dispatchers.IO) {
        val sources = orderSourcesByFreshness(ConfigSources.SOURCES_TIERED)
        if (sources.isEmpty()) return@withContext emptyList()

        val done = AtomicInteger(0)
        val gate = Semaphore(FETCH_CONCURRENCY)
        val bodies: List<String?> = coroutineScope {
            sources.map { url ->
                async(Dispatchers.IO) {
                    if (!coroutineContext.isActive) return@async null
                    val body = gate.withPermit {
                        try { fetch(url) } catch (_: Throwable) { null }
                    }
                    done.incrementAndGet()
                    body
                }
            }.awaitAll()
        }

        val collected = LinkedHashMap<String, ServerConfig>()
        val locationCounts = HashMap<String, Int>()
        var serverIndex = 0
        var pending = ArrayList<ServerConfig>(batchSize)

        for (body in bodies) {
            if (!coroutineContext.isActive) break
            if (collected.size >= target) break
            if (body.isNullOrBlank()) continue

            val parsed = try { ConfigParser.parseMany(body) } catch (_: Throwable) { emptyList() }
            for (cfg in parsed) {
                if (collected.size >= target) break
                val key = ConfigParser.dedupKey(cfg)
                if (collected.containsKey(key)) continue
                val loc = ConfigParser.locationKey(cfg)
                val have = locationCounts[loc] ?: 0
                if (have >= MAX_PER_LOCATION) continue

                serverIndex++
                val named = cfg.copy(remark = "$GENERIC_PREFIX $serverIndex")
                collected[key] = named
                locationCounts[loc] = have + 1
                pending.add(named)
                onProgress(collected.size, target)

                if (pending.size >= batchSize) {
                    onBatch(ArrayList(pending), collected.size)
                    pending = ArrayList(batchSize)
                }
            }
        }
        if (pending.isNotEmpty()) onBatch(ArrayList(pending), collected.size)
        collected.values.toList()
    }

    /**
     * Order sources so the FRESHEST come first. We respect the static tier
     * (tier 0 = newest feeds, higher = older fallbacks) as the primary key, and
     * within each tier we sort by the source's real `Last-Modified` timestamp
     * (probed with a cheap HEAD request, best-effort). Sources whose freshness
     * we can't determine keep their declared order. A tiny shuffle inside the
     * same (tier, freshness-bucket) keeps the mix varied between runs.
     *
     * This is what makes the app "use whichever link is newer/up-to-date" — if
     * two equivalent feeds exist, the one updated most recently is tried first.
     */
    private suspend fun orderSourcesByFreshness(
        tiered: List<ConfigSources.Source>
    ): List<String> = withContext(Dispatchers.IO) {
        // Probe Last-Modified for ALL sources CONCURRENTLY (cheap HEAD, each hard
        // time-boxed) so ranking takes ~one HEAD round-trip instead of summing
        // them — a slow host can no longer stall the freshness ranking.
        val withTime = coroutineScope {
            tiered.map { src ->
                async(Dispatchers.IO) {
                    val ts = try {
                        withTimeoutOrNull(2_500L) { lastModifiedMillis(src.url) }
                    } catch (_: Throwable) { null } ?: -1L
                    Triple(src.url, src.tier, ts)
                }
            }.awaitAll()
        }
        // §4.8 — deterministic per-run rotation seed (NO RNG; Golden Rule #2 and
        // the NoRandomInStatsTest forbid kotlin/java Random in core/ and ui/).
        // A coarse time bucket rotates the order of equally-fresh sources between
        // runs without ever fabricating data.
        val rotation = System.currentTimeMillis() / 60_000L
        withTime
            // primary: tier (0 first). secondary: newer Last-Modified first
            // (unknown = -1 sinks to the bottom of its tier). tertiary: a stable
            // rotation hash so equally-fresh sources cycle between runs.
            .sortedWith(
                compareBy<Triple<String, Int, Long>> { it.second }
                    .thenByDescending { it.third }
                    .thenBy { (it.first.hashCode().toLong() xor rotation) }
            )
            .map { it.first }
    }

    /** Cheap HEAD request to read `Last-Modified`; returns epoch millis or -1. */
    private fun lastModifiedMillis(urlStr: String): Long {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 2000
                readTimeout = 2000
                instanceFollowRedirects = true
                requestMethod = "HEAD"
                setRequestProperty("User-Agent", "v2rayNG/1.8.5 (Android)")
            }
            try {
                conn.responseCode
                val lm = conn.getHeaderFieldDate("Last-Modified", -1L)
                if (lm > 0) lm else conn.getHeaderFieldDate("Date", -1L)
            } finally {
                try { conn.disconnect() } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {
            -1L
        }
    }

    /**
     * Fetch a source URL. On Iranian mobile-data, `raw.githubusercontent.com` is
     * frequently throttled/poisoned, so a direct GET silently times out — this
     * is why "configs are found on WiFi but not on SIM data". We therefore try
     * the original URL first and, if it fails, retry through a set of public
     * GitHub mirrors / CDNs that stay reachable on disrupted mobile links. The
     * first mirror that returns a usable body wins.
     */
    private fun fetch(urlStr: String): String? {
        for (candidate in mirrorCandidates(urlStr)) {
            val body = try { fetchOne(candidate) } catch (e: Throwable) {
                Log.w(TAG, "fetch failed for $candidate: ${e.message}"); null
            }
            if (!body.isNullOrBlank()) return body
        }
        return null
    }

    /** Build an ordered list of URLs to try: the original first, then mirrors.
     *
     *  On disrupted Iranian links, the origin host (GitHub raw, mudfish, …) is
     *  frequently throttled or DNS-poisoned, which is why "configs load on WiFi
     *  but not on SIM data / when GitHub is blocked". We therefore always append
     *  a set of edge-cached CDNs and generic reverse proxies. The first mirror
     *  that returns a usable body wins, so the configs can still be pulled even
     *  when the origin is unreachable. */
    private fun mirrorCandidates(urlStr: String): List<String> {
        val out = LinkedHashSet<String>()
        out.add(urlStr)

        // raw.githubusercontent.com/<user>/<repo>/<branch>/<path>
        val rawPrefix = "https://raw.githubusercontent.com/"
        if (urlStr.startsWith(rawPrefix)) {
            val rest = urlStr.substring(rawPrefix.length)            // user/repo/branch/path...
            val parts = rest.split('/')
            if (parts.size >= 4) {
                val user = parts[0]; val repo = parts[1]; val branch = parts[2]
                val path = parts.drop(3).joinToString("/")
                // jsDelivr CDN (very reliable inside Iran, edge-cached)
                out.add("https://cdn.jsdelivr.net/gh/$user/$repo@$branch/$path")
                out.add("https://fastly.jsdelivr.net/gh/$user/$repo@$branch/$path")
                out.add("https://gcore.jsdelivr.net/gh/$user/$repo@$branch/$path")
                // statically.io mirror
                out.add("https://cdn.statically.io/gh/$user/$repo/$branch/$path")
            }
        }

        // Generic reverse-proxy / CORS mirrors that work for ANY origin host
        // (GitHub raw, mudfish.net pastes, etc.). These keep config-fetching
        // working even when the origin host itself is blocked on the user's ISP.
        out.add("https://ghproxy.net/$urlStr")
        out.add("https://gh.api.99988866.xyz/$urlStr")
        out.add("https://cors.isomorphic-git.org/$urlStr")
        // r.jina.ai mirrors arbitrary URLs through a reachable edge.
        out.add("https://r.jina.ai/$urlStr")
        // allorigins raw passthrough (URL-encoded target).
        out.add("https://api.allorigins.win/raw?url=" + urlEncode(urlStr))

        return out.toList()
    }

    private fun urlEncode(s: String): String = try {
        java.net.URLEncoder.encode(s, "UTF-8")
    } catch (_: Throwable) { s }

    private fun fetchOne(urlStr: String): String? {
        val url = URL(urlStr)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            // Tightened from 12s/15s: with parallel fetching we'd rather abandon a
            // stalled mirror quickly and fall through to the next candidate than
            // block a download slot for 15 seconds on a dead host.
            connectTimeout = 7000
            readTimeout = 9000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "v2rayNG/1.8.5 (Android)")
            setRequestProperty("Accept", "*/*")
        }
        if (conn is HttpsURLConnection) {
            // best-effort; default trust managers are fine for public CDNs
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "HTTP $code for $urlStr")
                return null
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            try { conn.disconnect() } catch (_: Throwable) {}
        }
    }
}
