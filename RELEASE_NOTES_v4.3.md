# Professor VPN v4.3 — Ping System Repair

v4.2 shipped a **broken ping system**: no config would ever ping — not even
known-good ones. This release fixes the root causes and makes Auto-Test behave
identically to a manual ping.

## The bug: why nothing pinged in v4.2

Three compounding problems each, on their own, returned "unreachable":

1. **Nested-timeout starvation (the killer).**
   `Pinger.ping` is already hard-bounded internally (a multi-second budget +
   confirmation). But BOTH callers —
   `PingService.probeWithRetry` and `AutoTestEngine.probeWithRetry` — wrapped the
   whole call in an OUTER `withTimeoutOrNull(2500 ms)`. The 2 500 ms outer timer
   always fired before the inner work finished, cancelling every probe → every
   config reported UNREACHABLE.

2. **Incompatible probe endpoints.**
   `Libv2ray.measureOutboundDelay` expects a tiny, fast `generate_204` reply.
   v4.2 pointed it at `telegram.org/robots.txt`, `instagram.com/favicon.ico` and
   `1.1.1.1/cdn-cgi/trace` — heavier / redirecting / sometimes-blocked responses
   the native measurer treats as failures. So even a working proxy returned -1.

3. **Over-strict 2-stage confirm.**
   Requiring a SECOND success on a DIFFERENT endpoint meant one slow/blocked
   endpoint failed an otherwise-healthy node.

## The fix

- **One bounded probe path.** `Pinger.ping` does its own single hard timeout and
  returns fast. Callers no longer re-wrap it in a shorter timeout (they call it
  directly and retry once on a miss).
- **Fast, proxy-friendly endpoints.** Probe `cp.cloudflare.com/generate_204`,
  `gstatic.com/generate_204`, `google.com/generate_204`,
  `connectivitycheck.gstatic.com/generate_204` — tiny empty-body 204s served from
  CDNs reachable through any working proxy. The FIRST genuine proxied round-trip
  wins; a single down endpoint can't sink a healthy node.
- **Accurate & real.** The probe still travels THROUGH the Xray outbound (same
  outbound + stream settings as the live connect path), so a green ping genuinely
  means the tunnel carries traffic — no fake pings.
- **Stable on every network.** Wi-Fi, mobile data, any ISP — the measurement is
  of the tunnel, not the local link.
- The live-tunnel watchdog health check (`XrayManager.measureDelay`) now also uses
  the rock-solid gstatic 204, so it never produces a false "dead" reading on a
  perfectly good tunnel.

## Auto-Test == manual ping (no difference)

- Auto-Test uses the **identical** engine, timeout and accept/reject threshold as
  a manual ping (`WORKING_MAX_MS` raised to match `Pinger`'s own ceiling). What a
  manual ping accepts, Auto-Test accepts.
- Auto-Test simply automates what you'd do by hand: search → ping each → keep the
  working ones / drop the dead ones — and it adds each working config to My
  Configs the INSTANT it pings (FLUSH_EVERY = 1), one by one, whether there's 1
  config or 120.

## Build

- `versionCode 24`, `versionName 4.3`. Universal signed APK
  (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`, Android 7.0+), mirrored into
  `build/` (the old v4.2 artifact removed).
