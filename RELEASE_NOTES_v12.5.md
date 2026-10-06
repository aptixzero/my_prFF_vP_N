# Professor VPN v12.5

## The auto-tuned engine release

versionCode 106 / versionName 12.5. Same signing key as v6.7–v12.4 — installs
straight over the previous version.

### 1. Network settings are now fully automatic — measured, never random

- Every launch (and every network change) scans the device's real link:
  transport (Wi-Fi / cellular), carrier family, generation (5G / LTE), the
  OS bandwidth estimate, a median of three real round-trips, jitter across
  them, and DNS-poisoning pressure on the link.
- From those measurements a wire plan is DERIVED through a fixed decision
  ladder — connection mode, handshake-splitting depth, client family,
  multiplexing, packet size, keep-alive, test-worker count, probe windows.
  The same link always produces the same plan. There is no randomness
  anywhere in the app's logic.
- The plan is cached per network, so walking back onto a known link reuses
  its measured plan instantly, and a genuinely changed link is re-measured.
- The settings page shows the measured result and the chosen plan in generic
  language only — no address, no hostname, no endpoint, no infrastructure
  identity appears anywhere in the settings.

### 2. The connection-test page (Start Search)

- START SEARCH now runs as a foreground service. The notification shows the
  live progress and carries a STOP action for the whole run — and the in-app
  button IS the STOP button while the sweep runs. Both are fed by the same
  phase, so the "Stop disappeared while the search notification stayed"
  bug is structurally impossible.
- The sweep takes the next 120-config batch from the config bank (60 VLESS +
  60 VMESS), walks it strictly in list order from row 1, and splits it LIVE:
  the moment a row answers green it is pinned to the top; the moment a row is
  proven dead it sinks to the bottom with the real reason in English
  ("no TCP answer", "connection refused", "dns failure", "handshake failed",
  "proxy timed out", "no egress").
- When the batch is finished, greens are kept, the rest are dropped, the next
  batch (121–240, then 241–360, …) is fetched, and the walk continues from
  the new row 1. The sweep NEVER stops on its own; only STOP ends it. A lost
  link pauses it and the link's return resumes it.
- A config that pings green is promoted to My Configs immediately, so the
  config the list calls green is connectable on Home — a ping is not a
  decoration, it is a connectable tunnel.

### 3. Real pings only

- Every verdict is a REAL probe in three stages: a fast TCP gate of the
  endpoint (a node that won't TCP-connect is definitively dead, with the
  real reason), then a genuine proxied request through a fresh Xray core
  instance to a public generate_204 target, and on a miss the NEXT target in
  the ladder is tried before giving up (one blocked check target can never
  paint a live node dead).
- The pacing between probes is measured, not fixed: the engine tracks how
  long the target actually took to answer and waits a bounded multiple of
  that before knocking again. No hardcoded sleeps, no cooldown constants —
  a fast responder is re-asked quickly, a slow one gets room.

### 4. Config bank

- The app reads its own project bridge first (release assets, then the
  project Pages host — both independent of raw.githubusercontent.com), and
  the upstream feeds directly as the last resort. Each source's success and
  latency are remembered on-device, and the bank walk reads the healthiest
  source first — measured on THIS device's link, never shuffled.
- The corpus is deterministically ordered and the batch cursor advances
  across runs, so repeated searches never repeat rows inside one cycle.

### 5. Stability & privacy

- The tunnel config is built from the measured plan: deeper handshake
  splitting under heavy interference, tighter keep-alive on noisy links,
  conservative MTU on cellular, multiplexing only where it helps.
- allowBackup=false (unchanged), no identifiers added: presence reporting
  still sends a random per-install UUID + app version + model + running
  state only.
- The brand glitch animation is deterministic now; no random call remains
  in the app's logic paths.
