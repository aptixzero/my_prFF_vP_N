# Professor VPN — v6.1 (versionCode 42)

Update 6.1 is a focused bug-fix release for the **connection-test page** and the
**config buckets** (My Configs vs. Free Configs). Nothing about the real
Xray-core / tun2socks VPN engine changed — only the connectivity-probe and the
config-routing/ping behaviour.

## 🐞 Bug fixes

### 1. Connection-test bar no longer "locks on a number"
The `0 % → 60 %` connection-test phase now runs in a **retry loop**:

- While the link is alive the bar climbs honestly as each source feed is really
  probed.
- If the internet drops **completely** mid-test, the bar **holds (locks) on its
  current value** instead of pushing forward or racing to 100 %.
- The instant the connection comes back, the test **resumes** from where it
  paused and continues to 60 %.
- The bar can **never** be carried to the phase boundary (60 %) or to 100 % by a
  kind that never actually reached a source — the boundary is emitted **only** on
  a genuine success.

So: alive → progresses; fully offline → pauses on a number; reconnected →
continues. Exactly as requested. There is still **no fake "connection error"**.

### 2. Auto Test now adds to **Free Configs**, not My Configs
This was the main reported bug. Previously the connection-test page dumped all
**240** raw configs straight into **My Configs**. That was wrong.

Now (like the older versions):

- **0 % → 60 %** — real connection test against the live source feeds.
- **60 % → 100 %** — the fresh **240** batch (120 VLESS + 120 VMESS) is placed in
  **Free Configs** (the old Free batch is wiped and replaced).
- The continuous **Auto Test engine** then pings the whole Free batch and, for
  **each config that actually returns a ping**, copies it — live, one by one —
  into **My Configs**.
- When the 240 are exhausted, if Auto Test is still on it **wipes the Free list
  and pulls a brand-new 240** from the same bonded source, and repeats.

### 3. My Configs is now permanent & clean
- **My Configs only ever contains**: configs you added manually (paste), plus the
  configs that **actually ping** (auto-copied by the engine after they pass the
  ping test). It is **never** stuffed with 240 raw configs again.
- **My Configs is permanent** — it is never auto-wiped by a search / Auto Test /
  batch rotation. It only clears when **you** delete configs by hand.
- **No auto-ping in My Configs** — pings there are taken only when you press
  PING (per row) or PING ALL. Opening the tab / relaunch never starts a sweep.
- **The last measured ping sticks to each config** (content-keyed) and survives
  restart, tab switch, screen off/on, and the config being renamed "Server N"
  when it is copied in.

## 🔌 Panel ⇄ App
The admin panel publishes `app_config.json` to `aptixzero/PRF_VPN` and the app
reads it from the same repo (with jsDelivr / raw mirrors for reliability inside
Iran), so the panel and the app stay in sync.

## 🏗️ Build
- `versionCode 42`, `versionName "6.1"`.
- The signed universal APK (`ProfessorVPN-v6.1-universal.apk`) is built on GitHub
  Actions CI, the previous APK in `build/` is deleted and replaced, and a single
  latest **v6.1** GitHub Release is published with the APK attached.
