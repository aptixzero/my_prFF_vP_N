# v12.9 — connect fallback, instant disconnect/reconnect, stable My Configs order

**versionCode 109 · compiled, unit-tested and signed on CI (`.github/workflows/release.yml`)**

Five targeted changes, each tied to a specific mechanism found by reading the
code. Nothing here has been run on a phone — see "What is NOT verified".

## 1. "Server not responding" on a config that showed a ping

The ping path never enables mux (`XrayConfigBuilder.buildPingConfig` strips it
on purpose). The connect path can enable it from the strategy table. A server
that does not speak mux.cool therefore pinged green, the core accepted the
config and started, and then no traffic crossed. The tier ladder in
`startWithFallback` only reacts to the core rejecting a config, so nothing
caught this and the user saw the error.

**Fix:** when the connect gate fails with mux on, the service restarts the core
once with exactly the config the ping validated (mux off), re-bridges tun2socks
and runs the same gate again. Mux-off cannot break a config that worked with
mux on. If it still fails, the existing auto-failover and error path run as
before.

## 2. Fast disconnect → connect leaves the button stuck

Every session step runs on one single-thread executor. A STOP or a new CONNECT
bumped the generation but could not interrupt the previous session's gate probe,
which blocks on the network for up to ~8 s, so the new connect sat in the queue
behind a session nobody wanted.

**Fix:** the in-flight probe connection is tracked and disconnected the moment a
stop or a newer connect arrives, so the session thread frees immediately.

## 3. Lists hanging while pings land

`ConfigStore.reorderSuspend` wrote the new order one row per statement. Each
standalone write invalidates the table and the paged list re-queries, so
reordering a few hundred configs meant a few hundred back-to-back re-queries
while the sweep was also repainting the rows.

**Fix:** the whole reorder is one Room transaction, so it invalidates once.

## 4. My Configs ordering

A selected config that measured dead was sunk to the bottom, so the row the user
was using disappeared from view. Now the selected config stays at the top, healthy
rows pin right below it and dead rows still sink — both in the live pin/sink path
and in the end-of-sweep sort.

## 5. Nothing pings on Wi-Fi (mitigation, root cause not found)

No Wi-Fi-specific rule exists anywhere in the probe code, so the cause was not
found by reading. What the code does show: the sweep lane asks exactly one URL.
If that one URL's path is what fails on a given link, every row returns NO_EGRESS.

**Mitigation:** after 12 consecutive sweep misses with no green row, the lane also
tries up to two alternate URLs, and whichever answers becomes the lead URL for
later rows. A healthy link never pays for this (counter stays at 0). A mostly-dead
list pays it for roughly one row in seven, not every row.

## What is NOT verified

- Nothing has run on a device or a real network. CI compiles and runs the JVM unit
  tests only.
- Item 5 is a mitigation for a symptom, not a found cause. If Wi-Fi still shows no
  pings, a log from that Wi-Fi (logcat lines tagged with the probe/ping classes) is
  what identifies the real cause.
- Item 1 fixes one concrete cause of "pinged but won't connect". If a config still
  fails with mux off, that is a different cause (TUN/tun2socks path, server-side) and
  needs the connect-gate log lines from the device.

## Not added: per-site / per-app logging

Recording which sites and apps each user reaches was requested again and is still
not implemented. This project's registry is built so the panel never receives
hosts, domains, apps or traffic content, and this app's users rely on it to avoid
surveillance. Reversing that is a product decision for the owner, not something
to ship inside a bug-fix release.

## Build

`versionCode 109`, `versionName "12.9"`, same release key as v6.7–v12.8
(certificate SHA-256 `6a5ed5e32014ee77b41ca9ef9c71c5ab3397156d25fe22c7f1d52bb8907eb82d`).
CI refuses to publish if the certificate differs.
