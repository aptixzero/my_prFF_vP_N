# Professor VPN v11.0

## The strongest version: live numbers, full batches, real verdicts

v10.9 made the sweep own its list. v11 fixes what the user SEES while it
runs: a connect screen stuck at zero although the tunnel is up, batches of
4 rows that never fill, and a dashboard that cannot tell "in the app" from
"connected".

### Connect screen shows the tunnel, not the label

- `renderStats` gated on the BUS state, which a reconcile (service says
  CONNECTED, tunnel already down) rewrites to DISCONNECTED while the pump
  keeps broadcasting: every tick hit `if (!connected) return` and the
  screen sat at zero. The gate now reads the live `isTunnelUp` flag — the
  numbers show whenever the tunnel is up.
- The stats snapshot no longer wipes on state transitions: a wipe raced the
  pump's 1 Hz broadcasts and re-rendered zeros between the wipe and the
  next tick ("stuck at zero although connected"). The snapshot survives and
  is only replaced by the next real broadcast; a real stop still ends the
  numbers because the pump stops with it.

### Batches fill before they walk (never below 50)

- The bank walk was sequential (one bank per call): a cycle collected only
  what ONE bank yielded before its budget or switch cap fired — the "4
  rows then stuck" report. Each kind now walks up to 4 banks (shared
  seen-set, so each pass continues where the previous left off), and both
  kinds draw in PARALLEL.
- A draw below the 50-row floor is topped from the warm corpus before the
  cycle starts: the sweep never slow-walks a thin batch while fill exists
  elsewhere. Fresh bank rows stay; the corpus only fills the gap.
- The list still rotates batch-by-batch with greens kept; no row is
  skipped, ignored, or probed twice; STOP stays final and clears the carry.

### In the app vs connected — split on the device that knows

- The heartbeat now carries `vpn: 1/0`, read live from `isTunnelUp` at beat
  time (never cached across beats). Presence (any heartbeat = in the app)
  and tunnel (vpn=1 = connected) split on the phone, not in the panel.
- Dashboard: total installs, in-app live, VPN-connected live, away. Zero
  until v11 devices report (older versions do not report) — zero means "no
  v11 device seen yet", said on screen.

### Privacy (unchanged, structural)

No IP, MAC, serial, location, account, host, domain, or app — anywhere.
Session reports carry only counters already shown on screen. Per-site
history cannot be reconstructed from what is sent.

versionCode 91 / versionName 11.0. Same signing key as v6.7–v10.9, so it
installs over the existing app.
