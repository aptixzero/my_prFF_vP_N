package com.neonvpn.app.config

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds a full, valid Xray-core JSON config from a [ServerConfig].
 *
 * v5.5 — REAL WORKING TUNNEL (restored from the last known-good method).
 *
 * The v4.6 → v5.4 line was BROKEN: those versions kept stripping the pieces that
 * actually make the tunnel carry traffic on a filtered network. v5.3 hid the
 * anti-DPI fragmentation behind a `dialerProxy` chain (which measureOutboundDelay
 * does NOT walk, so ping lied and the live path often broke); v5.4 then removed
 * the DNS block AND the fragmentation entirely — leaving a config that finishes
 * a TCP/TLS handshake (so ping is green and the UI turns "Connected") yet gets
 * its ClientHello RST-injected by DPI, so NO real bytes ever flow. That is
 * exactly the "shows connected but nothing works / doesn't use the config" bug.
 *
 * This version goes back to the proven approach — the one that WORKED:
 *
 *   1. A proper `dns` block (encrypted DoH for the free internet to beat DNS
 *      poisoning + a domestic resolver for Iranian domains) so name resolution
 *      never wedges and local sites stay fast.
 *   2. TLS-ClientHello FRAGMENTATION applied DIRECTLY in the proxy outbound's own
 *      `streamSettings.sockopt.fragment` (NOT via a dialerProxy chain). Splitting
 *      the ClientHello across tiny TCP segments hides the SNI from DPI, so the
 *      connection survives instead of being reset — this is what makes real
 *      traffic actually flow through the selected config.
 *   3. Full-tunnel routing: everything except Iranian domains / private LAN goes
 *      through the proxy, at full bandwidth — a fast filtershkan, not a proxy.
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

        // ---- stats + api (so queryStats returns real up/down counters) ----
        root.put("stats", JSONObject())
        root.put("api", JSONObject().apply {
            put("tag", "api")
            put("services", JSONArray().apply { put("StatsService") })
        })
        root.put("policy", JSONObject().apply {
            put("levels", JSONObject().apply {
                put("8", JSONObject().apply {
                    put("handshake", 4)
                    put("connIdle", 300)
                    put("uplinkOnly", 1)
                    put("downlinkOnly", 1)
                    put("statsUserUplink", true)
                    put("statsUserDownlink", true)
                    // Larger per-connection buffer => higher throughput on big
                    // transfers (full bandwidth). 1 MiB is a strong, safe
                    // sweet-spot without spiking RAM on weak phones.
                    put("bufferSize", 1024)
                })
            })
            put("system", JSONObject().apply {
                put("statsInboundUplink", true)
                put("statsInboundDownlink", true)
                put("statsOutboundUplink", true)
                put("statsOutboundDownlink", true)
            })
        })

        // ---- dns (anti-poisoning + Iran-aware) ----
        // Encrypted DoH resolvers defeat Iran's DNS poisoning for the free
        // internet; a fast domestic resolver handles Iranian domains so they stay
        // direct and snappy. Without a working DNS block, name resolution can
        // wedge on some ISPs and nothing loads even on a live tunnel.
        root.put("dns", JSONObject().apply {
            put("servers", JSONArray().apply {
                put("https://1.1.1.1/dns-query")
                put("https://dns.google/dns-query")
                put("1.1.1.1")
                put("8.8.8.8")
                put(JSONObject().apply {
                    put("address", "78.157.42.100")           // domestic resolver
                    put("port", 53)
                    put("domains", JSONArray().apply {
                        put("geosite:category-ir")
                        put("domain:ir")
                    })
                    put("skipFallback", true)
                })
            })
            put("queryStrategy", "UseIP")
            put("disableCache", false)
            put("disableFallbackIfMatch", true)
            put("tag", "dns-out-tag")
        })

        // ---- inbounds ----
        // SOCKS inbound that tun2socks feeds all device traffic into, and a
        // dokodemo-door for the stats API.
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
            put("sniffing", JSONObject().apply {
                put("enabled", true)
                put("destOverride", JSONArray().apply { put("http"); put("tls"); put("quic") })
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
        // proxy FIRST so measureDelay / stats target it. Then a plain freedom for
        // Iranian/local traffic, a dns outbound so the core's own queries resolve,
        // and a blackhole for blocks.
        val outbounds = JSONArray()
        outbounds.put(buildProxyOutbound(cfg))
        outbounds.put(JSONObject().apply {
            put("tag", "direct")
            put("protocol", "freedom")
            put("settings", JSONObject().apply { put("domainStrategy", "UseIP") })
        })
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

        // ---- routing (full-tunnel, Iran-aware) ----
        // Order matters (first match wins):
        //   1. stats api  -> api outbound
        //   2. DNS (53)   -> dns outbound
        //   3. bittorrent -> block (so it can't saturate the tunnel)
        //   4. Iranian domains / Iran IPs / private LAN -> DIRECT (fast, no tunnel)
        //   5. EVERYTHING ELSE -> proxy (full bandwidth through the tunnel)
        root.put("routing", JSONObject().apply {
            put("domainStrategy", "IPIfNonMatch")
            put("rules", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "field")
                    put("inboundTag", JSONArray().apply { put("api-in") })
                    put("outboundTag", "api")
                })
                put(JSONObject().apply {
                    put("type", "field")
                    put("port", 53)
                    put("outboundTag", "dns-out")
                })
                put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "block")
                    put("protocol", JSONArray().apply { put("bittorrent") })
                })
                put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "direct")
                    put("domain", JSONArray().apply {
                        put("geosite:category-ir")
                        put("domain:ir")
                    })
                })
                put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "direct")
                    put("ip", JSONArray().apply {
                        put("geoip:ir")
                        put("geoip:private")
                    })
                })
                put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", PROXY_TAG)
                    put("port", "0-65535")
                })
            })
        })

        return root.toString()
    }

    /**
     * Minimal config for a per-config ping — the SAME outbound the live connect
     * uses (identical fragmentation + stream settings), so the measured latency
     * is the real latency the tunnel will have. "ping == connects", done honestly.
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
        return when (cfg.protocol) {
            "vless" -> buildVlessOutbound(cfg)
            "vmess" -> buildVmessOutbound(cfg)
            else -> buildVlessOutbound(cfg)
        }
    }

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
        // Mux OFF for VLESS: Reality/XTLS-vision are incompatible with mux, and
        // even on plain TLS a dedicated 1:1 proxied stream per app connection
        // gives the fastest single-stream bandwidth with no head-of-line blocking.
        out.put("mux", JSONObject().apply {
            put("enabled", false)
            put("concurrency", -1)
        })
        return out
    }

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
        out.put("mux", JSONObject().apply {
            put("enabled", false)
            put("concurrency", -1)
        })
        return out
    }

    private fun buildStreamSettings(cfg: ServerConfig): JSONObject {
        val stream = JSONObject()
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

        // Socket options + TLS-record FRAGMENTATION applied DIRECTLY on the proxy
        // outbound (NOT via a dialerProxy chain — that was the v5.3 mistake that
        // made ping lie and broke the live path). Splitting the ClientHello across
        // tiny TCP segments means DPI never sees the SNI in one packet, so SNI /
        // keyword blocking and RST-injection can't fingerprint and tear down the
        // connection. This is what makes real traffic actually flow through the
        // selected config on a filtered network. uTLS mimicry (above) + keep-alive
        // keep the tunnel persistent and full-speed.
        stream.put("sockopt", JSONObject().apply {
            put("tcpKeepAliveIdle", 30)
            put("tcpKeepAliveInterval", 15)
            put("tcpNoDelay", true)
            put("tcpMptcp", false)
            put("mark", 0)
            if (sec == "tls" || sec == "reality") {
                put("tcpFastOpen", true)
                put("fragment", JSONObject().apply {
                    put("packets", "tlshello")
                    put("length", "100-200")
                    put("interval", "10-20")
                })
            } else {
                // for plaintext transports, fragment the first few packets so
                // protocol-pattern DPI still can't lock on immediately.
                put("fragment", JSONObject().apply {
                    put("packets", "1-3")
                    put("length", "100-200")
                    put("interval", "10-20")
                })
            }
        })

        return stream
    }
}
