# Professor VPN v11.1

## The ping finally holds: screen-off keep-alive, no gaps, best bank first

v11 showed live numbers and full batches. v11.1 fixes what the sweep does
between verdicts: screen-off stalls, gaps between pings, and file-order
bank walks that ignore what the user's own link already proved.

### 1. Screen-off keeps sweeping

- New network heartbeat in the foreground service: one cheap socket to
  1.1.1.1:443 every 45 s. The partial wake lock kept the CPU, but with the
  screen off the OS stops callbacks and throttles the radio — dials timed
  out one by one, the AIMD cap folded to the floor, and the run looked
  "stuck at zero although it worked with the screen on". The heartbeat
  keeps the radio out of deep idle AND proves the link is still there.
- Three missed heartbeats = the link is really gone → the engine's
  no-network pause owns the wait (rows wait, never paint red). A single
  miss costs nothing.

### 2. No gaps between pings

- The first-answer lane takes a 3 s fast acquire instead of the 30 s
  patient one: a queued row is carried in seconds (its second chance),
  not stalled half a minute before its first dial. Confirmations and
  connect checks keep the patient lane — they may legitimately wait.
- Lossy links probe narrower but longer (Wi-Fi lossy 4-wide/14 s,
  cellular lossy 3-wide/14 s): width buys queueing on a dropping link,
  the longer wall lets the ladder survive one loss burst. The "six rows
  stall together, all clip together" pattern is gone.

### 3. The best bank walks first — measured on YOUR link

- The walk order is ranked by the source_health ledger (fetch
  reachability + ingest yield + probe successes via SourceRanker), not the
  collector's file order measured from outside Iran. The bank whose
  configs prove working HERE walks first; a bank with no evidence keeps
  the neutral prior (never buried); an exhausted bank is never re-fetched.
- Your 100 banks (50 VLESS + 50 VMESS from the October file, quality
  sorted) were already the app's banks — verified 100/100 URLs match. No
  import was needed; the ranking now uses them in YOUR link's order.

### 4. Link-aware settings, honestly measured

- SIM vs Wi-Fi comes from the OS transports + bandwidth band (never an
  operator name): link class → quality (RTT/loss/jitter) → weak verdict →
  IP family → DNS race → DPI probe → shaping/mode. Fragmentation requires
  the measured split-succeeds result; mobile defaults to PADDED; weak caps
  every aggressive lever. Manual picks still win.

versionCode 92 / versionName 11.1. Same signing key as v6.7–v11.0, so it
installs over the existing app.
