# build/

This folder holds the latest signed universal APK, produced automatically by
GitHub Actions (`.github/workflows/build.yml`). Old APKs are removed on each
build so only the newest `ProfessorVPN-v<version>-universal.apk` remains.

The stale v3.6 APKs were deleted in the v3.8 version-bump commit; CI republishes
the fresh `ProfessorVPN-v3.8-universal.apk` on the next build of `main`.
