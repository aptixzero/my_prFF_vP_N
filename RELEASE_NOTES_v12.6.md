# v12.6 — STOP that stops, pings that ping, a boot that does not wait

**versionCode 106 · built from the correct source tree (professor-vpn-source-private @ v12.4 baseline)**

> v12.5 was assembled from a foreign tree and never matched this codebase; it is
> retracted. This release restores the real source, then fixes the three field
> regressions reported on it.

## 1. STOP is immediate (the reported "I press Stop, it keeps pinging")

- The sweep's native probe wait was a blocking `Future.get` for up to the full
  probe budget (25–30 s). The engine job cancelled on STOP, but every worker
  stayed parked inside the JNI wait and the list kept "pinging" for half a
  minute. The wait is now **interruptible in 250 ms slices**
  (`runInterruptible` + sliced `Future.get` in `XrayManager.measureConfigDelay`,
  wired through `MeasurementEngine.coreProbe` / `sustainedProbe`): a STOP ends
  every worker's wait within one slice, the abandoned slots are reclaimed by
  the existing reaper, and the native threads finish in the background as
  daemons — bounded, exactly as before.
- The foreground notification now disappears **the instant** STOP is pressed
  (`AutoTestService.stopForegroundNow()`), not after the engine drains.
- `SweepController.stopAll` still cancels both ping buckets, the supervisor
  backstop and the service in one idempotent call.

## 2. Real pings, not a fast skim (the reported "it rejects everything in milliseconds")

- Root cause: the sweep, the manual row ping and the connect gate all call the
  native probe through the companion `measureConfigDelay`, which can run
  **before any `XrayManager.init()`** (a search started from the test page, a
  process restart). Without `initCoreEnv` every JNI probe failed instantly and
  every row was painted dead in milliseconds. The core environment is now
  **guaranteed from any entry point** (`ensureCoreEnv` inside
  `measureConfigDelay`): geo assets extracted, `initCoreEnv` called once per
  process, before the first probe of any path.
- **Instant-fail sanity gate** in the sweep: a rush of fast, non-definitive
  failures (`NO_EGRESS` / timeout / bad response — never refused / DNS / TLS
  reset, which stay honest verdicts) now triggers one deterministic raw-IP link
  probe (1.1.1.1:443, rate-limited, single-flight). If the LINK is down, the
  rows stay **untested** (Idle) and the engine's existing no-network pause owns
  the wait — the batch is re-probed for real when the link is back, instead of
  a whole 120-row list being written off in one sweep. One refused row is
  still just a refused row.
- The per-row verdict, the reason line and the ladder are unchanged: a number
  is still a real end-to-end round trip through a live core
  (`MeasurementEngine`, FIRST_ANSWER lane), never a TCP guess, never a cache
  replay for sweep rows.

## 3. Launch speed (the reported "loading and network setup take too long")

- The boot screen no longer **blocks** on the network analysis. The analysis
  (scan → measure → apply per-link settings) still runs automatically at
  launch — now on a background scope — and the boot screen ends as soon as the
  core is ready. `NetWatcher` (started in `NeonApp.onCreate`) keeps the profile
  fresh after launch and across link changes.
- Core init stays on the boot path but is bounded (8 s ceiling); a clipped boot
  is still fully functional because of `ensureCoreEnv`.
- Terminal animation quickened (14 ms/char) and the artificial pauses cut;
  the boot now lands on Home in seconds instead of tens of seconds.

## 4. Live separation, kept and hardened

- While the sweep walks, a row that pings **pins to the top** the moment its
  verdict lands and a row that fails **sinks to the bottom** — each as a single
  row move, persisted so the store keeps the position. Failed configs can no
  longer bury the working ones.

## 5. Honesty of the list

- Reason lines are short and fit one row: `no ping · <reason>`,
  `no answer`, `handshake reset`, `too slow`, `not pinged yet`.
- No URLs, endpoints, usernames or pointers are shown anywhere in Settings —
  the ping service and origin pages expose labels and states only.

## 6. Build

- One signed **universal** APK (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`),
  Android 7.0+ (`minSdk 24`), `zipalign -c 4` verified.
- Same release key as v6.7–v12.4
  (certificate SHA-256 `6a5ed5e32014ee77b41ca9ef9c71c5ab3397156d25fe22c7f1d52bb8907eb82d`) —
  installs directly over any previous version.
- 207 JVM unit tests green (including the v12.6 label contract in
  `LinkAndReasonTest`).
- Panel sync unchanged: `latestApkVersion 12.6` in `adminpanel/app_config.json`,
  device registry + presence heartbeat still served from the published config
  (`registryUrl`), nothing new collected.
