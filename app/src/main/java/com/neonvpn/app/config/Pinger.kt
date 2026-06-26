package com.neonvpn.app.config

import android.util.Log
import com.neonvpn.app.service.XrayManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * REAL end-to-end reachability test for a single [ServerConfig].
 *
 * v4.3 — PING SYSTEM REPAIR.
 * ─────────────────────────────────────────────────────────────────────────────
 * The v4.2 ping system was BROKEN — no config (not even known-good ones) would
 * ever ping. Three compounding bugs caused it:
 *
 *   1. NESTED TIMEOUT STARVATION. `Pinger.ping` had a 4 600 ms internal budget
 *      AND a 2-stage confirm, but both PingService.probeWithRetry and
 *      AutoTestEngine.probeWithRetry wrapped the WHOLE call in an OUTER
 *      `withTimeoutOrNull(2500 ms)`. The outer 2 500 ms always fired before the
 *      inner 4 600 ms work could finish → every probe was cancelled → every
 *      config reported UNREACHABLE.
 *
 *   2. INCOMPATIBLE PROBE ENDPOINTS. `Libv2ray.measureOutboundDelay` expects a
 *      lightweight endpoint that answers a tiny GET fast (a `generate_204`).
 *      v4.2 pointed it at `telegram.org/robots.txt`, `instagram.com/favicon.ico`
 *      and `1.1.1.1/cdn-cgi/trace` — heavier / redirecting / sometimes-blocked
 *      responses that the native measurer treats as a failure. So even when the
 *      proxy worked, the measurement returned -1.
 *
 *   3. OVER-STRICT 2-STAGE CONFIRM. Requiring a SECOND success on a DIFFERENT
 *      endpoint meant one slow endpoint failed the whole config.
 *
 * THE FIX (this file):
 *   • ONE bounded probe path. `Pinger.ping` does its OWN single hard timeout and
 *     returns fast; callers must NOT re-wrap it in a shorter timeout.
 *   • Probe a SET of fast, proxy-friendly `generate_204` endpoints. The FIRST
 *     that answers within budget wins — that single genuine proxied round-trip
 *     is proof the tunnel carries traffic. (We try several so a single down
 *     endpoint never sinks a healthy node.)
 *   • Works the SAME on Wi-Fi, mobile data, any ISP: the probe travels through
 *     the Xray outbound, so it reflects the real tunnel, not the local network.
 *
 * Returns latency in ms, or [UNREACHABLE] (-1) if the server can't proxy.
 */
object Pinger {

    private const val TAG = "Pinger"

    const val TESTING = Long.MIN_VALUE
    const val UNREACHABLE = -1L

    /**
     * Hard wall-clock ceiling for the ENTIRE ping of one config (all probe
     * attempts combined). Callers must treat [ping] as already-bounded and must
     * NOT wrap it in a shorter timeout (that was the v4.2 starvation bug).
     */
    const val PER_CONFIG_BUDGET_MS = 6_000L

    /** Per single probe attempt ceiling (one endpoint, one round-trip). */
    private const val PER_PROBE_BUDGET_MS = 3_500L

    /**
     * v4.3 — FAST, PROXY-FRIENDLY probe endpoints.
     *
     * These are all tiny `generate_204` (or equivalent) endpoints that answer
     * almost instantly with an empty body — exactly what `measureOutboundDelay`
     * is designed to time. They are also served from CDNs that are reachable
     * THROUGH a working proxy, which is the only thing that matters: if the
     * tunnel can fetch ANY of these, it genuinely carries traffic.
     *
     * Cloudflare's edge is first (fast + a censored edge for many IR ISPs), then
     * Google's connectivity-check infra (gstatic / google generate_204) which is
     * the most reliable 204 on the internet and the same target v2rayNG/v2box
     * use, then a couple of extra CDNs as fallbacks. We are NOT testing whether
     * google.com is *blocked* — we are testing whether the PROXY works; routing a
     * real request through the outbound to a guaranteed-204 endpoint is the
     * honest, reliable way to do that.
     */
    private val PROBE_URLS = listOf(
        "https://cp.cloudflare.com/generate_204",        // Cloudflare edge
        "https://www.gstatic.com/generate_204",          // Google infra (rock-solid 204)
        "https://www.google.com/generate_204",           // Google fallback
        "https://connectivitycheck.gstatic.com/generate_204"
    )

    /** Latency upper bound for a node we still treat as "reachable". */
    private const val MAX_VALID_MS = 6_000L

    suspend fun ping(cfg: ServerConfig): Long = withContext(Dispatchers.IO) {
        // Only vless / vmess are buildable; anything else is unreachable here.
        if (cfg.protocol != "vless" && cfg.protocol != "vmess") return@withContext UNREACHABLE

        // Build the ping config from the EXACT same outbound + stream settings
        // the real connect path uses, so a green ping == genuinely connects.
        val json = try {
            XrayConfigBuilder.buildPingConfig(cfg)
        } catch (e: Throwable) {
            Log.w(TAG, "buildPingConfig failed: ${e.message}")
            return@withContext UNREACHABLE
        }

        // Whole-config budget guards against a native call that hangs.
        val result = withTimeoutOrNull(PER_CONFIG_BUDGET_MS) {
            // Try each fast 204 endpoint; the FIRST genuine proxied success wins.
            // One real round-trip through the outbound is proof enough — no
            // over-strict second-endpoint gate that used to reject good nodes.
            for (url in PROBE_URLS) {
                val ms = singleProbe(json, url)
                if (ms in 1..MAX_VALID_MS) return@withTimeoutOrNull ms
            }
            UNREACHABLE
        }
        result ?: UNREACHABLE
    }

    /** One hard-wall-clock-capped proxied round-trip through [json] to [url]. */
    private suspend fun singleProbe(json: String, url: String): Long {
        return withTimeoutOrNull(PER_PROBE_BUDGET_MS) {
            withContext(Dispatchers.IO) {
                try {
                    XrayManager.measureConfigDelay(json, url)
                } catch (e: Throwable) {
                    Log.w(TAG, "core delay error: ${e.message}")
                    -1L
                }
            }
        } ?: -1L
    }
}
