# build/

This folder holds the **latest signed universal APK** for Professor VPN.
Only the newest `ProfessorVPN-v<version>-universal.apk` is kept; older APKs
are removed on each rebuild.

## Current artifact

- **`ProfessorVPN-v4.3-universal.apk`** — version 4.3 (versionCode 24)
- Universal: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` (Android 7.0+).
- Signed with the release key (`CN=NeonVPN`); v1+v2+v3 signature schemes.
- v4.3 fixes (PING SYSTEM REPAIR):
  - **Ping works again.** v4.2 was broken — *no* config (not even known-good
    ones) would ever ping. Three compounding bugs were fixed:
    1. **Nested-timeout starvation** — both the manual ping (`PingService`) and
       the Auto-Test engine wrapped the already-bounded `Pinger.ping` in a
       shorter outer `withTimeoutOrNull(2500 ms)` that always fired before the
       inner work could finish, so every probe was cancelled → everything
       reported unreachable. The outer timeout is removed; `Pinger.ping` is the
       single hard-bounded path.
    2. **Incompatible probe endpoints** — v4.2 pointed the native delay
       measurer at `telegram.org/robots.txt` & `instagram.com/favicon.ico`
       (heavy/redirecting/blocked responses it treats as failures). v4.3 probes
       fast, proxy-friendly `generate_204` endpoints (Cloudflare + the
       rock-solid gstatic/google connectivity-check infra) — the same targets
       v2rayNG/v2box trust. The FIRST genuine proxied round-trip wins.
    3. **Over-strict 2-stage confirm** — requiring a second success on a
       different endpoint rejected good nodes; removed.
  - **Stable on every network** — Wi-Fi, mobile data, any ISP: the probe travels
    through the Xray outbound, so it reflects the real tunnel, not the local link.
  - **Auto-Test == manual ping** — Auto-Test now uses the *identical* engine,
    threshold and accept/reject logic as a manual ping. It automatically does
    what you'd do by hand (search → ping → keep working / drop dead) and adds
    each working config to My Configs the INSTANT it pings.
- Free configs are fetched from the `aptixzero/con_new` feed.

Install on a device/emulator with:

```bash
adb install -r ProfessorVPN-v4.3-universal.apk
```
