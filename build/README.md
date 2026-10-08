# Build output

The signed **universal** release APK for Professor VPN lives here — exactly one
APK, always the binary for the current `versionName`.

- Output name: `ProfessorVPN-v12.8-universal.apk` (`versionCode 108`)
- ABIs: `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86` (Android 7.0+ / `minSdk 24`)
- Certificate SHA-256: `6a5ed5e32014ee77b41ca9ef9c71c5ab3397156d25fe22c7f1d52bb8907eb82d`
  (same release key as v6.7–v12.7 — installs directly over any previous version)
- Source: `aptixzero/professor-vpn-source-private` @ tag `v12.8`. Built and
  tested for real on GitHub Actions (JVM unit tests + `assembleRelease` +
  signing-certificate check: see that repo's `.github/workflows/release.yml`
  and the Actions run linked from its `v12.8` tag), then independently
  re-downloaded and re-verified before being copied here.
