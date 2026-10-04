# Build output

The signed **universal** release APK for Professor VPN lives here — exactly one
APK, always the binary for the current `versionName`.

- Output name: `ProfessorVPN-v11.8-universal.apk` (`versionCode 99`)
- ABIs: `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86` (Android 7.0+ / `minSdk 24`)
- APK SHA-256: `73301e901539880fd03bfa4081231c5126576f12afcc5d6cbd6f76fe207c8d39`
- Size: 60522118 bytes
- Same release key as v6.7–v11.7
  (certificate SHA-256 `6a5ed5e32014ee77b41ca9ef9c71c5ab3397156d25fe22c7f1d52bb8907eb82d`)
- `zipalign -c 4` verified; APK Signature Scheme v2 verified.

See [`RELEASE_NOTES_v11.8.md`](../RELEASE_NOTES_v11.8.md).
