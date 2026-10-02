# Professor VPN v10.7

## Fail fast on dead rows, stay generous with live ones — and finish with an answer

v10.6 made every row end with a verdict and a reason. This release fixes what
that verdict cost on a real feed: a batch of public configs is mostly dead, and
each dead row used to cost two full lane budgets before it was painted red — so
a search ran for tens of minutes and showed nothing, the "no config is ever
healthy" report. v10.7 spends the retry budget only where a live config can
still hide.

### The ping that finds a working config sooner

- **A dead row ends on its first definitive verdict.** Refused / DNS failure /
  TLS reset / below the throughput floor is final after ONE attempt — the
  second attempt used to replay the whole lane and reproduce the same verdict.
  On a dead-heavy batch that replay was the dominant cost of the sweep.
- **A live-shaped miss keeps its second chance.** NO_EGRESS / AUTH_REJECTED /
  BAD_RESPONSE / interrupted / pool-pressure still retry exactly as before, so
  a config that hiccuped once is never written off.
- **The TCP gate answers fast.** The sweep's dial races a 1.2 s window first:
  a healthy row answers inside it and the lane keeps its whole budget for the
  real ladder, while a dead row falls through to one full classified dial so
  its reason stays honest (refused / DNS / no answer). A slow-but-alive server
  is still dialled in full downstream — the shortcut only ever costs time,
  never a verdict.
- **The confirmation trusts the answer it just got.** A node that answered end
  to end seconds ago and then reproduces an artefact-shaped miss on its
  re-check keeps its first real answer instead of paying a third full ladder.
  Only a definitive confirmation failure (TLS reset, throughput floor,
  refused, DNS) still rejects the row.

### Ordered, deduplicated, no repeats

- **The sweep walks Server 1 → N in list order.** Pickup is one monotonic
  cursor; the mid-sweep re-rank is retired, so the plan is walked 0..N-1
  exactly as the list shows it. Workers finish out of order (painting only) —
  pickup never jumps.
- **No number and no config repeats.** One persistent monotonic counter issues
  each Server N once; every row carries its canonical content hash from parse
  time, and the batch is deduplicated per id AND per address:port. The same
  server under many UUIDs is probed once and its twin's verdict is mirrored
  onto the copies.
- **When the batch ends, the finished list is replaced by the next one.**
  A walked batch rotates (proven greens are kept and are already in My
  Configs); a cycle still finishing carried rows merges instead, so nothing is
  taken off screen while it is tested. Carried and leftover rows are never
  probed twice and no verdict is painted twice.

### The verdict cache (no duplicate dials)

- A verdict is replayed only when config + network + transport plan all still
  match — same server, same link, same settings. A stale verdict from another
  network or other settings is never replayed; the row is probed live.
- Sweep rows always measure live (force-fresh). The cache serves single taps
  and re-pings, and it is cleared on batch rotation.

### Automatic network settings, honestly fast

- **START SEARCH still measures first:** link class (Wi-Fi vs SIM/mobile),
  weakness, RTT, loss, DNS race, ports, DPI — then resolves mode, plan and
  test tuning from those measurements. Manual picks still win over automatic.
- **The analysis budget is shorter (32 s → 22 s)** so the first ping starts
  sooner; the sweep widths move up one step (Wi-Fi 6→7, cellular 5→6,
  high-latency cellular 3→4, unknown 4→5) because each row now costs less.
  Weak links stay narrow on purpose.
- **An interrupted probe says so:** "not checked yet — tap PING to retry"
  instead of a verdict-shaped label. A row with no verdict is untested, never
  red.

### Also

- Fixed a duplicate STREAM hint branch in ConnectionMode (second branch was
  unreachable).
- Cycle budget 24 → 30 min so a weak-link batch fits inside one cycle.

versionCode 88 / versionName 10.7. Same signing key as v6.7–v10.6, so it
installs over the existing app.
