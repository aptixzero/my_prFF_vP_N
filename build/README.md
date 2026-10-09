# Build output

The signed **universal** release APK for Professor VPN lives here — exactly one
APK, always the binary for the current `versionName`.

- Output name: `ProfessorVPN-v12.9-universal.apk` (`versionCode 109`)
- ABIs: `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86` (Android 7.0+ / `minSdk 24`)
- APK SHA-256: `b0f80a8df17332d58ca0b3a15e4c7043886f37c0a3401d862f25890b509a51cc`
- Certificate SHA-256: `6a5ed5e32014ee77b41ca9ef9c71c5ab3397156d25fe22c7f1d52bb8907eb82d`
  (same release key as v6.7–v12.8 — installs directly over any previous version)
- Built, unit-tested and signed by `.github/workflows/release.yml` on GitHub's
  runners; the bytes here are the Release asset, re-downloaded and re-checked
  (hash, versionCode/versionName, certificate, ABIs) before being committed.

See [`RELEASE_NOTES_v12.9.md`](../RELEASE_NOTES_v12.9.md).
