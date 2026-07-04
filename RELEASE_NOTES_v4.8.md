# Professor VPN v4.8 — Stability, Speed & Real Ping

This update targets the connection-stability, speed, and ping-accuracy problems
reported for v4.7, plus overnight crash-resistance and a smarter clipboard paste.

## Connection: instant-use, stable, no more "connects then drops after 10s"

- **Instant traffic flow.** The connect sequence now starts `tun2socks` (the
  TUN <-> SOCKS bridge) *immediately* after the core is up, instead of waiting for a
  slow multi-round-trip health check first. Device traffic flows the moment you're
  connected — no more "says connected but only starts working 10-20s later".
- **Trimmed warm-up delay.** The fixed pre-tunnel sleep was cut from 450 ms to
  120 ms (the SOCKS inbound binds almost instantly).
- **Fast connect verification.** A single real round-trip to a censored endpoint
  (Cloudflare / Telegram / Instagram — never Google) confirms the tunnel genuinely
  carries blocked traffic, with a short 8 s window and quick retries. A good node is
  confirmed in ~1-3 s; a truly dead one fails fast.
- **Longer watchdog grace period (20 s).** A freshly-connected Reality/XTLS tunnel
  is given a full 20 s to stabilise before the first health probe, so early jitter
  can no longer trigger a needless core re-spin that briefly black-holed traffic —
  the direct cause of the "drops/stalls ~10-20 s after connect" report.
- **Tolerant watchdog.** The tunnel is only declared unhealthy after **three**
  consecutive fast-probe misses (with a short pause between each). A tunnel that
  answers even one of three probes is left completely untouched.

## Ping: real, and finally stable

- **Reproducible ping.** Fixes "this config pings 90 ms now, then no ping 2 minutes
  later". The pinger now locks onto ONE reference censored endpoint and reports the
  **median of 3 quick round-trips** to that same target — a jitter-resistant number
  instead of one lucky/unlucky sample against a random endpoint.
- **Robust live ping.** The in-connection ping (`measureDelay`) now falls back
  through the whole censored-edge set if the primary endpoint is momentarily
  throttled, so the displayed ping no longer flickers to a dash on a healthy tunnel.
- **Smoothed display.** The live ping shown while connected is an exponential moving
  average of real measured round-trips, so the number is steady instead of jumping
  every refresh. A transient miss never wipes the last good value.

## Overnight stability (crash handler)

- **Memory-pressure shedding.** The app now responds to OS `onTrimMemory` /
  `onLowMemory` signals by pruning cached ping state and hinting a GC — so a long
  screen-off auto-test session through the night no longer fills the cache and gets
  OOM-killed. Combined with the existing OOM first-aid in the crash handler, the
  process stays alive and the tunnel keeps running.
- Auto-Test already adds every config that pings to **My Configs** and drops the
  ones that don't — unchanged and confirmed working.

## Smarter "Paste From Clipboard"

- **Scans the full paste history.** Android does not expose the OS clipboard
  *history* to apps (privacy), so the app keeps its own on-device history of every
  vless/vmess link it has ever pasted. This is now **on by default**, the capacity
  is raised to **500 entries** (60-day retention), and each paste merges the entire
  history with the current clip — so if you copied 100 configs over time, every
  vless/vmess among them is detected and added. Only vless/vmess are ever stored.

## Admin panel

- Verified end-to-end: the panel publishes `adminpanel/app_config.json` to
  `aptixzero/PRF_VPN`, and the app reads it from the matching raw URL. No change
  needed — the link is live.

## Build

- Version bumped to **4.8** (`versionCode` 29). The CI now also commits the freshly
  built signed APK into `build/`, replacing the previous artifact.

_Language-independent: the connection path contains no locale branching, so behaviour
is identical in Persian and English._
