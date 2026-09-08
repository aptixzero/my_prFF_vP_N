# Build output

The signed **universal** release APK for Professor VPN lives here — exactly one
APK, always the binary for the current `versionName`.

- Output name: `ProfessorVPN-v10-universal.apk` (`versionCode 81`)
- ABIs: `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86` (Android 7.0+ / `minSdk 24`)
- APK SHA-256: `764e60bbe942fd3fe98fe6307052a8da8314fb4dc0ad643eb8cbb56dc2325a7d`
- Size: 60,459,302 bytes
- Same release key as v6.7–v9.9
  (certificate SHA-256 `6a5ed5e32014ee77b41ca9ef9c71c5ab3397156d25fe22c7f1d52bb8907eb82d`)
- `zipalign -c 4` verified; APK Signature Scheme v2 verified.

See [`RELEASE_NOTES_v10.md`](../RELEASE_NOTES_v10.md).
