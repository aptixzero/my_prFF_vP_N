# Build output

The signed **universal** release APK for Professor VPN is produced by the
GitHub Actions workflow `.github/workflows/build.yml` on every push to
`main` / `genspark_ai_developer`, and on manual dispatch.

- Output name: `ProfessorVPN-v<versionName>-universal.apk` (current: `ProfessorVPN-v6-universal.apk`)
- Download the **v6** APK from the
  [Releases page](https://github.com/aptixzero/PRF_VPN/releases/tag/v6)
  (published on tag `v6` / manual dispatch), or from the workflow run's
  **Artifacts**.

The APK is built exclusively on GitHub Actions CI (the dev sandbox has too little
RAM to compile it). Only the latest version is referenced here; the previous
`ProfessorVPN-v5.9-universal.apk` has been removed in favour of v6.
