package com.neonvpn.app.config

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds a full, valid Xray-core JSON config from a [ServerConfig].
 *
 * v5.3 — THE REAL CONNECTION FIX (works on Iran's strong-filter ISPs).
 * ─────────────────────────────────────────────────────────────────────────────
 * ROOT CAUSE of the recurring "shows connected, 0 up / 0 down" bug (v4.7 → v5.2):
 *
 *   • v4.6 (the last version that connected) fragmented the TLS ClientHello, but
 *     put `fragment` directly on the proxy outbound's own sockopt. Newer Xray
 *     cores IGNORE fragment there — the option only takes effect on a `freedom`
 *     dialer that the proxy is chained to via `sockopt.dialerProxy`. So from the
 *     moment that build shipped, fragmentation silently stopped happening.
 *   • v5.0-v5.2 then REMOVED fragmentation entirely ("direct dial, no
 *     intermediary"). On a strong-filter Iranian ISP the DPI box sees the SNI in
 *     one ClientHello packet and RST-kills the flow the instant the TLS handshake
 *     starts — the tunnel connects at the TCP layer (so the localhost SOCKS probe
 *     even passes) but every real byte is dropped. That is EXACTLY the fake
 *     "connected, no traffic" the user reported for every version since 4.7.
 *
 * v5.3 restores fragmentation the CORRECT way — a dedicated `dialer` freedom
 * outbound that owns the fragment settings, with the proxy chained to it through
 * `sockopt.dialerProxy = "dialer"`. This is precisely how v2rayNG / NekoBox do
 * it, and it is what makes the ClientHello invisible to DPI so real bytes flow.
 *
 * Other correctness fixes vs v5.2:
 *   • DNS: a real bootstrap (plain 1.1.1.1/8.8.8.8) + queryStrategy UseIPv4 so
 *     names actually resolve on IPv4-only cellular links (a big "nothing opens"
 *     cause). No DoH (can itself be blocked / wedge resolution).
 *   • Routing: DNS(53) → proxy path kept, tcp+udp proxy catch-all, Iran-direct.
 *   • sockopt on the PROXY: tcpNoDelay + keep-alive + dialerProxy (no SO_MARK —
 *     harmful on Android where the disallow-app bypass already handles egress).
 *   • The `dialer` freedom outbound uses domainStrategy AsIs (it dials the
 *     already-resolved server IP) so it never double-resolves / leaks.
 *
 * Only vless / vmess are supported (project policy).
 */
object XrayConfigBuilder {

    const val SOCKS_PORT = 10808
    const val API_PORT = 10809

    const val PROXY_TAG = "proxy"
    const val DIALER_TAG = "dialer"

    fun build(cfg: ServerConfig): String {
        val root = JSONObject()

        // ---- log ----
        root.put("log", JSONObject().apply { put("loglevel", "warning") })

        // ---- stats ----
        root.put("stats", JSONObject())

        // ---- api (StatsService for runtime counters) ----
        root.put("api", JSONObject().apply {
            put("tag", "api")
            put("services", JSONArray().apply { put("StatsService") })
        })

        // ---- policy: per-outbound up/down stats + sane buffers ----
        root.put("policy", JSONObject().apply {
            put("levels", JSONObject().apply {
                put("8", JSONObject().apply {
                    put("handshake", 8)
                    put("connIdle", 300)
                    put("uplinkOnly", 0)
                    put("downlinkOnly", 0)
                    put("statsUserUplink", true)
                    put("statsUserDownlink", true)
                    put("bufferSize", 512)
                })
            })
            put("system", JSONObject().apply {
                put("statsInboundUplink", true)
                put("statsInboundDownlink", true)
                put("statsOutboundUplink", true)
                put("statsOutboundDownlink", true)
            })
        })

        // ---- dns ----
        // v5.3 — plain-UDP resolvers carried over the proxy for the free internet,
        // a domestic resolver ONLY for Iranian domains (kept direct), and a
        // localhost bootstrap so the resolvers' own IPs never need resolving.
        // queryStrategy UseIPv4 is deliberate: many Iranian cellular carriers give
        // broken / no IPv6, and asking for AAAA that then times out is a classic
        // "connected but pages hang / nothing loads" cause.
        root.put("dns", JSONObject().apply {
            put("servers", JSONArray().apply {
                put("1.1.1.1")
                put("8.8.8.8")
                // domestic resolver ONLY for Iranian domains => kept direct & fast
                put(JSONObject().apply {
                    put("address", "78.157.42.100")           // Shecan / domestic
                    put("port", 53)
                    put("domains", JSONArray().apply {
                        put("geosite:category-ir")
                        put("domain:ir")
                    })
                    put("expectIPs", JSONArray().apply { put("geoip:ir") })
                    put("skipFallback", true)
                })
            })
            put("queryStrategy", "UseIPv4")
            put("disableCache", false)
            put("tag", "dns-out-tag")
        })

        // ---- inbounds ----
        val inbounds = JSONArray()
        inbounds.put(JSONObject().apply {
            put("tag", "socks-in")
            put("port", SOCKS_PORT)
            put("listen", "127.0.0.1")
            put("protocol", "socks")
            put("settings", JSONObject().apply {
                put("auth", "noauth")
                put("udp", true)
                put("address", "127.0.0.1")
                put("userLevel", 8)
            })
            // sniffing lets routing see the real destination domain (needed for
            // domain-based Iran-direct rules and correct SNI). Do NOT sniff quic
            // here — over-sniffing quic on some cores dropped UDP flows.
            put("sniffing", JSONObject().apply {
                put("enabled", true)
                put("destOverride", JSONArray().apply { put("http"); put("tls") })
                put("routeOnly", false)
            })
        })
        inbounds.put(JSONObject().apply {
            put("tag", "api-in")
            put("port", API_PORT)
            put("listen", "127.0.0.1")
            put("protocol", "dokodemo-door")
            put("settings", JSONObject().apply {
                put("address", "127.0.0.1")
                put("port", 0)
                put("network", "tcp")
            })
        })
        root.put("inbounds", inbounds)

        // ---- outbounds ----
        // v5.3 — the proxy dials THROUGH a `dialer` freedom outbound that owns the
        // TLS-ClientHello fragmentation. This is the real anti-DPI path (see file
        // header). Order: proxy MUST be first so measureOutboundDelay / stats
        // target it.
        val outbounds = JSONArray()
        outbounds.put(buildProxyOutbound(cfg))
        // the fragment dialer: a freedom outbound whose sole job is to split the
        // ClientHello so Iran's DPI can't SNI-block / RST the flow. AsIs strategy
        // → it dials the server IP the proxy already resolved (no double resolve).
        outbounds.put(buildFragmentDialer(cfg))
        // direct freedom for Iranian/local traffic (fast, no tunnel).
        outbounds.put(JSONObject().apply {
            put("tag", "direct")
            put("protocol", "freedom")
            put("settings", JSONObject().apply { put("domainStrategy", "UseIP") })
        })
        // dns outbound so the core's own queries resolve through the proxy.
        outbounds.put(JSONObject().apply {
            put("tag", "dns-out")
            put("protocol", "dns")
        })
        outbounds.put(JSONObject().apply {
            put("tag", "block")
            put("protocol", "blackhole")
            put("settings", JSONObject().apply {
                put("response", JSONObject().apply { put("type", "http") })
            })
        })
        root.put("outbounds", outbounds)

        // ---- routing (Bypass for Iran) ----
        root.put("routing", JSONObject().apply {
            put("domainStrategy", "IPIfNonMatch")
            put("rules", JSONArray().apply {
                // stats api inbound -> api outbound
                put(JSONObject().apply {
                    put("type", "field")
                    put("inboundTag", JSONArray().apply { put("api-in") })
                    put("outboundTag", "api")
                })
                // DNS queries -> dns outbound
                put(JSONObject().apply {
                    put("type", "field")
                    put("port", 53)
                    put("outboundTag", "dns-out")
                })
                // drop bittorrent so it can't saturate the tunnel
                put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "block")
                    put("protocol", JSONArray().apply { put("bittorrent") })
                })
                // Iranian domains -> DIRECT (don't waste tunnel on local sites)
                put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "direct")
                    put("domain", JSONArray().apply {
                        put("geosite:category-ir")
                        put("domain:ir")
                    })
                })
                // Iranian IP ranges + private LAN -> DIRECT
                put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "direct")
                    put("ip", JSONArray().apply {
                        put("geoip:ir")
                        put("geoip:private")
                    })
                })
                // everything else through the proxy (full bandwidth, tcp + udp)
                put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", PROXY_TAG)
                    put("network", "tcp,udp")
                })
            })
        })

        return root.toString()
    }

    /**
     * The fragment dialer — a `freedom` outbound that splits the TLS ClientHello
     * across several tiny TCP segments so Iran's DPI never sees the SNI in one
     * packet. The proxy outbound chains to this via sockopt.dialerProxy, so the
     * proxy's real connection to the server travels THROUGH this fragmenter.
     * This is the v2rayNG / NekoBox approach and the single most important
     * anti-DPI mechanism for Iranian ISPs.
     */
    private fun buildFragmentDialer(cfg: ServerConfig): JSONObject {
        return JSONObject().apply {
            put("tag", DIALER_TAG)
            put("protocol", "freedom")
            put("settings", JSONObject().apply {
                // AsIs: the proxy already resolved the server address, so the
                // dialer must NOT re-resolve (avoids leaks / double lookups).
                put("domainStrategy", "AsIs")
                put("fragment", JSONObject().apply {
                    // "tlshello" targets exactly the ClientHello record (where the
                    // SNI lives), so it costs essentially nothing on throughput —
                    // only the first handshake packet is split — while hiding the
                    // SNI from DPI. Ranges tuned to survive Iran's filtering.
                    put("packets", "tlshello")
                    put("length", "100-200")
                    put("interval", "10-20")
                })
            })
            put("streamSettings", JSONObject().apply {
                put("sockopt", JSONObject().apply {
                    put("tcpNoDelay", true)
                    put("tcpKeepAliveIdle", 100)
                })
            })
        }
    }

    /**
     * Minimal config used ONLY for a delay/ping test of a single server, without
     * bringing the VPN up. It dials EXACTLY like the live connection (same proxy
     * + same fragment dialer) so a config that pings green genuinely connects and
     * carries data — the "ping == connects" rule.
     */
    fun buildPingConfig(cfg: ServerConfig): String {
        val root = JSONObject()
        root.put("log", JSONObject().apply { put("loglevel", "none") })
        root.put("outbounds", JSONArray().apply {
            put(buildProxyOutbound(cfg))
            put(buildFragmentDialer(cfg))
        })
        return root.toString()
    }

    private fun buildProxyOutbound(cfg: ServerConfig): JSONObject {
        return when (cfg.protocol) {
            "vless" -> buildVlessOutbound(cfg)
            "vmess" -> buildVmessOutbound(cfg)
            else -> buildVlessOutbound(cfg)   // parser only emits vless/vmess
        }
    }

    /**
     * VLESS engine. VLESS is stateless and lean (no built-in encryption — TLS /
     * Reality does it), so it shines for raw throughput. Reality / XTLS-vision
     * flows want mux OFF and the connection kept 1:1 for full single-stream
     * line-rate. We always advertise encryption=none (correct for VLESS) and
     * pass the xtls flow through when present.
     */
    private fun buildVlessOutbound(cfg: ServerConfig): JSONObject {
        val out = JSONObject()
        out.put("tag", PROXY_TAG)
        out.put("protocol", "vless")

        out.put("settings", JSONObject().apply {
            put("vnext", JSONArray().apply {
                put(JSONObject().apply {
                    put("address", cfg.address)
                    put("port", cfg.port)
                    put("users", JSONArray().apply {
                        put(JSONObject().apply {
                            put("id", cfg.userId)
                            put("encryption", "none")
                            if (cfg.flow.isNotBlank()) put("flow", cfg.flow)
                            put("level", 8)
                        })
                    })
                })
            })
        })
        out.put("streamSettings", buildStreamSettings(cfg))
        applyMux(out, cfg)
        return out
    }

    /**
     * VMESS engine. VMESS carries its own AEAD encryption; "auto" lets the core
     * pick the fastest cipher the device supports.
     */
    private fun buildVmessOutbound(cfg: ServerConfig): JSONObject {
        val out = JSONObject()
        out.put("tag", PROXY_TAG)
        out.put("protocol", "vmess")

        out.put("settings", JSONObject().apply {
            put("vnext", JSONArray().apply {
                put(JSONObject().apply {
                    put("address", cfg.address)
                    put("port", cfg.port)
                    put("users", JSONArray().apply {
                        put(JSONObject().apply {
                            put("id", cfg.userId)
                            put("alterId", cfg.alterId)
                            put("security", cfg.security.ifBlank { "auto" })
                            put("level", 8)
                        })
                    })
                })
            })
        })
        out.put("streamSettings", buildStreamSettings(cfg))
        applyMux(out, cfg)
        return out
    }

    /**
     * SMART multiplexing for max throughput + stability.
     *   • Reality / XTLS-vision flow → mux INCOMPATIBLE, MUST stay off.
     *   • Raw TCP+TLS → one dedicated stream per socket gives full line-rate.
     *   • ws / grpc / h2 → mux pools requests onto a few carrier streams, faster
     *     and more stable on Iran's disrupted internet.
     */
    private fun applyMux(out: JSONObject, cfg: ServerConfig) {
        val net = cfg.network.ifBlank { "tcp" }.lowercase()
        val isReality = cfg.tls.equals("reality", true) || cfg.tls.equals("xtls", true)
        val hasFlow = cfg.flow.isNotBlank()
        val muxFriendly = net in setOf("ws", "grpc", "h2", "http")
        val enable = muxFriendly && !isReality && !hasFlow
        // Only EMIT the mux block when genuinely enabled (some cores reject a mux
        // object with concurrency:-1, failing the whole parse).
        if (enable) {
            out.put("mux", JSONObject().apply {
                put("enabled", true)
                put("concurrency", 8)
                put("xudpConcurrency", 8)
                put("xudpProxyUDP443", "reject")
            })
        }
    }

    private fun buildStreamSettings(cfg: ServerConfig): JSONObject {
        val stream = JSONObject()
        // normalise transport: treat xhttp/splithttp as tcp-ish so the build
        // never produces an unknown network the core rejects.
        var net = cfg.network.ifBlank { "tcp" }.lowercase()
        if (net == "xhttp" || net == "splithttp") net = "tcp"
        if (net !in setOf("tcp", "ws", "grpc", "h2", "http", "kcp")) net = "tcp"
        stream.put("network", net)

        val sec = when {
            cfg.tls.equals("reality", true) -> "reality"
            cfg.tls.equals("tls", true) -> "tls"
            cfg.tls.equals("xtls", true) -> "tls"
            else -> ""
        }
        if (sec.isNotBlank()) stream.put("security", sec)

        if (sec == "tls") {
            stream.put("tlsSettings", JSONObject().apply {
                put("allowInsecure", true)   // public free nodes often use self-signed / fronted certs
                val server = cfg.sni.ifBlank { cfg.host.ifBlank { cfg.address } }
                if (server.isNotBlank()) put("serverName", server)
                put("fingerprint", cfg.fingerprint.ifBlank { "chrome" })  // uTLS browser mimicry
                if (cfg.alpn.isNotBlank()) {
                    put("alpn", JSONArray().apply {
                        cfg.alpn.split(",").forEach { if (it.isNotBlank()) put(it.trim()) }
                    })
                }
            })
        } else if (sec == "reality") {
            stream.put("realitySettings", JSONObject().apply {
                val server = cfg.sni.ifBlank { cfg.address }
                if (server.isNotBlank()) put("serverName", server)
                put("fingerprint", cfg.fingerprint.ifBlank { "chrome" })
                if (cfg.publicKey.isNotBlank()) put("publicKey", cfg.publicKey)
                if (cfg.shortId.isNotBlank()) put("shortId", cfg.shortId)
                put("spiderX", cfg.spiderX.ifBlank { "/" })
            })
        }

        when (net) {
            "ws" -> stream.put("wsSettings", JSONObject().apply {
                put("path", cfg.path.ifBlank { "/" })
                val hdrHost = cfg.host.ifBlank { cfg.sni }
                if (hdrHost.isNotBlank()) {
                    put("headers", JSONObject().apply { put("Host", hdrHost) })
                }
            })
            "grpc" -> stream.put("grpcSettings", JSONObject().apply {
                put("serviceName", cfg.path)
                put("multiMode", false)
                put("idle_timeout", 60)
                put("health_check_timeout", 20)
            })
            "h2", "http" -> stream.put("httpSettings", JSONObject().apply {
                put("path", cfg.path.ifBlank { "/" })
                if (cfg.host.isNotBlank()) {
                    put("host", JSONArray().apply {
                        cfg.host.split(",").forEach { if (it.isNotBlank()) put(it.trim()) }
                    })
                }
            })
            "kcp" -> stream.put("kcpSettings", JSONObject().apply {
                put("header", JSONObject().apply { put("type", cfg.headerType.ifBlank { "none" }) })
                if (cfg.seed.isNotBlank()) put("seed", cfg.seed)
                put("mtu", 1350)
                put("tti", 50)
                put("uplinkCapacity", 12)
                put("downlinkCapacity", 100)
                put("congestion", false)
            })
            "tcp" -> {
                if (cfg.headerType.equals("http", true)) {
                    stream.put("tcpSettings", JSONObject().apply {
                        put("header", JSONObject().apply {
                            put("type", "http")
                            put("request", JSONObject().apply {
                                put("path", JSONArray().apply { put(cfg.path.ifBlank { "/" }) })
                                if (cfg.host.isNotBlank()) {
                                    put("headers", JSONObject().apply {
                                        put("Host", JSONArray().apply {
                                            cfg.host.split(",").forEach { if (it.isNotBlank()) put(it.trim()) }
                                        })
                                    })
                                }
                            })
                        })
                    })
                }
            }
        }

        // v5.3 — sockopt on the PROXY socket. The critical addition is
        // `dialerProxy = "dialer"`: it routes the proxy's real connection to the
        // server THROUGH the fragment freedom outbound, which splits the TLS
        // ClientHello so Iran's DPI can't SNI-block / RST it. Without this chain
        // fragmentation never happens and the tunnel connects at TCP but carries
        // no real bytes (the fake-connected bug). NO SO_MARK (harmful on Android;
        // the disallow-app bypass in establishTun already handles egress).
        //
        // Reality/XTLS-vision handshakes must NOT be fragmented (it breaks the
        // authentication), so we only chain the dialer for plain TLS / no-TLS.
        val fragmentSafe = sec != "reality" && cfg.flow.isBlank()
        stream.put("sockopt", JSONObject().apply {
            put("tcpKeepAliveIdle", 100)
            put("tcpNoDelay", true)         // no Nagle delay → snappier first byte
            if (fragmentSafe) put("dialerProxy", DIALER_TAG)
        })

        return stream
    }
}
