# Build output

The signed **universal** release APK for Professor VPN lives here — exactly one
APK, always the binary for the current `versionName`.

- Output name: `ProfessorVPN-v11.9-universal.apk` (`versionCode 100`)
- ABIs: `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86` (Android 7.0+ / `minSdk 24`)
- APK SHA-256: `24abc6bf99e65ef3b8c681864842295f0ff631d76f0d5ea750ce44f1b90c0858`
- Size: 60523994 bytes
- Same release key as v6.7–v11.8
  (certificate SHA-256 `6a5ed5e32014ee77b41ca9ef9c71c5ab3397156d25fe22c7f1d52bb8907eb82d`)
- `zipalign -c 4` verified; APK Signature Scheme v2 verified.

See [`RELEASE_NOTES_v11.9.md`](../RELEASE_NOTES_v11.9.md).
