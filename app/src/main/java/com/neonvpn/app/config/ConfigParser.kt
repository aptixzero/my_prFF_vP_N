package com.neonvpn.app.config

import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.util.UUID

/**
 * Parses V2Ray / Xray share links into [ServerConfig].
 *
 * PROJECT POLICY — this client supports **ONLY** two protocols:
 *   - vmess://   (classic base64-JSON  *and*  the newer base64-body + query form)
 *   - vless://   (URI format, incl. reality / xtls / ws / grpc / xhttp)
 *
 * Everything else (trojan://, ss://, ssr://, hysteria, …) is intentionally
 * ignored — when a mixed source contains them, only the vless / vmess entries
 * are returned. This guarantees the rest of the app (XrayConfigBuilder, the
 * connect path) only ever deals with the two protocols we fully support.
 *
 * Also handles:
 *   - subscription content where each non-empty line is one link,
 *   - base64-encoded subscription blobs,
 *   - links concatenated back-to-back without separators,
 *   - leading emoji / whitespace before the scheme (`vless:// uuid@…`),
 *   - HTML-escaped query separators (`&amp;`, `&amp%3B`).
 */
object ConfigParser {

    // Only the two schemes we actually support are scanned for.
    private val SCHEMES = listOf("vmess://", "vless://")

    /** Parse possibly-many links from a blob of text (clipboard / subscription).
     *
     *  This is the heart of the "paste from clipboard doesn't detect my config"
     *  fix. Real-world clipboards are messy: the user may have copied SEVERAL
     *  configs at once, the configs may be glued together with **no separator**,
     *  separated by spaces / newlines / tabs / `|`, wrapped in emoji or symbols
     *  (🔥, ✅, ➖, ▪️ …), or surrounded by channel-name decorations. Some are a
     *  single big base64 subscription blob. We robustly recover EVERY vless /
     *  vmess link from ANY of those shapes.
     */
    fun parseMany(input: String): List<ServerConfig> {
        val text = input.trim()
        if (text.isEmpty()) return emptyList()

        // Build the pool of text we scan. The clipboard might be:
        //   (a) raw text containing links (possibly with noise), and/or
        //   (b) one big base64 subscription blob. We try BOTH: decode the whole
        //       thing as base64 and, if that yields links, scan the decoded text
        //       too — never trusting only one interpretation.
        val haystacks = mutableListOf(text)
        val decodedBlob = tryDecodeBase64(text)
        if (decodedBlob != null && looksLikeLinks(decodedBlob)) {
            haystacks.add(0, decodedBlob)   // prefer the decoded form first
        }

        // Collect every candidate link across all haystacks. We DON'T split on
        // newlines first anymore — instead we scan the entire blob for scheme
        // occurrences, so links glued together or wrapped in noise are all found.
        val candidates = LinkedHashSet<String>()
        for (hay in haystacks) {
            // strip comment lines but keep everything else intact for scanning
            val cleaned = hay.lineSequence()
                .filterNot { val t = it.trim(); t.startsWith("#") || t.startsWith("//") }
                .joinToString("\n")
            candidates.addAll(extractLinks(cleaned))
        }

        val result = mutableListOf<ServerConfig>()
        val seen = HashSet<String>()
        for (link in candidates) {
            val clean = sanitizeLink(link)
            if (clean.isEmpty()) continue
            try {
                val cfg = parse(clean) ?: continue
                if (cfg.address.isBlank() || cfg.port !in 1..65535) continue   // skip junk
                if (cfg.protocol != "vless" && cfg.protocol != "vmess") continue
                // vless/vmess identity must have a user id
                if (cfg.userId.isBlank()) continue
                val key = dedupKey(cfg)
                if (seen.add(key)) result.add(cfg)
            } catch (_: Throwable) {
                // skip malformed entry, continue with the rest
            }
        }
        return result
    }

    /**
     * Pull every vless/vmess link out of a text blob, even when multiple links
     * are concatenated directly (no separator), separated by spaces / tabs /
     * newlines / `|`, or wrapped in emoji / symbols. Algorithm:
     *
     *   1. Find every scheme occurrence (`vmess://` / `vless://`) in the blob.
     *   2. The link starting at occurrence i ends where the NEXT occurrence
     *      starts (so back-to-back links are split correctly).
     *   3. Within that slice, the link also ends at the first hard separator
     *      that can't be part of a share link — a whitespace char, `|`, `<`,
     *      `>`, `"`, or a quote — UNLESS we're still inside the `#remark`
     *      portion (remarks may legitimately contain spaces / emoji). To keep
     *      remarks that DO contain spaces, we only cut on whitespace when no
     *      `#` has appeared yet in the slice; once a `#` is seen, the rest of
     *      the slice (up to the next scheme / newline) is treated as the remark.
     */
    private fun extractLinks(blob: String): List<String> {
        val lower = blob.lowercase()
        val starts = mutableListOf<Int>()
        var idx = 0
        while (idx < lower.length) {
            var next = -1
            for (s in SCHEMES) {
                val p = lower.indexOf(s, idx)
                if (p >= 0 && (next == -1 || p < next)) next = p
            }
            if (next == -1) break
            starts.add(next)
            idx = next + 1
        }
        if (starts.isEmpty()) return emptyList()

        val out = mutableListOf<String>()
        for (i in starts.indices) {
            val from = starts[i]
            val hardEnd = if (i + 1 < starts.size) starts[i + 1] else blob.length
            val slice = blob.substring(from, hardEnd)
            out.add(trimLinkSlice(slice))
        }
        return out
    }

    /**
     * Given a slice that begins exactly at a scheme, return just the link part:
     * cut at the first separator that can't be inside the link BODY, but allow
     * spaces / emoji inside the `#remark` tail. Newlines always terminate.
     */
    private fun trimLinkSlice(slice: String): String {
        var hashSeen = false
        var end = slice.length
        var i = 0
        while (i < slice.length) {
            val c = slice[i]
            if (c == '\n' || c == '\r') { end = i; break }
            if (c == '#') { hashSeen = true; i++; continue }
            if (!hashSeen) {
                // before the remark, these chars cannot be part of the body
                if (c == ' ' || c == '\t' || c == '|' || c == '<' || c == '>' ||
                    c == '"' || c == '\'' || c == '`' || c == '\u00a0'
                ) { end = i; break }
            }
            i++
        }
        return slice.substring(0, end).trim()
    }

    /**
     * Normalise a single raw link so it parses cleanly:
     *   - chop anything before the scheme (emoji / stray chars),
     *   - drop a stray '|' tail,
     *   - remove the space some feeds put right after the scheme
     *     (`vless:// uuid@host` -> `vless://uuid@host`),
     *   - un-escape HTML query separators (`&amp;` / `&amp%3B` -> `&`),
     *   - strip trailing zero-width / BOM chars.
     * The link itself and the remark fragment (after #) are preserved.
     */
    private fun sanitizeLink(raw: String): String {
        // strip leading zero-width / BOM / direction marks that feeds inject
        var s = raw.trim().trim('\u200b', '\u200c', '\u200d', '\u200e', '\u200f', '\ufeff', '\u00a0')
        val lower = s.lowercase()
        var cut = -1
        for (sc in SCHEMES) {
            val p = lower.indexOf(sc)
            if (p >= 0 && (cut == -1 || p < cut)) cut = p
        }
        if (cut == -1) return ""
        if (cut > 0) s = s.substring(cut)

        // Split the link into BODY (before #) and REMARK (after #). We must NOT
        // touch the remark — it may legitimately contain `|`, spaces, emoji. We
        // only sanitise the body, where a stray `|` or space means a glued
        // neighbour link / decoration that slipped through.
        val hashIdx = s.indexOf('#')
        var body = if (hashIdx >= 0) s.substring(0, hashIdx) else s
        val remark = if (hashIdx >= 0) s.substring(hashIdx) else ""

        // cut a trailing pipe-delimited neighbour link from the BODY only
        val pipe = body.indexOf('|')
        if (pipe > 0) body = body.substring(0, pipe)
        // any stray whitespace inside the body (other than right after scheme)
        // also means a glued neighbour — keep only up to the first inner space.
        for (sc in SCHEMES) {
            if (body.startsWith(sc, true)) {
                val rest = body.substring(sc.length).trimStart()
                val sp = rest.indexOfFirst { it == ' ' || it == '\t' || it == '\u00a0' }
                val cleanRest = if (sp >= 0) rest.substring(0, sp) else rest
                body = sc + cleanRest
                break
            }
        }

        // un-escape HTML entity separators that some feeds inject
        body = body.replace("&amp%3B", "&")
            .replace("&amp;", "&")
            .replace("%26amp%3B", "&")

        s = body + remark
        return s.trim().trimEnd('\u200b', '\u200c', '\u200e', '\u200f', '\ufeff', '`', '\u00a0')
    }

    /** Stable identity for de-duplication that ignores the (cosmetic) remark. */
    fun dedupKey(c: ServerConfig): String =
        "${c.protocol}|${c.address.lowercase()}|${c.port}|${c.userId}|${c.network}|${c.path}|${c.host}|${c.tls}"

    /**
     * v5.6 — STABLE ping key. Ping/latency results are stored against the config's
     * CONTENT (not its ephemeral UUID `id`, which is regenerated every time the
     * same link is re-parsed). Keying pings by content means the last measured
     * ping "sticks" to a config forever: it survives app restart, tab switch,
     * screen off/on, an Auto-Test batch replacing the free list with freshly-parsed
     * copies, and the same config appearing in BOTH Free and My Configs. This is
     * the definitive fix for "the pings I measured keep disappearing / resetting".
     */
    fun pingKey(c: ServerConfig): String = dedupKey(c)

    /**
     * "Location" key used to cap how many configs share the same server endpoint.
     * Many public lists publish 10-20 near-identical configs that all point at
     * the SAME IP / SNI host (same physical location), differing only by remark
     * or a trivial query field — adding them all just clutters the list with what
     * is effectively one server. We treat configs as same-location when their
     * resolved endpoint matches. We key primarily on the network address (the IP
     * or front domain) and, when present, the real SNI/host (CDN-fronted nodes
     * share a front IP but the SNI identifies the true backend). This lets the
     * fetcher keep at most N (=3) per location, killing duplicates.
     */
    fun locationKey(c: ServerConfig): String {
        val addr = c.address.trim().lowercase()
        // For TLS/Reality nodes the SNI is the true server identity (the address
        // is often a shared Cloudflare/Fastly front), so include it; otherwise
        // the address+port pair is the location.
        val sni = c.sni.trim().lowercase()
        val realId = when {
            sni.isNotBlank() -> sni
            c.host.isNotBlank() -> c.host.trim().lowercase()
            else -> addr
        }
        return "$realId|${c.port}"
    }

    private fun looksLikeLinks(s: String): Boolean =
        s.contains("vmess://") || s.contains("vless://")

    /**
     * Parse ONE line that is expected to be a single share link (used by the new
     * Free-config source where every file line is one config). Sanitises the
     * line first (strips emoji / BOM / stray separators) and returns null safely
     * for anything that isn't a valid vless/vmess link — never throws.
     */
    fun parseSingleSafe(line: String): ServerConfig? {
        val clean = sanitizeLink(line)
        if (clean.isEmpty()) return null
        return try { parse(clean) } catch (_: Throwable) { null }
    }

    /** Parse a single share link. Returns null if unsupported. */
    fun parse(link: String): ServerConfig? {
        val l = link.trim()
        return when {
            l.startsWith("vmess://", true) -> parseVmess(l)
            l.startsWith("vless://", true) -> parseVless(l)
            else -> null   // trojan / ss / ssr / anything else => ignored
        }
    }

    // ---------------------------------------------------------------- VMESS
    /**
     * Two real-world vmess formats:
     *   A) classic v2rayN  -> vmess://<base64 of a JSON object>
     *   B) DukeMehdi style  -> vmess://<base64(uuid@host:port)>?type=ws&...#name
     * Both are supported here.
     */
    private fun parseVmess(link: String): ServerConfig? {
        var body = link.substring("vmess://".length).trim()

        // split off remark / query (only relevant for form B)
        var remarkPart = ""
        val hashIdx = body.indexOf('#')
        if (hashIdx >= 0) {
            remarkPart = body.substring(hashIdx + 1)
            body = body.substring(0, hashIdx)
        }
        var queryPart = ""
        val qIdx = body.indexOf('?')
        if (qIdx >= 0) {
            queryPart = body.substring(qIdx + 1)
            body = body.substring(0, qIdx)
        }

        val decoded = tryDecodeBase64(body)

        // ---- form A: decoded is a JSON object ----
        if (decoded != null && decoded.trim().startsWith("{")) {
            val o = JSONObject(decoded)
            val net = o.optString("net", "tcp").ifBlank { "tcp" }
            val tlsVal = o.optString("tls", "")
            return ServerConfig(
                id = UUID.randomUUID().toString(),
                remark = o.optString("ps", "vmess node").ifBlank { "vmess node" },
                protocol = "vmess",
                address = o.optString("add"),
                port = o.optString("port", "0").toIntOrNull() ?: 0,
                rawLink = link,
                userId = o.optString("id"),
                alterId = o.optString("aid", "0").toIntOrNull() ?: 0,
                security = o.optString("scy", "auto").ifBlank { "auto" },
                network = net,
                headerType = o.optString("type", "none").ifBlank { "none" },
                host = o.optString("host", ""),
                path = o.optString("path", ""),
                tls = if (tlsVal.equals("tls", true)) "tls" else tlsVal,
                sni = o.optString("sni", ""),
                alpn = o.optString("alpn", ""),
                fingerprint = o.optString("fp", "")
            )
        }

        // ---- form B: decoded is "uuid@host:port" + separate query/remark ----
        if (decoded != null && decoded.contains("@")) {
            val at = decoded.lastIndexOf('@')
            val userId = decoded.substring(0, at)
            val hostPort = decoded.substring(at + 1)
            val colon = hostPort.lastIndexOf(':')
            val host = if (colon > 0) hostPort.substring(0, colon) else hostPort
            val portStr = if (colon > 0) hostPort.substring(colon + 1) else "443"
            val port = portStr.trimEnd('/').toIntOrNull() ?: 0
            val q = queryToMap(queryPart)
            val tls = q["security"] ?: ""
            return ServerConfig(
                id = UUID.randomUUID().toString(),
                remark = decodeFragment(remarkPart) ?: "vmess node",
                protocol = "vmess",
                address = host,
                port = port,
                rawLink = link,
                userId = userId,
                alterId = (q["aid"] ?: "0").toIntOrNull() ?: 0,
                security = (q["encryption"] ?: q["scy"] ?: "auto").ifBlank { "auto" },
                network = q["type"] ?: "tcp",
                headerType = q["headerType"] ?: "none",
                host = q["host"] ?: "",
                path = q["path"] ?: (q["serviceName"] ?: ""),
                tls = if (tls.equals("tls", true)) "tls" else tls,
                sni = q["sni"] ?: "",
                alpn = q["alpn"] ?: "",
                fingerprint = q["fp"] ?: ""
            )
        }

        return null
    }

    // ---------------------------------------------------------------- VLESS
    private fun parseVless(link: String): ServerConfig? {
        // Try the standard URI parser first; if it can't extract host/user (some
        // odd encodings break android.net.Uri) fall back to a manual split so a
        // valid vless link is never silently dropped.
        val uri = Uri.parse(link)
        var userId = uri.userInfo ?: ""
        var host = uri.host ?: ""
        var port = if (uri.port > 0) uri.port else 443

        if (host.isBlank() || userId.isBlank()) {
            val manual = manualUserHostPort(link, "vless://")
            if (manual != null) {
                if (userId.isBlank()) userId = manual.first
                if (host.isBlank()) host = manual.second
                if (uri.port <= 0 && manual.third > 0) port = manual.third
            }
        }
        if (host.isBlank()) return null
        val q = queryMap(link)

        // ignore exotic transports we don't actually support well; keep ws/grpc/
        // tcp/h2/kcp/xhttp (xhttp maps to tcp-ish handling downstream).
        val net = (q["type"] ?: "tcp").lowercase()

        return ServerConfig(
            id = UUID.randomUUID().toString(),
            remark = decodeFragment(uri.fragment) ?: "vless node",
            protocol = "vless",
            address = host,
            port = port,
            rawLink = link,
            userId = userId,
            flow = q["flow"] ?: "",
            network = net,
            headerType = q["headerType"] ?: "none",
            host = q["host"] ?: "",
            path = q["path"] ?: (q["serviceName"] ?: ""),
            tls = q["security"] ?: "",
            sni = q["sni"] ?: "",
            alpn = q["alpn"] ?: "",
            fingerprint = q["fp"] ?: "",
            publicKey = q["pbk"] ?: "",
            shortId = q["sid"] ?: "",
            spiderX = q["spx"] ?: "",
            seed = q["seed"] ?: ""
        )
    }

    // ------------------------------------------------------------- helpers
    /**
     * Manual `scheme://user@host:port` extractor used as a fallback when
     * android.net.Uri fails on an odd encoding. Returns Triple(user, host, port)
     * or null if it can't find a `user@host` shape.
     */
    private fun manualUserHostPort(link: String, scheme: String): Triple<String, String, Int>? {
        var body = link.substring(scheme.length)
        // chop query + fragment
        val q = body.indexOf('?')
        if (q >= 0) body = body.substring(0, q)
        val h = body.indexOf('#')
        if (h >= 0) body = body.substring(0, h)
        val at = body.lastIndexOf('@')
        if (at <= 0) return null
        val user = body.substring(0, at)
        var hostPort = body.substring(at + 1).trim('/')
        // strip IPv6 brackets if present
        var host: String
        var port = 0
        if (hostPort.startsWith("[")) {
            val end = hostPort.indexOf(']')
            if (end < 0) return null
            host = hostPort.substring(1, end)
            val rest = hostPort.substring(end + 1)
            if (rest.startsWith(":")) port = rest.substring(1).toIntOrNull() ?: 0
        } else {
            val colon = hostPort.lastIndexOf(':')
            if (colon > 0) {
                host = hostPort.substring(0, colon)
                port = hostPort.substring(colon + 1).toIntOrNull() ?: 0
            } else host = hostPort
        }
        if (host.isBlank()) return null
        return Triple(user, host, port)
    }

    private fun queryMap(link: String): Map<String, String> {
        val qStart = link.indexOf('?')
        if (qStart < 0) return emptyMap()
        var query = link.substring(qStart + 1)
        val frag = query.indexOf('#')
        if (frag >= 0) query = query.substring(0, frag)
        return queryToMap(query)
    }

    private fun queryToMap(query: String): Map<String, String> {
        val map = HashMap<String, String>()
        if (query.isBlank()) return map
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            if (eq < 0) {
                map[pair.lowercase()] = ""
            } else {
                val k = pair.substring(0, eq)
                val v = pair.substring(eq + 1)
                map[k] = safeDecode(v)
            }
        }
        return map
    }

    private fun safeDecode(v: String): String = try {
        Uri.decode(v)
    } catch (_: Exception) {
        v
    }

    private fun decodeFragment(frag: String?): String? {
        if (frag.isNullOrBlank()) return null
        return try {
            Uri.decode(frag)
        } catch (_: Exception) {
            frag
        }
    }

    // ============================================================ v5.1 NAMING
    /**
     * v5.1 — bake the neutral "Server N" name INTO the share link's `#remark`
     * fragment, so the name travels WITH the config.
     *
     * The user's problem: a config copied out of this app showed as "Server 1"
     * here, but when pasted into another v2ray client the remark changed (the
     * other client either kept the original feed branding baked into the link,
     * or showed its own auto-name). The fix is to REWRITE the link's `#remark`
     * fragment to the neutral "Server N" label right when we name it, so:
     *
     *   • the name we display is exactly the name embedded in the link, and
     *   • when the user copies the link out and pastes it anywhere else, the
     *     OTHER client reads the SAME "Server N" remark from the link — so the
     *     name stays consistent across apps (Server 1 stays Server 1 … up to
     *     99999).
     *
     * Works for both supported formats:
     *   • vless://…?…#remark  → replace the fragment with the encoded name.
     *   • vmess://<base64-of-JSON>  → rewrite the JSON's "ps" (remark) field,
     *     re-encode, and rebuild the link. (Form-B vmess with a query/fragment
     *     is handled by the fragment path.)
     *
     * Returns the rewritten link. Never throws — on any failure the original
     * link is returned unchanged (the name still displays correctly in-app via
     * [ServerConfig.remark]).
     */
    fun rewriteRemark(rawLink: String, newRemark: String): String {
        return try {
            if (rawLink.isBlank()) return rawLink
            if (rawLink.startsWith("vless://", true)) {
                rewriteVlessRemark(rawLink, newRemark)
            } else if (rawLink.startsWith("vmess://", true)) {
                rewriteVmessRemark(rawLink, newRemark)
            } else {
                rawLink
            }
        } catch (_: Throwable) {
            rawLink   // never lose a config over a rename
        }
    }

    /** Replace the `#fragment` of a vless link with the URL-encoded [newRemark]. */
    private fun rewriteVlessRemark(link: String, newRemark: String): String {
        // strip existing fragment (and any trailing junk after it)
        val hashIdx = link.indexOf('#')
        val base = if (hashIdx >= 0) link.substring(0, hashIdx) else link
        // URL-encode the remark so spaces / non-ASCII survive a copy/paste round-trip
        val enc = try { Uri.encode(newRemark) } catch (_: Throwable) { newRemark }
        return "$base#$enc"
    }

    /**
     * vmess form A is `vmess://<base64 of JSON>` with the remark in the JSON's
     * "ps" field. We decode, rewrite "ps", re-encode, and rebuild the link so
     * the name is baked into the base64 blob itself. Form B (with a `#fragment`)
     * is handled like vless.
     */
    private fun rewriteVmessRemark(link: String, newRemark: String): String {
        var body = link.substring("vmess://".length).trim()
        // If there's a fragment, rewrite it (form B / DukeMehdi style).
        val hashIdx = body.indexOf('#')
        if (hashIdx >= 0) {
            val baseBody = body.substring(0, hashIdx)
            val enc = try { Uri.encode(newRemark) } catch (_: Throwable) { newRemark }
            return "vmess://$baseBody#$enc"
        }
        // Form A: base64-of-JSON. Decode, rewrite "ps", re-encode.
        val qIdx = body.indexOf('?')
        if (qIdx >= 0) body = body.substring(0, qIdx)   // drop query for decode
        val decoded = tryDecodeBase64(body) ?: return link
        if (!decoded.trim().startsWith("{")) return link
        val o = JSONObject(decoded)
        o.put("ps", newRemark)
        val reencoded = try {
            android.util.Base64.encodeToString(
                o.toString().toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP
            )
        } catch (_: Throwable) {
            return link
        }
        return "vmess://$reencoded"
    }

    /** Try standard + url-safe base64, with or without padding. */
    private fun tryDecodeBase64(input: String): String? {
        val s = input.trim().replace("\n", "").replace("\r", "")
        if (s.isEmpty()) return null
        val flags = intArrayOf(
            Base64.NO_WRAP,
            Base64.URL_SAFE or Base64.NO_WRAP,
            Base64.DEFAULT
        )
        for (f in flags) {
            try {
                val padded = when (s.length % 4) {
                    2 -> "$s=="
                    3 -> "$s="
                    else -> s
                }
                val bytes = Base64.decode(padded, f)
                val out = String(bytes, Charsets.UTF_8)
                if (out.isNotEmpty()) return out
            } catch (_: Exception) {
            }
        }
        return null
    }
}
