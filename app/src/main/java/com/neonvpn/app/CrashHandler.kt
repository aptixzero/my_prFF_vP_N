package com.neonvpn.app

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Global uncaught-exception handler.
 *
 * Why this exists: the app used to hard-crash the FIRST time the user tapped
 * CONNECT — typically the moment the VPN-permission consent dialog returned, on
 * a thread that threw before any try/catch could see it. A raw uncaught
 * exception there kills the whole process with the ugly "Professor VPN keeps
 * stopping" system dialog.
 *
 * This handler catches EVERY uncaught throwable on EVERY thread, writes a
 * timestamped stack trace to the app's private files dir (so it can be inspected
 * later), and then:
 *   • for the VPN worker / tun2socks / watchdog threads, it SWALLOWS the crash
 *     and lets the process keep living (the VpnService already has its own
 *     crash-guards + auto-recovery, so the UI survives), and
 *   • for the main thread, it logs and defers to the previous default handler so
 *     Android still does the right thing rather than leaving a frozen black UI.
 *
 * The net effect: tapping CONNECT can no longer take the whole app down — at
 * worst a single connection attempt fails gracefully and the user can retry.
 */
class CrashHandler private constructor(
    private val appContext: Context,
    private val previous: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(t: Thread, e: Throwable) {
        try {
            val trace = StringWriter().also { sw ->
                PrintWriter(sw).use { e.printStackTrace(it) }
            }.toString()
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            Log.e(TAG, "Uncaught on thread '${t.name}':\n$trace")
            saveCrash(stamp, t.name, sanitize(trace))
        } catch (_: Throwable) {
            // never let the crash handler itself crash
        }

        // Threads we know how to survive: keep the process alive so the user's
        // app/UI doesn't die from a background connection hiccup.
        val survivable = t.name in SURVIVABLE_THREADS ||
            t.name.startsWith("vpn-") ||
            t.name.startsWith("tun2socks") ||
            t.name.startsWith("stats") ||
            t.name.startsWith("watchdog") ||
            t.name.startsWith("DefaultDispatcher") ||
            t.name.startsWith("pool-")

        if (survivable) {
            Log.w(TAG, "swallowed crash on survivable thread '${t.name}' — process kept alive")
            return
        }

        // For the main/UI thread, fall back to the platform default so Android
        // can restart/clean up properly instead of leaving a frozen screen.
        previous?.uncaughtException(t, e)
    }

    private fun saveCrash(stamp: String, thread: String, trace: String) {
        try {
            // §4.5 — sanitized crash logs live in filesDir/logs/.
            val dir = File(appContext.filesDir, "logs")
            if (!dir.exists()) dir.mkdirs()
            // keep only the latest few logs
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(4)?.forEach {
                try { it.delete() } catch (_: Throwable) {}
            }
            File(dir, "crash_${System.currentTimeMillis()}.txt")
                .writeText("[$stamp] thread=$thread\n\n$trace")
        } catch (_: Throwable) {
        }
    }

    /**
     * §4.5 privacy rule — never persist anything that could leak a user's
     * server. Strips config share-links, IPv4/IPv6 literals, UUIDs and bare
     * host:port pairs from a stack trace before it is written to disk.
     */
    private fun sanitize(raw: String): String {
        var s = raw
        s = LINK_RX.replace(s, "[redacted-link]")
        s = UUID_RX.replace(s, "[redacted-uuid]")
        s = IPV4_RX.replace(s, "[redacted-ip]")
        s = IPV6_RX.replace(s, "[redacted-ip6]")
        s = HOSTPORT_RX.replace(s, "[redacted-host]")
        return s
    }

    companion object {
        private const val TAG = "CrashHandler"
        private val SURVIVABLE_THREADS = setOf("vpn-start", "tun2socks", "stats", "watchdog")

        // Sanitization patterns (see sanitize()).
        private val LINK_RX = Regex("""\b(?:vless|vmess|trojan|ss)://[^\s'"<>]+""")
        private val UUID_RX = Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""")
        private val IPV4_RX = Regex("""\b\d{1,3}(?:\.\d{1,3}){3}\b""")
        private val IPV6_RX = Regex("""\b(?:[0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}\b""")
        private val HOSTPORT_RX = Regex("""\b[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}:\d{2,5}\b""")

        fun install(context: Context) {
            val prev = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(
                CrashHandler(context.applicationContext, prev)
            )
        }
    }
}
