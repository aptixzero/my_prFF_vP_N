# Professor VPN v5.8 — Release Notes

**Hot-fix** for the v5.7 regression where **Auto Test always showed
"connection error" and added no configs**, even on healthy connections.

The real VPN core (real connection, health check, real-traffic proof,
watchdog, real stats — no `Random()`) is **unchanged**. All Golden Rules from
`AI_AGENT_GUIDE.md` are preserved.

---

## 🐞 The bug (v5.7 regression)

In v5.7 the Auto Test connectivity page only reported success if a candidate
config could be **ping-confirmed through a live Xray core** inside the probe's
time budget. On Iran's weak / unstable mobile links that proxied ping very often
cannot complete in time — so the probe returned empty and the page showed
**"connection error — please try again"** and added **nothing**, even though all
50 source feeds were opening fine.

## ✅ The fix (v5.8)

**Reaching the source feeds is now the success signal — not a live ping.**

- The probe fetches the 50 live feeds (with the existing CDN / reverse-proxy
  mirror fallbacks that stay reachable inside Iran). The moment it opens a feed
  and extracts a **valid vless / vmess** config, it records that config as the
  result — this alone proves the user can reach the sources.
- It still *tries* to ping-confirm a config, but only within a bounded
  ping-phase budget (`PING_PHASE_BUDGET_MS`). If the ping can't finish in time,
  the fetched-and-validated config is used instead of returning empty.
- **Configs are now added whenever the feeds are reachable.** The user pings them
  later, manually, from **My Configs** (v5.7's "no automatic pinging" rule).
- **"Connection error"** is shown **only** when *not one* of the 50 feeds could be
  opened (a genuine no-internet state). Even then, the background Auto-Test engine
  is started so that the instant the (unstable) connection recovers, working
  configs start arriving without the user tapping Auto Test again.
- Probe tuning for weak links: whole-probe budget 20s → **25s**, fetch
  concurrency 4 → **6**.

**Result:** tap Auto Test → the bar fills → a vless + a vmess are added to My
Configs (no more false "connection error").

---

## ✅ Golden-rule checklist (all preserved)
- Post-connect health check + real-traffic proof: **kept**.
- All stats real, **no `Random()`** in any stat path: **kept**.
- Only **vless/vmess** parsed/added: **kept**.
- No ad-network scripts, no hardcoded credentials/tokens: **kept**.
- Admin-panel sync unchanged & permanent (panel writes `app_config.json` to
  `aptixzero/PRF_VPN` via the GitHub Contents API; the app reads that same file).
- `versionCode` (39) and `versionName` (5.8) bumped together.

## Carried over from v5.7
- No automatic pinging anywhere (ping only on PING / PING ALL).
- Pings are content-keyed + persisted and stay stable across restart / tab
  switch / screen off-on.
- Free & My Configs pin healthy configs to the top and keep the user at the top
  (no more being dragged to the dead configs at the bottom).
