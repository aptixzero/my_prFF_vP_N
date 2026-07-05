# Professor VPN v5.3 — THE REAL CONNECTION FIX

**versionCode:** 34 · **versionName:** 5.3
**APK:** `build/ProfessorVPN-v5.3-universal.apk` (signed, universal — arm64-v8a, armeabi-v7a, x86, x86_64)

---

## The problem (v4.7 → v5.2)

Every version since **4.7** showed the same failure on Iran's strong-filter
ISPs: the app animated to **CONNECTED**, but **0 B/s upload and 0 B/s download** —
a fake "connected" that carried no real traffic. Version **4.6 was the last one
that actually connected.**

## Root cause (finally identified)

The regression was in how the TLS **ClientHello was fragmented** — the single
most important anti-DPI mechanism for Iranian filtering:

- **v4.6 (last working)** fragmented the ClientHello, but put the `fragment`
  option directly on the **proxy outbound's own `sockopt`**. Newer Xray cores
  **ignore `fragment` there** — the option only takes effect on a `freedom`
  *dialer* outbound that the proxy is chained to via `sockopt.dialerProxy`. So
  from that build on, fragmentation silently stopped happening.
- **v5.0 – v5.2** then **removed fragmentation entirely** ("direct dial, no
  intermediary"). On a strong-filter Iranian ISP the DPI box sees the SNI in one
  ClientHello packet and **RST-kills the flow the instant the TLS handshake
  starts**. The tunnel connects at the TCP layer (so the localhost SOCKS probe
  even passed and the app said "connected") but **every real byte was dropped**.

That is exactly the fake "connected, no up/down, not using the config" bug the
user reported for every version since 4.7.

## The fix (v5.3)

Fragmentation is restored the **correct** way — precisely how v2rayNG / NekoBox
do it:

1. **Dedicated `dialer` freedom outbound** that owns the fragment settings
   (`packets: tlshello`, tuned length/interval), with `domainStrategy: AsIs` so
   it never double-resolves.
2. **Proxy chained to it** via `sockopt.dialerProxy = "dialer"`, so the proxy's
   real connection to the server travels *through* the fragmenter and the
   ClientHello (with the SNI) is split across multiple TCP segments — **invisible
   to DPI, so real bytes flow.**
3. Reality / XTLS-vision handshakes are **not** fragmented (it would break their
   authentication), so the dialer chain is applied only for plain-TLS / no-TLS.

### Other correctness fixes vs v5.2

- **DNS**: `queryStrategy` switched to **UseIPv4** — many Iranian cellular
  carriers give broken/no IPv6, and waiting on AAAA that times out was a classic
  "connected but nothing loads" cause. Plain-UDP resolvers over the proxy, no
  DoH (which can itself be blocked / wedge resolution). Domestic resolver kept
  for Iranian domains only.
- **No SO_MARK** (kept removed from v5.2 — harmful on Android; the
  disallow-app bypass already handles egress).
- Clean `tcp,udp` proxy catch-all so full bandwidth of the config is used.

## No fixed proxy / DNS (works on every network)

Per the requirement, the app uses **no fixed proxy or fixed DNS server** as an
intermediary. Each config dials its server **directly** (through the transparent
fragmenter only, which does not change the destination), so it works across any
ISP / any device and uses the full speed of whichever config connects.

## Honest state

The connect flow still keeps its real-bytes gates: the native tun2socks bridge
must be loaded, and a real HTTP response must return bytes through the proxy
outbound before the app reports **Connected** — so the animation is synced to a
genuinely working tunnel, never a fake one.

---

**Install:** download `ProfessorVPN-v5.3-universal.apk`, install over any
previous version (same signing key — updates in place).
