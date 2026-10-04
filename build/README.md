# Build output

The signed **universal** release APK for Professor VPN lives here — exactly one
APK, always the binary for the current `versionName`.

- Output name: `ProfessorVPN-v12-universal.apk` (`versionCode 101`)
- ABIs: `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86` (Android 7.0+ / `minSdk 24`)
- APK SHA-256: `e4a3722208ea75b51395c0315dd1875b42ce0cc6ac174355b635d5a304e59ad5`
- Size: 60519902 bytes
- Same release key as v6.7–v11.9
  (certificate SHA-256 `6a5ed5e32014ee77b41ca9ef9c71c5ab3397156d25fe22c7f1d52bb8907eb82d`)
- `zipalign -c 4` verified; APK Signature Scheme v2 verified.

See [`RELEASE_NOTES_v12.md`](../RELEASE_NOTES_v12.md).
