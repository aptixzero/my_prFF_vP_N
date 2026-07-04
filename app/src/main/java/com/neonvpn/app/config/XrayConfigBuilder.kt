package com.neonvpn.app.config

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds a full, valid Xray-core JSON config from a [ServerConfig].
 *
 * Supports ONLY vless / vmess (project policy). Key points that make it work &
 * not crash, and that give a stable, fast tunnel:
 *
 *   - SOCKS5 inbound (tag "socks-in") that tun2socks feeds the TUN traffic into,
 *     with sniffing so routing/DNS sees real domains.
 *   - Real `stats` + `policy` + `api` blocks so queryStats(...) returns true
 *     up/down byte counters (the live speed meter). Without these the core
 *     throws on stats query and the service dies.
 *   - DNS with a fake-DNS-free, UseIP strategy + a local DNS outbound so the
 *     core resolves names itself (prevents leaks & speeds up first byte).
 *   - Anti-censorship hardening: TCP fragment on the dialer (tlshello) so DPI
 *     can't fingerprint the ClientHello, mux for ws/grpc, uTLS chrome
 *     fingerprint, TCP no-delay + keep-alive for a smooth, persistent tunnel.
 */
object XrayConfigBuilder {

    const val SOCKS_PORT = 10808
    const val API_PORT = 10809

    const val PROXY_TAG = "proxy"

    // v5.0 — freedom outbound used ONLY as the proxy's dialer when it is SAFE to
    // fragment (plain TLS, no XTLS flow). It is NEVER used for Reality / Vision /
    // plaintext, because chaining the proxy through a freedom dialer breaks the
    // XTLS-Vision splice and the Reality handshake — that was the v4.8/v4.9 "fake
    // connected, no traffic" bug: the TUN came up (UI said Connected) but the
    // corrupted handshake meant no real bytes ever flowed.
    const val FRAGMENT_TAG = "frag-dialer"

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
                    // v4.5 SPEED — handshake gets a touch more room so a node on
                    // Iran's disrupted links isn't dropped mid-TLS, while a long
                    // connIdle keeps a healthy tunnel alive through quiet moments
                    // (downloads that pause, screen-off, etc.) so it "never drops".
                    put("handshake", 8)
                    put("connIdle", 300)
                    put("uplinkOnly", 0)
                    put("downlinkOnly", 0)
                    put("statsUserUplink", true)
                    put("statsUserDownlink", true)
                    // v5.0 — 512 KiB buffer: the v2rayNG default that gives full
                    // throughput on phones without wasting RAM. The old 4 MiB was
                    // aggressive and could cause stalls / OOM on low-RAM devices,
                    // which looked like a dead tunnel.
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

        // ---- dns (Bypass-for-Iran aware) ----
        // Iranian domains resolve via a fast domestic resolver and go DIRECT, so
        // local sites stay snappy and don't waste the tunnel; everything else is
        // resolved through encrypted DoH (DoH defeats Iran's DNS poisoning) and
        // routed through the proxy. This split is the backbone of the bypass.
        // v5.0 — SIMPLE, robust DNS. Plain UDP resolvers (routed through the
        // proxy by the routing rules) for the free internet, and a domestic
        // resolver for Iranian domains only. The old DoH ("https://…") servers
        // could themselves be blocked / need to bootstrap and sometimes wedged
        // resolution so nothing loaded even on a healthy tunnel — a direct cause
        // of "connected but nothing opens". Plain IPs resolved over the proxy
        // are the proven v2rayNG approach.
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
            put("queryStrategy", "UseIP")
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
        val outbounds = JSONArray()
        // proxy MUST be first so measureOutboundDelay / stats target it.
        outbounds.put(buildProxyOutbound(cfg))
        // v5.0 — only add the fragment dialer when the proxy actually chains to
        // it (plain TLS, no flow). For Reality / Vision / plaintext it is NOT
        // added and the proxy dials directly, so real traffic flows.
        if (usesFragmentDialer(cfg)) {
            outbounds.put(buildFragmentDialer())
        }
        // direct freedom for Iranian/local traffic (no fragmentation needed —
        // these stay inside the country and must be fast).
        outbounds.put(JSONObject().apply {
            put("tag", "direct")
            put("protocol", "freedom")
            put("settings", JSONObject().apply { put("domainStrategy", "UseIP") })
        })
        // dns outbound so the core's own queries resolve
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
        // Rule order matters (first match wins):
        //   1. stats api  -> api outbound
        //   2. DNS (53)   -> dns outbound
        //   3. block ads/malware + bittorrent so they can't choke the tunnel
        //   4. Iranian domains / Iran IPs / private LAN -> DIRECT (fast, no tunnel)
        //   5. EVERYTHING ELSE -> proxy  (full bandwidth through the tunnel)
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
     * Minimal config used ONLY for a delay/ping test of a single server, without
     * bringing the VPN up. It has just the proxy outbound; the native
     * measureOutboundDelay dials through it and times a generate_204 request.
     */
    fun buildPingConfig(cfg: ServerConfig): String {
        val root = JSONObject()
        root.put("log", JSONObject().apply { put("loglevel", "none") })
        // v5.0 — the ping dials EXACTLY like the live connection (same proxy
        // outbound, and the same fragment dialer only when the proxy chains to
        // one) so a config that pings green genuinely connects and carries data.
        root.put("outbounds", JSONArray().apply {
            put(buildProxyOutbound(cfg))
            if (usesFragmentDialer(cfg)) put(buildFragmentDialer())
        })
        return root.toString()
    }

    private fun buildProxyOutbound(cfg: ServerConfig): JSONObject {
        // Dedicated builders per protocol — a tuned "engine" for VLESS and a
        // tuned one for VMESS, each with the settings that give that protocol
        // the best speed + stability on Iran's disrupted internet.
        return when (cfg.protocol) {
            "vless" -> buildVlessOutbound(cfg)
            "vmess" -> buildVmessOutbound(cfg)
            else -> buildVlessOutbound(cfg)   // parser only emits vless/vmess
        }
    }

    /**
     * v5.0 — fragmentation is ONLY safe for plain TLS WITHOUT an XTLS flow.
     *   • Reality  → fragmenting/chaining breaks the Reality handshake.
     *   • XTLS flow (vision) → needs the raw socket, cannot be chained.
     *   • plaintext → no ClientHello to hide; chaining just adds fragility.
     * In all those cases the proxy dials DIRECTLY (no dialerProxy) so traffic
     * really flows. Only tls-without-flow opts into the fragment dialer.
     */
    private fun usesFragmentDialer(cfg: ServerConfig): Boolean {
        val isPlainTls = cfg.tls.equals("tls", true)
        val hasFlow = cfg.flow.isNotBlank()
        return isPlainTls && !hasFlow
    }

    /**
     * v5.0 — freedom outbound whose `settings.fragment` splits the TLS
     * ClientHello. This is the proven v2rayNG placement: the proxy outbound sets
     * `sockopt.dialerProxy = FRAGMENT_TAG` and the actual TCP dial + fragmentation
     * happens here. Used ONLY for plain-TLS-no-flow configs (see usesFragmentDialer)
     * so it can never corrupt a Reality / Vision handshake.
     */
    private fun buildFragmentDialer(): JSONObject {
        val out = JSONObject()
        out.put("tag", FRAGMENT_TAG)
        out.put("protocol", "freedom")
        out.put("settings", JSONObject().apply {
            put("domainStrategy", "AsIs")
            put("fragment", JSONObject().apply {
                put("packets", "tlshello")
                put("length", "100-200")
                put("interval", "10-20")
            })
        })
        out.put("streamSettings", JSONObject().apply {
            put("sockopt", JSONObject().apply {
                put("tcpNoDelay", true)
                put("tcpKeepAliveIdle", 100)
                put("mark", 255)
            })
        })
        return out
    }

    /**
     * VLESS engine. VLESS is stateless and lean (no built-in encryption — TLS /
     * Reality does it), so it shines for raw throughput. Reality / XTLS-vision
     * flows want mux OFF and the connection kept 1:1 for full single-stream
     * line-rate, which is exactly what a power user asking for "تمام پهنای باند"
     * needs. We always advertise encryption=none (correct for VLESS) and pass the
     * xtls flow through when present.
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
     * pick the fastest cipher the device supports (AES-GCM on ARMv8 with crypto
     * extensions, ChaCha20-Poly1305 otherwise) — best speed across old & new
     * phones. We also leave mux OFF here for the same full-bandwidth reason; the
     * stream tuning (fragment / keep-alive / buffers) is shared via
     * buildStreamSettings so VMESS gets the same anti-DPI hardening as VLESS.
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
                            // "auto" => fastest AEAD cipher the CPU supports
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
     * v4.4 — SMART multiplexing for max throughput + stability.
     *
     * The right mux setting depends on the transport:
     *   • Reality / XTLS-vision flow → mux is INCOMPATIBLE and would break the
     *     connection, so it MUST stay off.
     *   • Raw TCP+TLS → one dedicated stream per app socket already gives full
     *     single-stream line-rate with zero head-of-line blocking, so off is the
     *     fastest for big downloads.
     *   • ws / grpc / h2 → these run over a single long-lived connection where
     *     opening a fresh handshake per request is slow and fragile on Iran's
     *     disrupted internet. Mux pools many app requests onto a few carrier
     *     streams, which is noticeably FASTER and far MORE STABLE here — pages
     *     and apps stop stalling while a new sub-connection is negotiated.
     */
    private fun applyMux(out: JSONObject, cfg: ServerConfig) {
        val net = cfg.network.ifBlank { "tcp" }.lowercase()
        val isReality = cfg.tls.equals("reality", true) || cfg.tls.equals("xtls", true)
        val hasFlow = cfg.flow.isNotBlank()
        val muxFriendly = net in setOf("ws", "grpc", "h2", "http")
        val enable = muxFriendly && !isReality && !hasFlow
        // v4.9 — only EMIT the mux block when it is genuinely enabled. Some
        // Xray-core builds reject a mux object with `concurrency: -1` (the old
        // "disabled" sentinel), which failed the whole config parse and left the
        // core unable to actually route traffic (part of the "fake connected"
        // bug). When mux is off we simply omit the block entirely — the correct,
        // universally-accepted way to disable multiplexing.
        if (enable) {
            out.put("mux", JSONObject().apply {
                put("enabled", true)
                // 8 concurrent sub-streams per carrier connection is the v2rayNG
                // sweet-spot: enough parallelism for snappy browsing without the
                // overhead of too many open streams.
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

        // v5.0 — sockopt. Genuine socket tuning only. `dialerProxy` is set ONLY
        // for plain-TLS-no-flow (chaining to the fragment dialer); it is NEVER
        // set for Reality / Vision / plaintext, so those flows keep the raw
        // socket they require and real bytes actually flow (fixes the v4.8/v4.9
        // "fake connected, no traffic" bug).
        stream.put("sockopt", JSONObject().apply {
            if (usesFragmentDialer(cfg)) {
                // route this outbound's TCP dial through the fragment dialer,
                // which splits the ClientHello — safe because there's no XTLS
                // flow / Reality splice to break.
                put("dialerProxy", FRAGMENT_TAG)
            }
            put("tcpKeepAliveIdle", 100)
            put("tcpNoDelay", true)         // no Nagle delay → snappier first byte
            put("mark", 255)
        })

        return stream
    }
}
