package com.neonvpn.app.config

import android.content.Context

/**
 * v6.0 — STICKY CONNECTED SOURCE memory.
 *
 * The v6 brief: when the Auto-Test connectivity page runs, it must first find out
 * WHICH of the 50 live source feeds the user can actually reach (a real, measured
 * connection test — 0..60 %), then pull the configs from THAT reachable source
 * (60..100 %). Crucially, "the next 240 configs must come from the SAME source it
 * connected to the first time" — so we remember, per kind, the index of the last
 * source feed we successfully reached and pulled configs from, and resume from it
 * on the next press instead of blindly walking the cursor forward.
 *
 * This is intentionally a tiny, separate store (not folded into [FreeConfigSource]'s
 * cursor prefs) so the "which source is the user bonded to" fact survives even a
 * dedup-memory reset, and can be read by the connectivity probe to drive its real
 * (non-random) progress bar.
 */
object ConnectedSourceStore {

    private const val PREFS = "pv_connected_src_v6"
    private const val KEY_VLESS_SRC = "vless_connected_src"
    private const val KEY_VMESS_SRC = "vmess_connected_src"
    private const val NONE = -1

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The VLESS source index the user is currently bonded to, or -1 if none yet. */
    fun vlessSource(ctx: Context): Int = prefs(ctx).getInt(KEY_VLESS_SRC, NONE)

    /** The VMESS source index the user is currently bonded to, or -1 if none yet. */
    fun vmessSource(ctx: Context): Int = prefs(ctx).getInt(KEY_VMESS_SRC, NONE)

    fun setVlessSource(ctx: Context, index: Int) =
        prefs(ctx).edit().putInt(KEY_VLESS_SRC, index).apply()

    fun setVmessSource(ctx: Context, index: Int) =
        prefs(ctx).edit().putInt(KEY_VMESS_SRC, index).apply()

    /** Forget the bonded sources (used by the 30-day reset). */
    fun clear(ctx: Context) =
        prefs(ctx).edit().remove(KEY_VLESS_SRC).remove(KEY_VMESS_SRC).apply()

    fun hasBond(ctx: Context): Boolean =
        vlessSource(ctx) != NONE || vmessSource(ctx) != NONE
}
