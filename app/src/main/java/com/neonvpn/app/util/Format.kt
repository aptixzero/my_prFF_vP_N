package com.neonvpn.app.util

import java.util.Locale

/** Human-friendly formatting helpers for the speed meter & stats. */
object Format {

    /** bytes/sec -> "1.2 MB/s" style (with /s suffix). */
    fun speed(bytesPerSec: Long): String = "${size(bytesPerSec)}/s"

    /** raw bytes -> "1.2 MB" / "850 KB" / "12 B". */
    fun size(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1024 && i < units.size - 1) {
            v /= 1024.0
            i++
        }
        return if (i == 0) "${v.toLong()} ${units[i]}"
        else String.format(Locale.US, "%.1f %s", v, units[i])
    }

    /** seconds -> "01:23:45". */
    fun duration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    /** ping ms -> "42 ms" or "—". */
    fun ping(ms: Long): String = if (ms in 0..100000) "$ms ms" else "—"
}
