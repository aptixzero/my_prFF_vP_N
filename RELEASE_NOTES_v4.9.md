# Professor VPN — v4.9 Release Notes

**versionCode 30 · versionName 4.9**

v4.9 fixes the single most serious regression in the app: the **"fake connected"**
bug where the UI said *Connected* but **no real traffic flowed** (no upload /
download numbers, nothing loaded). The connection is now genuinely real,
end-to-end verified, faster and more stable.

---

## 🔴 The core bug that is fixed

Previously the connect path only confirmed that the proxy **outbound could
dial** (`measureDelay`). That test passed even when the full device path
(**TUN → tun2socks → local SOCKS5 → routing → proxy outbound**) was broken, so
the app reported *Connected* while nothing actually moved through the tunnel —
the exact "shows connected but no bytes / no speed" symptom.

### Root causes found & fixed

1. **TLS-record fragmentation was placed on the proxy outbound's `sockopt`
   (`fragment` + `dialerProxy: ""`).** In modern Xray-core this corrupts a
   VLESS / VMESS / Reality handshake — the TUN comes up (so the UI flips to
   *Connected*) but the encrypted handshake is malformed and **no real bytes
   ever transfer.**
   **Fix (v4.9):** fragmentation now lives on a **dedicated `freedom` dialer
   outbound** (`dialer`), and the proxy outbound chains its real network dial
   through it via `streamSettings.sockopt.dialerProxy = "dialer"`. This is the
   only correct, core-supported way to fragment the ClientHello. Result: the
   anti-DPI SNI-hiding is kept **and** traffic really flows.

2. **The `mux` block was always emitted, even when disabled
   (`concurrency: -1`).** Some Xray-core builds reject that sentinel, failing
   the whole config parse so the core couldn't route.
   **Fix (v4.9):** the `mux` object is now emitted **only when multiplexing is
   actually enabled**; when off it is omitted entirely (the universally-accepted
   way to disable mux).

3. **No end-to-end proof of real traffic before claiming "Connected".**
   **Fix (v4.9):** after the core is up, the service now issues a **real HTTP
   request through the local SOCKS5 inbound** (the exact socket tun2socks feeds)
   to a censored endpoint and **requires actual response bytes** before it
   reports *Connected*. If nothing transfers, the app honestly says
   *"No traffic through tunnel — pick another"* instead of faking success.

---

## 📊 Real, reliable upload / download numbers

- The stats pump baseline for the native tun2socks byte counters is now seeded
  from the first reading (a `-1` sentinel) so a genuine `0` is never mistaken for
  "unseeded" and a real delta is never lost.
- The tun baseline is **refreshed every tick** (even when the Xray stats API
  reported bytes), so if the API momentarily drops to `0` mid-session the
  fallback delta stays correct and the speed meter can't spike from a stale
  baseline.
- Upload / download / totals / ping / uptime remain **100 % real** — no random
  values anywhere (the `NoRandomInStatsTest` guard still enforces Golden Rule #2).

---

## ⚡ Speed & stability

- Per-config **ping now dials exactly like the live connection** (same proxy
  outbound **and** the same fragment dialer chain), so a config that pings green
  genuinely connects — the "ping == connects" rule holds true.
- Full-bandwidth tuning retained (4 MiB per-connection buffer, TCP no-delay,
  TCP Fast Open, tuned keep-alive) for maximum speed.
- Watchdog + auto-revive retained so a healthy tunnel rides out Iran's
  disrupted links without dropping, and the whole device is tunnelled across all
  apps (`addRoute 0.0.0.0/0` + IPv6).

---

## ✅ Golden-rule compliance

- Connection is **100 % real** and now **end-to-end verified**.
- **No fake / random** ping, upload or download.
- Internet off ⇒ **not connected** (health check + new traffic proof).
- Only **VLESS / VMESS** supported.
- **No ad-network scripts**, **no hardcoded credentials/tokens.**
