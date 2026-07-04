# Professor VPN — v5.2 Release Notes

**versionCode 33 · versionName 5.2**

## What v5.2 fixes

v5.1 still let a **dead/fake server show "connected"** — the app said connected,
the animation played, but **0 upload / 0 download and nothing actually worked**.
v5.2 closes that last gap with a **strict real-bytes proof** and a corrected
socket option, so when it says **Connected**, the tunnel genuinely carries
traffic at full speed.

### 1. Two hard gates before "Connected" — no more fake "connected"

v5.1's connect check accepted a bare TCP CONNECT ("ESTABLISHED") as proof that
the tunnel works. A dead or fake server can satisfy that — it accepts the SOCKS
CONNECT handshake, then **drops every byte**. The app reported "Connected" while
no real data ever flowed — exactly the bug you hit.

v5.2 now applies **two independent hard gates**; if either fails, the connect is
rejected with "Server carries no real traffic — pick another", so you never stare
at a fake "connected" again.

**Gate 1 — native tun2socks bridge must be loaded.** Real app traffic only flows
if the native `hev-socks5-tunnel` (TUN → SOCKS5 bridge) actually loaded. If it
didn't (ABI mismatch / failed extract / missing on a device), the SOCKS inbound
still answers a localhost probe but **no device traffic ever reaches the tunnel**
— the exact "0% works, no up/down" failure. v5.2 hard-checks `nativeAvailable`
(a clean boolean set once at startup, no timing race) and rejects immediately if
the bridge isn't there.

**Gate 2 — real bytes through the selected config's proxy outbound.** v5.2
snapshots the proxy-outbound traffic counters, sends an actual HTTP GET through
the local SOCKS5 inbound (the same socket tun2socks feeds) to censored endpoints
(Cloudflare edge, Telegram), then snapshots the counters again. It accepts only
if **either** the HTTP response returned > 0 real bytes **or** the proxy-outbound
counter moved during the probe. A dead server that opens a socket but drops every
byte fails **both** and is rejected. (OR, not AND, deliberately — some Xray-core
builds don't surface per-outbound stats, so requiring the delta would false-reject
good servers on those builds; the HTTP-bytes proof still holds everywhere.)

The tun2socks TUN byte counters are read only as a soft diagnostic (logged), never
as a reject condition — on an idle device no background app may have sent traffic
through the TUN in the ~1-2s since it came up, so a zero counter at that moment is
legitimate. Gate 1 already catches the real "dead tun2socks" failure mode without
that race.

### 2. Removed `sockopt.mark = 255` (wrong on Android)

v5.1 set `sockopt.mark = 255` on the proxy's sockets, copying a desktop-Linux
iptables/nftables pattern. On Android this is both **unnecessary** (the VPN's
own sockets are kept off the TUN by `VpnService.addDisallowedApplication(self)`,
already done) and **harmful** — on some kernels and cellular gateways the marked
packets get dropped by a local firewall / the carrier, which directly causes
"shows connected, no real upload/download". v5.2 removes the mark so the core's
sockets egress exactly the way v2rayNG's do (no mark, just the disallow-app
bypass) and real bytes flow.

### 3. Kept from v5.1 (still true)

- **Direct connection** — proxy dials the server DIRECTLY, one TCP dial, one
  handshake, raw bytes. No proxy / no DNS intermediary in the path.
- **No DoH** — plain-UDP resolvers (1.1.1.1 / 8.8.8.8) through the proxy + a
  domestic resolver for Iranian domains only. Fastest, never poisons.
- **Anti-DPI hardening that adds no hop** — TCP NoDelay, keep-alive, uTLS chrome
  fingerprint, mux for ws/grpc/h2.
- **Tuned for Iran** — handshake 10s, connIdle 600s, 512 KiB buffer, resilient
  watchdog that rides out Iran's disruptions.
- **Server names travel with the config** — "Server 1 … 99999" baked into the
  link's `#remark` / vmess `ps` field, so names stay consistent across any
  v2ray client.

## Supported protocols

Only **vless** and **vmess** (project policy) — including reality, xtls-vision,
ws, grpc, h2, kcp, xhttp transports.

## Build

Single signed universal APK (all ABIs): `ProfessorVPN-v5.2-universal.apk`.
