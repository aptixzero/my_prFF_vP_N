# v12.7 — the sweep finds working configs again, and the list shows it live

**versionCode 107 · built from the correct source tree (professor-vpn-source-private @ v12.6 baseline)**

> v12.5 was assembled from a foreign tree and never matched this codebase; it is
> retracted. v12.6 restored the real source. This release fixes the three field
> regressions reported on v12.6.

## 1. Configs ping again (the reported "a whole list, not one ping")

- The sweep's per-row cost now follows the MEASURED link: the same lane wall
  and per-probe budget the manual PING ALL uses (`TestTuning` from
  `NetProfiler`/`NetworkScanner`), so a lossy cellular link is no longer judged
  by a fixed wall while the user's own manual taps get a link-aware one. The
  pool's fast admission lane still bounds concurrency (see below).
- Each row gets up to TWO real ladder attempts: a second attempt runs only when
  it can change the verdict (`SweepPolicy.shouldRetry` — an artefact-shaped
  miss, never a definitive refused / DNS / TLS-reset). A single dropped packet
  on a lossy filtered link no longer writes a live config off inside the same
  pass; a dead row still costs exactly one attempt.
- A fast gate dial (1.2 s window) runs BEFORE the ladder: a healthy row hands
  its handshake number forward, a dead row learns its honest cause (refused /
  DNS / timeout) once — instead of every dead row paying a full classified
  dial AFTER a failed core probe. The ladder still goes straight to the core;
  a row the gate missed is still measured by the ladder itself.
- The sweep re-enters the probe pool through the SHORT bounded lane (at most
  3 s for a permit) instead of bypassing admission entirely: the pool's
  protective caps (live-tunnel width, AIMD back-off, memory-pressure latch)
  apply again, so concurrent native cores can no longer stall each other into
  a whole red batch. A row that cannot be admitted in 3 s stays honestly
  INCONCLUSIVE (re-probed next pass), never painted dead.
- The bank walk attributes every drawn row to the bank URL it came from, so a
  config that proves working credits its bank (`probeSuccesses`, the ranker's
  dominant term). The map was previously fed only by the corpus paths, so a
  full sweep of fresh bank rows credited nothing and every search opened on
  the same curated order. The walk also leads with the connection-test page's
  measured race winner before the accumulated scores — the "it always picks
  the same source" report, fixed without any randomness: the same health plus
  the same measured winner always walks the same order.

## 2. Live separation (the reported "dead rows bury the working ones")

- While the sweep walks, a row that pings **pins to the top** the moment its
  verdict lands and a row that fails **sinks to the bottom** — each as a single
  row move, persisted so the store keeps the position and a reload cannot put
  a dead row back where it started. The mid-sweep order otherwise stays stable
  (no re-sort under the user's finger); the engine-progress reload path no
  longer undoes the live moves.
- Both moves were fixed to scan a snapshot while mutating by key lookup: the
  in-place scan skipped the row after every move, so consecutive verdicts
  could strand rows in the middle of the list.
- A dead row reads `no ping · <cause>` (`no answer`, `handshake reset`,
  `too slow`, `not pinged yet`), from the one shared label function — the
  class keeps the honest cause, the row stays short enough to fit.

## 3. Stable and standard (the reported "not stable")

- The retired TCP triage wave, the endpoint re-rank queue and the sustained
  confirmation path are deleted (about 700 lines): they were dead code on the
  live path (the sweep walks the list in display order and measures FIRST
  ANSWER only), and their leftovers were the only remaining callers of the
  removed budgets. What remains is one sweep path, one verdict path, one wall
  policy.
- STOP is unchanged and immediate (interruptible native probes, instant
  notification removal — v12.6). Launch is unchanged and fast (background
  network analysis — v12.6). The connect gate is untouched: a green row still
  passes the same ladder the connect path uses, so a ping still means the row
  connects.
- 208 JVM unit tests green (including the v12.6 label contract in
  `LinkAndReasonTest`, extended with the `no ping ·` sentence).
- Panel sync unchanged: `latestApkVersion 12.7` in `adminpanel/app_config.json`,
  device registry + presence heartbeat still served from the published config
  (`registryUrl`), nothing new collected.

## 4. Build

- One signed **universal** APK (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`),
  Android 7.0+ (`minSdk 24`), `zipalign -c 4` verified.
- Same release key as v6.7–v12.6
  (certificate SHA-256 `6a5ed5e32014ee77b41ca9ef9c71c5ab3397156d25fe22c7f1d52bb8907eb82d`) —
  installs directly over any previous version.
