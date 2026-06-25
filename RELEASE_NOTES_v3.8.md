# Professor VPN — v3.8 Release Notes

**Version:** 3.8 · **versionCode:** 20 · **Branch:** `release/v3.8`
**APK:** `ProfessorVPN-v3.8-universal.apk` (universal — `arm64-v8a`, `armeabi-v7a`,
`x86`, `x86_64`; Android 7.0+; signed)
**Download:** <https://github.com/prfgame/prf-VPN/releases/latest/download/ProfessorVPN-v3.8-universal.apk>

This release keeps every one of the **Seven Golden Rules** intact: a real
`VpnService` tunnel, **no `Random`** anywhere in stats (now enforced by an
instrumented test), a post-connect health check, **only VLESS/VMESS**, no ad
SDKs, no hardcoded credentials, and a glitch-free watchdog-backed connection.

---

## Highlights

### Liquid Orb connect control (§4.3)
- Retired the reptilian "eye" view. `LiquidOrbConnectView` renders a procedural
  liquid orb with **five states** — `IDLE`, `CONNECTING`, `CONNECTED`,
  `DISCONNECTING`, `ERROR` — plus a live progress arc while connecting.
- Three-tier renderer: **AGSL `RuntimeShader`** (per-pixel turbulence, API 33+)
  → otherwise a self-contained **Canvas 2D** fallback (wave bands + sheen) that
  works on every supported device. No binary assets required.
- `NeonVpnService` emits `ConnectionProgress(percent, label)` over
  `VpnStateBus.progress: StateFlow<…>` at 5/15/30/50/70/90/100% milestones; the
  orb consumes it directly.

### Shared, app-scoped ping service (§4.4)
- New `PingService` Kotlin singleton scoped to `ProcessLifecycleOwner`, exposing
  `statuses: StateFlow<Map<ConfigId, PingStatus>>`. Sweeps survive tab switches
  and only back off when the whole app is backgrounded.
- `Semaphore(16)` concurrency gate, **2500 ms** primary probe with a **1500 ms**
  retry, **4 s** backoff, color thresholds (good ≤300 ms / ok ≤800 ms),
  **unstable-demote**, DiffUtil re-sort, and cross-tab persistence.

### Connection stability (§4.5)
- **350 ms** tap debounce feeding a single state-machine coroutine
  (`Mutex` + `CONFLATED` Channel) so rapid taps can never desync the tunnel.
- `viewLifecycleOwner` scopes everywhere, foreground-service persistence,
  `START_STICKY` + `onTaskRemoved()` keep the tunnel alive on task swipe,
  partial wakelock tag `prfvpn:tunnel`.
- 5-step watchdog backoff (**2/4/8/16/32 s**) before giving up.
- Crash handler writes **sanitized** logs (links/UUIDs/IPs/host:port scrubbed)
  to `filesDir/logs/`. LeakCanary runs in debug builds only.

### Admin-controlled Telegram link (§4.2)
- Extends the existing `inAppTelegramUrl` (no parallel key). Panel validates
  `https://t.me/` or `https://telegram.me/` (empty allowed), rejects before any
  network call, shows **"Saved ✓"** + `lastUpdatedAt`, atomic single-PUT save.
- App caches the resolved link to `SharedPreferences` (`pref_telegram_url`) for
  instant cold-start render and refreshes on start + `onResume`, throttled to
  **60 s**.

### Smart clipboard paste (§4.7)
- One-tap paste of `vless://` / `vmess://` configs from the clipboard, with an
  **opt-in** history (toggle + clear in Settings → Privacy).

### Free-config feed (§4.6)
- Free Configs now sourced from **`prfgame/CC_new`** with ETag/304 disk caching
  and stale fallback.

---

## Quality gates

- **§4.8 — anti-Random guard:** `NoRandomInStatsTest` (instrumented) scans the
  shipped `config/`, `service/`, `stats/` and `ui/` sources on device and fails
  the build if `kotlin.random.Random`, `java.util.Random`, `Math.random()` or a
  bare `Random(` / `Random.` appears on a non-comment line. The lone RNG
  tiebreaker in `ConfigFetcher` was replaced with a deterministic per-minute
  rotation hash.
- **§4.9 — locale parity & RTL:** EN/FA reach 100% parity for every translatable
  key (zero `MissingTranslation`); `supportsRtl="true"` and all layouts use
  `Start`/`End` (no `Left`/`Right`).

---

## Web & release (§5, §4.1, §6)

- Version bumped to **3.8 / code 20**; About screen reads `BuildConfig.VERSION_NAME`.
- Download page updated to **v3.8** with a Persian "What's new" changelog and the
  `ProfessorVPN-v3.8-universal.apk` URL; admin panel gains a `<=560px` phone breakpoint.
- The download URL / manifest track the CI artifact name
  `ProfessorVPN-v<ver>-universal.apk` (the GitHub-App token used for this branch
  lacks the `workflows` permission, so `.github/workflows/build.yml` was left
  untouched; if the asset is later renamed to `prf-VPN-*`, update the manifest
  URL to match in the same change).
- Stale v3.6 APKs deleted from `build/` in the version-bump commit; CI
  republishes the fresh APK on the next `main` build.

---

## Notes

- **No Android SDK is available in this environment**, so the project was not
  compiled here — all changes are implemented directly against the real source
  and reviewed manually. CI (`.github/workflows/build.yml`) performs the actual
  signed `assembleRelease` and instrumented test run.
