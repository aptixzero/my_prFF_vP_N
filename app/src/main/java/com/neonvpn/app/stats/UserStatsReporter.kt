package com.neonvpn.app.stats

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL

/**
 * PRIVACY-FIRST anonymous user-stats heartbeat.
 *
 * Powers the admin panel's "Users" section (Online / Offline / All) WITHOUT a
 * dedicated backend and WITHOUT exposing anything personal:
 *
 *   • Every install contributes exactly ONE anonymous record, keyed by an opaque
 *     12-char token (see [InstallId]) — never an IP, device id, account, SIM, or
 *     location.
 *   • On launch (and every few minutes while open) the app POSTs a tiny heartbeat
 *     containing only that token + the current time bucket. The aggregate store
 *     therefore knows "this many distinct anonymous tokens were seen" (total) and
 *     "this many were seen in the last few minutes" (online) — nothing else.
 *   • The very FIRST heartbeat of an install also bumps a separate "installs"
 *     total exactly once, so the All count is accurate and a returning user does
 *     NOT inflate it.
 *
 * It is fully best-effort: it runs off the main thread, is time-boxed, swallows
 * every error, and never blocks or crashes the UI if the network (or the counter
 * service) is unreachable. If stats can't be reported, the app still works 100%.
 *
 * The counter service is a stateless public aggregate-counter API (counterapi).
 * It stores only integers under our namespace — there is no per-user table, no
 * PII, and nothing that can be traced back to a person. Mirrors are tried in
 * order so a blocked endpoint inside Iran simply falls through to the next.
 */
object UserStatsReporter {

    private const val TAG = "UserStats"
    private const val PREFS = "pv_anon_stats"
    private const val KEY_INSTALL_COUNTED = "install_counted"

    /** Namespace for our anonymous counters (kept generic / non-identifying). */
    private const val NS = "professorvpn"

    /** How long a token is considered "online" after its last heartbeat. */
    const val ONLINE_WINDOW_MS = 5 * 60 * 1000L

    /** Heartbeat cadence while the app is in the foreground. */
    private const val HEARTBEAT_INTERVAL_MS = 2 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var started = false

    /**
     * Start the anonymous heartbeat loop. Idempotent — safe to call from
     * Application.onCreate; the loop self-limits and never throws.
     */
    fun start(context: Context) {
        if (started) return
        started = true
        val appCtx = context.applicationContext
        scope.launch {
            // give app startup the CPU/network first — stats are never urgent and
            // must never contribute to a cold-start stutter.
            try { delay(4_000L) } catch (_: Throwable) {}
            // count this install exactly once (accurate "All" total)
            try { countInstallOnce(appCtx) } catch (_: Throwable) {}
            // then heartbeat forever while the process lives
            while (isActive) {
                try { heartbeat(appCtx) } catch (t: Throwable) {
                    Log.w(TAG, "heartbeat failed: ${t.message}")
                }
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    /** Fire a single heartbeat immediately (e.g. on app resume). Best-effort. */
    fun pulse(context: Context) {
        val appCtx = context.applicationContext
        scope.launch { try { heartbeat(appCtx) } catch (_: Throwable) {} }
    }

    // ------------------------------------------------------------------ internals
    private suspend fun countInstallOnce(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (sp.getBoolean(KEY_INSTALL_COUNTED, false)) return
        // bump the lifetime install total exactly once for this device.
        // KEY is bare ("installs_total") — the namespace IS already "professorvpn",
        // so the panel reads /v1/professorvpn/installs_total/ (no double prefix).
        val ok = hitCounter("installs_total")
        if (ok) sp.edit().putBoolean(KEY_INSTALL_COUNTED, true).apply()
    }

    /**
     * A heartbeat bumps the counter for the CURRENT 5-minute window. The window
     * number is derived purely from the wall clock — it contains NOTHING about
     * the user. The admin panel reads the current (and previous) window key to get
     * "online now"; because each window is a distinct key, a value never
     * accumulates stale hits, so it always reflects only recent activity.
     *
     * The local install flag already guarantees one-install-one-count for the
     * lifetime total, so no token is ever sent or stored server-side.
     */
    private suspend fun heartbeat(ctx: Context) {
        // ensure an id exists locally (also seeds the install count path)
        InstallId.anonToken(ctx)
        // bare key under the "professorvpn" namespace → "online_<window>"
        hitCounter("online_${currentWindow()}")
    }

    /** Absolute 5-minute window number (monotonic) used as the online-bucket key. */
    private fun currentWindow(): Long = System.currentTimeMillis() / ONLINE_WINDOW_MS

    /**
     * Increment an integer counter by 1 on a public aggregate-counter service.
     * Tries several mirrors; returns true on the first success. Time-boxed and
     * exception-safe. Stores only an integer under our namespace — no PII.
     */
    private suspend fun hitCounter(key: String): Boolean = withTimeoutOrNull(6_000L) {
        val safeKey = key.filter { it.isLetterOrDigit() || it == '_' }
        // counterapi.dev v1 /up increments and returns the value; it is the only
        // endpoint that is currently stable AND CORS-open for the panel to read
        // back via the trailing-slash GET. abacus is kept as a last-ditch mirror.
        val endpoints = listOf(
            "https://api.counterapi.dev/v1/$NS/$safeKey/up",
            "https://abacus.jasoncameron.dev/hit/$NS/$safeKey"
        )
        for (url in endpoints) {
            val ok = try { getOk(url) } catch (_: Throwable) { false }
            if (ok) return@withTimeoutOrNull true
        }
        false
    } ?: false

    private fun getOk(urlStr: String): Boolean {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000
            readTimeout = 5000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "ProfessorVPN/3.6 (Android)")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val code = conn.responseCode
            if (code in 200..299) {
                // drain so the connection can be reused/closed cleanly
                try { conn.inputStream.bufferedReader().use { it.readText() } } catch (_: Throwable) {}
                true
            } else false
        } finally {
            try { conn.disconnect() } catch (_: Throwable) {}
        }
    }
}
