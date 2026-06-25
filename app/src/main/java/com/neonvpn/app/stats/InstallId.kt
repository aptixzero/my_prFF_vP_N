package com.neonvpn.app.stats

import android.content.Context
import java.security.MessageDigest
import java.util.UUID

/**
 * PRIVACY-FIRST anonymous install identity.
 *
 * The admin panel's "Users" section needs to count installs and tell online from
 * offline — WITHOUT ever learning who the user is. To make that possible while
 * exposing ZERO personal data we generate, on first launch, a single random
 * opaque identifier and persist it locally. It is:
 *
 *   • RANDOM — a fresh UUID v4 with no device, SIM, IP, location, account or
 *     hardware information mixed in. It cannot be reversed into anything real.
 *   • STABLE — the same value for the life of this install, so a returning user
 *     is counted once (accurate totals) instead of inflating the numbers.
 *   • OPAQUE — only an 12-hex-char hash of the UUID is ever sent off-device, so
 *     even the random UUID itself never leaves the phone.
 *
 * This is the minimum data needed for an aggregate count and nothing more. There
 * is deliberately no advertising id, no ANDROID_ID, no IMEI, no MAC — none of the
 * identifiers that could deanonymise a user are touched.
 */
object InstallId {

    private const val PREFS = "pv_anon_stats"
    private const val KEY_UUID = "anon_uuid"

    /** The local random UUID (never leaves the device). */
    @Synchronized
    fun raw(ctx: Context): String {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var id = sp.getString(KEY_UUID, null)
        if (id.isNullOrBlank()) {
            id = UUID.randomUUID().toString()
            sp.edit().putString(KEY_UUID, id).apply()
        }
        return id
    }

    /**
     * The SHORT opaque token actually reported off-device: 12 hex chars of a
     * SHA-256 of the random UUID. This is what the aggregate counter buckets by,
     * so the server-side store only ever sees an anonymous, non-reversible token.
     */
    fun anonToken(ctx: Context): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(raw(ctx).toByteArray(Charsets.UTF_8))
            digest.joinToString("") { "%02x".format(it) }.take(12)
        } catch (_: Throwable) {
            // extremely defensive fallback — still anonymous, still stable
            raw(ctx).filter { it.isLetterOrDigit() }.take(12).ifBlank { "anonymous000" }
        }
    }
}
