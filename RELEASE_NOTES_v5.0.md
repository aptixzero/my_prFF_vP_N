# Professor VPN — v5.0 Release Notes

**versionCode 31 · versionName 5.0**

## The fix: REAL connection, real traffic

Previous builds (4.8 / 4.9) could show **"Connected"** while carrying **no real
traffic** — no upload/download, nothing loaded. v5.0 removes the exact config
mistakes that caused this and rebuilds the Xray config to match the proven
v2rayNG / NekoBox layout so a server that pings green **actually works**.

### Root causes removed

1. **`dialerProxy` chaining broke the tunnel.**
   4.9 forced EVERY proxy outbound to dial through a separate `freedom` fragment
   outbound (`sockopt.dialerProxy`). For **Reality** and **XTLS-Vision** flows
   this corrupts the handshake — Vision must own the raw TCP socket to splice,
   and Reality can't be chained. The handshake completed just enough for the TUN
   to come up (UI said *Connected*) but **no bytes ever flowed**.
   -> v5.0 **never** uses `dialerProxy` for Reality / Vision / plaintext. Those
   dial directly, exactly like v2rayNG.

2. **Blanket fragmentation on every config.**
   Fragmentation is only safe on **plain TLS without an XTLS flow**. v5.0 applies
   it **only** in that case, the proven way (a freedom dialer whose
   `settings.fragment` splits the ClientHello). Everything else runs clean.

3. **Fragile DNS / over-complex routing.**
   The DoH resolvers and heavy routing could wedge name resolution so nothing
   opened even on a healthy tunnel. v5.0 uses simple plain-IP resolvers routed
   through the proxy + a domestic resolver for Iranian domains only, and a clean
   tcp+udp proxy rule.

4. **4 MiB per-connection buffer -> stalls / OOM on low-RAM phones.**
   Reverted to the v2rayNG-default **512 KiB**, which gives full throughput
   without stalling.

### Real-traffic gate (kept, but tolerant)

Before reporting **Connected**, v5.0 drives real bytes **through the local SOCKS5
inbound** (the same socket tun2socks feeds) to censored edges over 443 and 80.
It requires a genuine remote TCP connection through the tunnel (and, when
possible, real response bytes). It is tolerant enough to never reject a working
server, but still catches a truly dead tunnel -- so *Connected* means *working*.

### Result

- Reality / Vision / TLS / plaintext, vless & vmess: **real** end-to-end traffic.
- Live **upload / download** numbers from the real Xray + tun2socks counters.
- Full-device tunnel on all apps, stable watchdog, mobile-data bypass intact.
- Faster first byte (tcpNoDelay), full bandwidth, persistent keep-alive.

### Unchanged guarantees

- No fake / random ping, upload or download.
- Internet off => **not connected**.
- Only **VLESS / VMESS** supported.
- No ad-network scripts, no hardcoded credentials/tokens.
