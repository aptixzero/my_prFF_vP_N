# Professor VPN v12

## Stable search and synchronized ping progress

- START SEARCH now replaces the previous Free list with one fresh generation.
- The progress bar advances when a real worker starts and remains synchronized when its verdict lands.
- The progress counter reads only the current sweep plan, not stale statuses from older lists.
- Search now detects the underlying Wi-Fi or cellular link while a VPN transport is active.
- Free-list rotation removes only stale FREE measurements. My Config measurements remain intact.
- The local Room database remains the durable store for configs, measurements, source state, and diagnostics.

versionCode 101 / versionName 12. Same signing key as v11.9.
