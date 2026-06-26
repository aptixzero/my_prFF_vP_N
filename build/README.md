# build/

This folder holds the **latest signed universal APK** for Professor VPN.
Only the newest `ProfessorVPN-v<version>-universal.apk` is kept; older APKs
are removed on each rebuild.

## Current artifact

- **`ProfessorVPN-v4.0-universal.apk`** — version 4.0 (versionCode 21)
- Universal: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`
- Signed with the release key (`CN=NeonVPN`); v1+v2+v3 signature schemes.
- Includes the crash-proof Auto Test engine (SupervisorJob +
  CoroutineExceptionHandler), thread-safe config stores, and automatic
  move of working configs into My Configs.

Install on a device/emulator with:

```bash
adb install -r ProfessorVPN-v4.0-universal.apk
```
