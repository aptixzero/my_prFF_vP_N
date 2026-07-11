# Professor VPN v5.7 — Release Notes

This is a **bug-fix / stability** release. The real VPN core (real connection,
real ping/upload/download, post-connect health check, watchdog) is **unchanged**
— all Golden Rules from `AI_AGENT_GUIDE.md` are preserved. v5.7 focuses on the
config / ping UX bugs reported by users.

---

## 🐞 Fixed

### 1. Auto Test "connection test" no longer hangs without adding configs
- The Auto Test connectivity probe scans the 50 live sources and finds one
  working **VLESS** and one working **VMESS**. Previously, when a probe line
  finished the page sometimes closed **without adding any config** and without
  telling the user why.
- Now the decision is made **exactly when the scan line finishes**:
  - **If** a reachable vless/vmess is confirmed → the configs are **saved to My
    Configs first** (the write completes before the page closes) and a toast
    confirms `"N config(s) added to My Configs"`. The continuous engine then
    keeps filling My Configs in the background.
  - **If** no source responded → the app shows a clear error:
    **"خطا در ارتباط — لطفاً دوباره تلاش کنید"** ("Connection error — please try
    again") and does **not** silently start the background engine.

### 2. Free Configs no longer drags you to the bottom of the list
- When configs are pinged, the healthy (pinging) servers are pinned to the
  **top** and the view now **snaps back to the top** — you stay with the working
  servers instead of being carried down 240 rows to the dead ones.
- Pressing **START SEARCH** now **appends** fresh, untested configs to the
  **bottom** of the list (targeted range-insert) instead of re-sorting the whole
  list, so your scroll position and the already-pinged rows at the top stay
  exactly where they were.

### 3. Pings are now taken ONLY on demand, and they stick
- **No automatic pinging.** Opening the Free / My Configs tab, switching tabs,
  turning the screen off/on, or relaunching the app **never** starts a ping
  sweep on its own and **never** clears existing results. (The v5.6 auto-ping
  loop that re-tested every few seconds has been removed.)
- A ping is measured **only** when you ask for it: the per-row **PING** button or
  **PING ALL**.
- Measured pings are **content-keyed and persisted**, so a config keeps its ping
  across app restart, tab switch and screen off/on. A ping is replaced only when
  you deliberately re-test that config (or press PING ALL).

### 4. My Configs — healthy configs pinned to top, you stay at the top
- After a manual **PING ALL**, the fastest/healthy configs are pinned to the top
  and the view snaps back to the top, so a dead "Server 1" can no longer pull you
  (and the whole list) down.

---

## 🔄 Admin-panel sync (unchanged, confirmed permanent)
The admin panel (`aptixzero/prf-vpn-admin`) publishes `app_config.json` straight
into `aptixzero/PRF_VPN @ adminpanel/app_config.json` via the GitHub Contents
API, and the app reads that same file. Every change you make in the panel is
committed to the repo, so it is **permanent** and picked up by the app on launch.

---

## ✅ Golden-rule checklist (all preserved)
- Post-connect health check + real-traffic proof: **kept**.
- All stats real, **no `Random()`** in any stat path: **kept**.
- Only **vless/vmess** parsed/added: **kept**.
- No ad-network scripts, no hardcoded credentials/tokens: **kept**.
- `versionCode` (38) and `versionName` (5.7) bumped together.
