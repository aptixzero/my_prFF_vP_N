# Professor VPN v10.8

## Stable on second use: re-sync, forget, and stay fast

v10.7 made dead rows settle fast. This release fixes what happened AFTER the
first use: an hour in — or one SIM → Wi-Fi swap in — even healthy configs
pinged red, and the app felt frozen on yesterday's answers. Three mechanisms
caused it; all three are fixed.

### 1. Every ping measures against the CURRENT link (NetResync)

- **New `NetResync.ensureCurrent` gate**: every ping path (single tap, PING
  ALL, every sweep cycle, connect revive) re-syncs the network analysis
  BEFORE it measures. Same link + fresh profile = one prefs read, no
  traffic. Changed link or a profile older than 30 minutes = background
  re-measure behind a 12 s ceiling; a clipped sync keeps the stored profile
  (the ping then behaves exactly as before).
- **The revive re-measures first**: after a network change the tunnel used to
  rebuild its plan from the OLD link's profile (the watcher skips measuring
  while the tunnel is up). It now forces a fresh profile before it rebuilds,
  so the new link's DNS/ports/mode apply at once.
- **Every sweep cycle re-syncs**: a long search no longer walks batch after
  batch against the first profile of the run.
- **The verdict cache is cleared on resync**: a verdict measured on the old
  link never replays on the new link.

### 2. A rotated list forgets what it dropped

- `rotateTo` now prunes the dropped rows' statuses, reasons, and persisted
  verdicts (new `ConfigStateDao.deleteExcept`): only proven greens keep
  their verdicts. The list can no longer show yesterday's answers, and the
  corpus prune finally sees the dropped rows go.
- Housekeeping (`PingCacheHandler.maybeCleanup`) gained a `force` flag; the
  memory guard calls it forced under pressure and also clears the verdict
  cache first.

### 3. Boot analyses the link while the splash is up

- The splash runs `NetResync.ensureCurrent` on IO behind the same 12 s
  ceiling after the core loads: first launch measures the real link instead
  of trusting the store (possibly yesterday's link, possibly none). A
  clipped scan keeps the stored profile — boot behaves as before, with one
  extra log line.

### Per-device registry (dashboard counts devices, not hits)

- New `DeviceRegistry`: stable random UUID per install, panel sees only
  `device_hash` = SHA-256(UUID) truncated (one-way). Register once a day +
  heartbeat every 2 min + one session report (counters only: seconds, up /
  down bytes) at teardown. HTTPS, short timeouts, best-effort — never
  affects the VPN, sweep, or UI.
- The registry URL rides the published config (`registryUrl`, HTTPS only,
  empty = disabled) so the shipped binary carries no panel identity
  (enforced by the `checkIdentityLeak` gate).
- **Privacy (hard rules)**: no IP, MAC, Android ID, serial, location,
  account — and no hosts, domains, apps, or traffic content. Session
  reports carry only the byte counters already shown on screen. Per-site or
  per-app history cannot be reconstructed from what is sent.

### Hardening

- `allowBackup=false` (no adb/cloud backup of configs and tokens),
  `usesCleartextTraffic=false` (all feeds already HTTPS; only loopback
  probes stay plain).
- Aggressive R8 obfuscation stays on; the persisted-enum keeps stay intact.

### Panel (prf-vpn-admin)

- Per-device registry API: public `register` / `heartbeat` / `session`
  (never reads or stores the client IP), authed `list` (search, online
  filter, paging) and `stats`.
- Dashboard counts devices: total = rows, online = seen inside 3 min (one
  row, one vote), offline = total − online. Legacy counters show only when
  the registry is still empty.
- New Users page (status dot: grey = offline, green = connected; device,
  version, sessions, connected time, data, last seen) and Tracking page
  (device search + aggregate session detail — counters only, never sites).
- Storage: git file fast path (`adminpanel/registry/devices.json`) +
  private Hugging Face dataset mirror (sharded; `HF_REGISTRY_REPO` /
  `HF_REGISTRY_SHARDS` / `HF_REGISTRY_TOKEN` env). Either side rebuilds
  the other; a failed mirror write never fails the device request.
- Credentials rotated via env (`PANEL_USER`, scrypt `PANEL_PASS_HASH`).
  No credential text in code, labels, or hints.

versionCode 89 / versionName 10.8. Same signing key as v6.7–v10.7, so it
installs over the existing app.
