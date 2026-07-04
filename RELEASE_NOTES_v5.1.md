# Professor VPN — v5.1 Release Notes

**versionCode 32 · versionName 5.1**

## What v5.1 fixes

v5.0 still had a hidden **intermediary** in the tunnel path that caused the
"shows connected but no real upload/download, no real numbers" bug on Iran's
unstable internet. v5.1 removes it completely and makes the connection
**direct, real and fast** — exactly the way v2rayNG / NekoBox dial a clean
config.

### 1. Direct connection — no proxy / no DNS intermediary

The single biggest fix. v5.0 chained plain-TLS configs through a separate
`freedom` fragment dialer (`sockopt.dialerProxy = "frag-dialer"`). That extra
hop is an **intermediary** that:

- adds a second TCP dial + handshake before real bytes flow → slow first byte,
- occasionally fails the chained dial while the direct dial would succeed,
  which is exactly the "shows connected, no real upload/download" bug, and
- can corrupt the TLS-fragment split under Iran's packet loss → fake-connected.

v5.1 **removes the fragment dialer / dialerProxy entirely**. The proxy
outbound dials the server **DIRECTLY** — one TCP dial, one handshake, raw
bytes straight through. When a config pings green, it genuinely connects at
full speed. No middleman in the path.

Anti-DPI hardening that does **not** add a hop is kept: TCP NoDelay, keep-alive,
uTLS chrome fingerprint, and mux for ws/grpc/h2. These sit ON the proxy's own
socket, not behind another outbound.

### 2. Simplified DNS — no DoH intermediary

v5.1 uses plain-UDP resolvers (1.1.1.1 / 8.8.8.8) carried through the proxy +
a domestic resolver for Iranian domains only. **No DoH** — DoH endpoints can
themselves be blocked and need to bootstrap, which sometimes wedged resolution
so nothing opened even on a healthy tunnel ("connected but nothing opens").
Plain IPs resolved over the proxy are the proven, fastest v2rayNG approach and
never poison.

### 3. Real numbers, no fakes

Before reporting **Connected**, v5.1 still drives real bytes **through the
local SOCKS5 inbound** (the same socket tun2socks feeds) and requires a real
remote TCP connection through the tunnel. If not even one connection opens, it
does **not** claim connected — it reports an error so the user can pick another
server. Upload/download numbers come straight from the Xray core stats API
(with a tun2socks native-counter fallback), so they are real, never fabricated.

### 4. Tuned for Iran's unstable internet

- handshake window slightly longer (10s) so a slow first RTT on a weak link
  isn't dropped mid-TLS,
- long connIdle (600s) so a healthy tunnel survives quiet moments and
  "never drops",
- 512 KiB buffer — full throughput without stalling / OOM on low-RAM phones,
- watchdog rides out Iran's frequent disruptions (only gives up after many
  sustained failures WITH a live physical network).

### 5. Server names now travel with the config (Server 1 … 99999)

v5.1 bakes the neutral "Server N" name INTO the share link's `#remark`
fragment (and the vmess JSON `ps` field) — so when a user copies a config out
of this app and pastes it into **any other v2ray client**, the name stays
**Server 1 … up to 99999** exactly as it shows here. No more name changing
between apps.

## Supported protocols

Only **vless** and **vmess** (project policy) — including reality, xtls-vision,
ws, grpc, h2, kcp, xhttp transports.

## Build

Single signed universal APK (all ABIs): `ProfessorVPN-v5.1-universal.apk`.
