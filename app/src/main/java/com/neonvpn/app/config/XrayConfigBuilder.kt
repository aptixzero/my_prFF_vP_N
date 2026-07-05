package com.neonvpn.app.config

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds a full, valid Xray-core JSON config from a [ServerConfig].
 *
 * v5.4 — DEAD-SIMPLE DIRECT CONNECTION (no DNS block, no proxy intermediary,
 * no fragment dialer, no dialerProxy). This is the smallest config that a real
 * Xray core needs to carry traffic, and it dials the selected config's server
 * DIRECTLY. Nothing sits between the device and the config's server except the
 * SOCKS inbound that tun2socks feeds and the proxy outbound itself.
 *
 * WHY the previous versions failed:
 *   • v5.3 added a `fragment` freedom dialer chained via `sockopt.dialerProxy`.
 *     The live tunnel then dialed:  socks-in → routing → proxy → dialerProxy →
 *     server. But the per-config PING used `measureOutboundDelay`, which times
 *     ONLY the proxy outbound handshake and does NOT walk the dialerProxy chain
 *     — so ping measured a different (shorter) path than the live tunnel. That
 *     is exactly "pings 100ms, connects at 700ms", and on many cores the
 *     dialerProxy chain simply broke the live tunnel so no bytes flowed at all.
 *   • The `dns` block (custom servers / domestic resolver / queryStrategy) could
 *     wedge name resolution on some ISPs so nothing loaded even on a live tunnel.
 *
 * v5.4 removes every one of those moving parts. The outbound dials the server
 * directly; ping and connect use the SAME outbound, so a green ping is the real
 * latency the live tunnel will have. No DNS block at all — the core resolves
 * names itself over the proxy the plain way, and the device's own DNS (sent to
 * the TUN and carried through the SOCKS inbound) just works.
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
                    put("handshake", 8)
                    put("connIdle", 300)
                    put("statsUserUplink", true)
                    put("statsUserDownlink", true)
                })
            })
            put("system", JSONObject().apply {
                put("statsOutboundUplink", true)
                put("statsOutboundDownlink", true)
            })
        })

        // ---- inbounds ----
        // SOCKS inbound that tun2socks feeds all device traffic into, and a
        // dokodemo-door for the stats API. NO dns inbound / dns block anywhere.
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
        // proxy FIRST so measureDelay / stats target it. Then a plain freedom for
        // the (very few) things routing sends direct, and a blackhole for blocks.
        val outbounds = JSONArray()
        outbounds.put(buildProxyOutbound(cfg))
        outbounds.put(JSONObject().apply {
            put("tag", "direct")
            put("protocol", "freedom")
            put("settings", JSONObject().apply { put("domainStrategy", "UseIP") })
        })
        outbounds.put(JSONObject().apply {
            put("tag", "block")
            put("protocol", "blackhole")
        })
        root.put("outbounds", outbounds)

        // ---- routing ----
        // Dead simple: stats api → api; private LAN → direct; EVERYTHING ELSE →
        // proxy (tcp+udp). No geo rules, no DNS routing — the config's server
        // carries all the user's traffic, using its full speed.
        root.put("routing", JSONObject().apply {
            put("domainStrategy", "AsIs")
            put("rules", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "field")
                    put("inboundTag", JSONArray().apply { put("api-in") })
                    put("outboundTag", "api")
                })
                put(JSONObject().apply {
                    put("type", "field")
                    put("ip", JSONArray().apply { put("geoip:private") })
                    put("outboundTag", "direct")
                })
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
     * Minimal config for a per-config ping — the SAME outbound the live connect
     * uses (no dialerProxy, no fragment, no DNS), so the measured latency is the
     * real latency the tunnel will have. This is the "ping == connects" rule,
     * done honestly: identical dialing path.
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
        applyMux(out, cfg)
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
        applyMux(out, cfg)
        return out
    }

    /**
     * mux for ws/grpc/h2 only (never for Reality/XTLS-vision or raw tcp+tls,
     * where 1:1 gives full line-rate). Only emit the block when enabled.
     */
    private fun applyMux(out: JSONObject, cfg: ServerConfig) {
        val net = cfg.network.ifBlank { "tcp" }.lowercase()
        val isReality = cfg.tls.equals("reality", true) || cfg.tls.equals("xtls", true)
        val hasFlow = cfg.flow.isNotBlank()
        val muxFriendly = net in setOf("ws", "grpc", "h2", "http")
        val enable = muxFriendly && !isReality && !hasFlow
        if (enable) {
            out.put("mux", JSONObject().apply {
                put("enabled", true)
                put("concurrency", 8)
            })
        }
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
                put("allowInsecure", true)
                val server = cfg.sni.ifBlank { cfg.host.ifBlank { cfg.address } }
                if (server.isNotBlank()) put("serverName", server)
                put("fingerprint", cfg.fingerprint.ifBlank { "chrome" })
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

        // Plain socket tuning ONLY. No mark, no fragment, no dialerProxy — the
        // proxy dials the server directly. This is exactly what a minimal
        // v2rayNG outbound does, and it is what actually carries traffic.
        stream.put("sockopt", JSONObject().apply {
            put("tcpNoDelay", true)
            put("tcpKeepAliveIdle", 100)
        })

        return stream
    }
}
