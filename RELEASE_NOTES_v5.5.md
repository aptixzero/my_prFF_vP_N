# Professor VPN — v5.5

## The one that actually connects again

v4.6 → v5.4 all shared the same fatal bug: the app would turn **green / "Connected"**
but **no traffic ever flowed** — it wasn't really using the selected config and it
wasn't tunneling the device. Every attempt to patch it inside that line failed
because each version kept removing the very pieces that make a tunnel work on a
filtered network.

v5.5 goes back to the **last known-good method** (verified against an older working
build) and rebuilds the core connection path on top of it.

## What was actually broken

- **v5.3** hid the anti-DPI TLS-ClientHello fragmentation behind a `dialerProxy`
  chain. The per-config ping (`measureOutboundDelay`) does **not** walk that chain,
  so ping measured a different, shorter path than the live tunnel — "pings 100ms,
  connects at 700ms" — and on many cores the chain simply broke the live path so
  **zero bytes** flowed.
- **v5.4** then removed the DNS block **and** the fragmentation entirely. The
  result: the TCP/TLS handshake completes (so the health check is green and the UI
  says "Connected"), but DPI RST-injects the un-fragmented ClientHello, so the
  tunnel carries **no real traffic** — exactly the "connected but nothing works /
  doesn't use the config" report, on **every** config.
- **v5.2's** extra "real-bytes" verification gate was over-aggressive and
  **false-rejected working servers** on cores that don't surface per-outbound
  counters.

## What v5.5 fixes

- **Core engine config restored (`XrayConfigBuilder`)**
  - Real `dns` block again: encrypted DoH (anti-poisoning) for the free internet +
    a domestic resolver for Iranian domains, `queryStrategy: UseIP`, dedicated
    `dns-out` outbound and DNS→dns-out routing. Name resolution no longer wedges.
  - **TLS-ClientHello fragmentation applied DIRECTLY on the proxy outbound's own
    `sockopt.fragment`** (`packets: tlshello`) — **not** via a dialerProxy chain.
    DPI never sees the SNI in one packet, so the connection survives and **real
    traffic flows through the selected config**.
  - Full-tunnel routing: everything except Iranian domains / Iran IPs / private LAN
    goes through the proxy at full bandwidth. It's a fast filtershkan, not a proxy.
  - uTLS `chrome` fingerprint + TCP keep-alive for a persistent, full-speed tunnel.

- **Connect flow rebuilt (`NeonVpnService`)**
  - Health-check through the LIVE core **before** reporting Connected, with a
    lenient window (1–15000ms) and a couple of retries so a cold-but-good tunnel is
    never falsely rejected.
  - Removed the over-aggressive v5.2 "real-bytes / native-gate" verification that
    was rejecting good servers.
  - `running` is now flipped **true before** the tun2socks thread starts, closing a
    race where `TProxyStartService` could be skipped (another "connected, no
    traffic" cause).

- **`XrayManager`** — simple, lenient `measureDelay` restored (gstatic 204 primary,
  small fallback set), so ping and connect agree and a healthy tunnel is never
  declared dead.

## New: sequential config names

When you copy / paste configs, they are now saved as **`Server 1`, `Server 2`, …**
in the order the list grows (a second paste keeps counting up). The original remark
baked into the link is no longer used as the display name.

## Notes

- No proxy mode — this is a full-device VPN tunnel (filtershkan).
- Only vless / vmess are supported (project policy).
