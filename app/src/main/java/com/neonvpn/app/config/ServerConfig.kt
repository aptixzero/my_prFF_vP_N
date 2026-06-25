package com.neonvpn.app.config

/**
 * Normalised representation of a single V2Ray / Xray server endpoint
 * parsed from a share link (vmess / vless / trojan / ss).
 */
data class ServerConfig(
    val id: String,                 // unique id (uuid-ish)
    val remark: String,             // display name
    val protocol: String,           // vmess | vless | trojan | shadowsocks
    val address: String,
    val port: Int,
    val rawLink: String,            // original share link, kept for re-export

    // auth / identity
    val userId: String = "",        // uuid for vmess/vless, password for trojan, none for ss
    val alterId: Int = 0,           // vmess only
    val security: String = "auto",  // vmess encryption / cipher
    val flow: String = "",          // vless xtls flow
    val password: String = "",      // trojan / ss password
    val method: String = "",        // shadowsocks cipher

    // stream settings
    val network: String = "tcp",    // tcp | ws | grpc | h2 | kcp | quic
    val headerType: String = "none",
    val host: String = "",          // ws/h2 Host header / http host
    val path: String = "",          // ws path / grpc serviceName / h2 path
    val tls: String = "",           // "" | tls | reality
    val sni: String = "",
    val alpn: String = "",
    val fingerprint: String = "",   // utls fingerprint
    val publicKey: String = "",     // reality
    val shortId: String = "",       // reality
    val spiderX: String = "",       // reality
    val seed: String = ""           // kcp seed
)
