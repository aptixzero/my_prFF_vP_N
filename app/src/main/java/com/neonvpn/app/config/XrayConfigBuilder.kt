package com.neonvpn.app.config

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds a full, valid Xray-core JSON config from a [ServerConfig].
 *
 * v5.1 — DIRECT, NO-INTERMEDIARY TUNNEL (the "real connection" fix).
 * ─────────────────────────────────────────────────────────────────────────────
 * v5.0 still chained plain-TLS configs through a separate `freedom` fragment
 * dialer (`sockopt.dialerProxy = "frag-dialer"`). On Iran's unstable, high-RTT
 * links that extra hop is an intermediary that:
 *   • adds a second TCP dial + handshake before real bytes flow → slow first byte,
 *   • occasionally fails the chained dial while the direct dial would succeed,
 *     which is exactly the "shows connected, no real upload/download" bug, and
 *   • can corrupt the TLS-fragment split under packet loss → fake-connected.
 *
 * The user requirement is explicit: connect DIRECTLY to the config, no proxy /
 * no DNS intermediary in the path — when ping says green it must genuinely
 * connect at full speed. v5.1 therefore:
 *
 *   1. REMOVES the fragment dialer / dialerProxy entirely. The proxy outbound
 *      dials the server DIRECTLY, the way v2rayNG / NekoBox do on a "clean"
 *      config. This is the fastest, most stable path: one TCP dial, one
 *      handshake, raw bytes straight through.
 *   2. KEEPS anti-DPI hardening that does NOT add a hop: TCP NoDelay, keep-alive,
 *      uTLS chrome fingerprint, mux for ws/grpc/h2. These sit ON the proxy's own
 *      socket, not behind another outbound.
 *   3. SIMPLIFIES DNS to plain-IP resolvers carried through the proxy + a
 *      domestic resolver for Iranian domains only. No DoH (DoH endpoints can
 *      themselves be blocked and wedge resolution → "connected but nothing
 *      opens"). Plain UDP to 1.1.1.1 / 8.8.8.8 routed through the tunnel is the
 *      proven, fastest approach and never poisons.
 *   4. Clean tcp+udp proxy rule so the full bandwidth of the tunnel is used.
 *
 * Only vless / vmess are supported (project policy).
 */
object XrayConfigBuilder {

    const val SOCKS_PORT = 10808
    const val API_PORT = 10809

    const val PROXY_TAG = "proxy"

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
                    // v5.1 — tuned for SPEED + PERSISTENCE on Iran's disrupted links.
                    // A slightly longer handshake window so a slow first RTT on a
                    // weak link isn't dropped mid-TLS; a long connIdle so a healthy
                    // tunnel survives quiet moments (screen-off, paused downloads)
                    // and "never drops"; uplinkOnly/downlinkOnly=0 so a one-way
                    // burst (big download) isn't torn down.
                    put("handshake", 10)
                    put("connIdle", 600)
                    put("uplinkOnly", 0)
                    put("downlinkOnly", 0)
                    put("statsUserUplink", true)
                    put("statsUserDownlink", true)
                    // 512 KiB buffer — the v2rayNG default that gives full throughput
                    // on phones without stalling / OOM on low-RAM devices.
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

        // ---- dns (simple, robust, no DoH intermediary) ----
        // v5.1 — Plain UDP resolvers (carried through the proxy by the routing
        // rules below) for the free internet, and a domestic resolver for Iranian
        // domains only. NO DoH (DoH endpoints can themselves be blocked / need
        // to bootstrap and sometimes wedge resolution so nothing loads even on a
        // healthy tunnel — a direct cause of "connected but nothing opens").
        // Plain IPs resolved over the proxy are the proven v2rayNG approach and
        // are the FASTEST: one UDP round-trip, no TLS handshake for DNS.
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
        // v5.1 — NO fragment dialer outbound. The proxy dials the server DIRECTLY.
        // This is the single biggest fix: no intermediary hop means real bytes
        // actually flow the moment the handshake completes, at full speed.
        val outbounds = JSONArray()
        // proxy MUST be first so measureOutboundDelay / stats target it.
        outbounds.put(buildProxyOutbound(cfg))
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
        // Rule order matters (first match wins):
        //   1. stats api  -> api outbound
        //   2. DNS (53)   -> dns outbound (carried through the proxy)
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
     *
     * v5.1 — the ping dials EXACTLY like the live connection (the same DIRECT
     * proxy outbound, no intermediary dialer) so a config that pings green
     * genuinely connects and carries data. This is the "ping == connects" rule.
     */
    fun buildPingConfig(cfg: ServerConfig): String {
        val root = JSONObject()
        root.put("log", JSONObject().apply { put("loglevel", "none") })
        root.put("outbounds", JSONArray().apply {
            put(buildProxyOutbound(cfg))
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
     * pick the fastest cipher the device supports (AES-GCM on ARMv8 with crypto
     * extensions, ChaCha20-Poly1305 otherwise) — best speed across old & new
     * phones. We also leave mux OFF here for the same full-bandwidth reason; the
     * stream tuning (keep-alive / buffers / uTLS) is shared via
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
     * v5.1 — SMART multiplexing for max throughput + stability.
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
        // core unable to actually route traffic. When mux is off we simply omit
        // the block entirely — the correct, universally-accepted way to disable
        // multiplexing.
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

        // v5.1 — sockopt. Genuine socket tuning ON THE PROXY'S OWN SOCKET, with
        // NO dialerProxy / NO intermediary hop. The proxy dials the server
        // DIRECTLY (the single biggest "real connection" fix) and these options
        // sit on that direct socket:
        //   • tcpNoDelay = true  → disables Nagle, snappier first byte + throughput.
        //   • tcpKeepAliveIdle   → keeps the long-lived tunnel alive through quiet
        //                          moments so it "never drops".
        //   • mark = 255         → marks the core's own sockets so they bypass the
        //                          TUN (no routing loop), egress over the real link.
        // NO dialerProxy here — that was the v5.0 intermediary that caused the
        // fake-connected / no-real-traffic bug. Direct dial = real bytes flow.
        stream.put("sockopt", JSONObject().apply {
            put("tcpKeepAliveIdle", 100)
            put("tcpNoDelay", true)         // no Nagle delay → snappier first byte
            put("mark", 255)
        })

        return stream
    }
}
