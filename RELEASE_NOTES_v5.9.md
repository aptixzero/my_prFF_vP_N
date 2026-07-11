# Professor VPN v5.9 — Release Notes

**Major reliability release** that makes Auto Test behave exactly as intended:
a **real** progress bar driven by the actual source work, **no fake connection
error**, correct **240-config batch replace**, a **stable list that never
flings you to the bottom**, and — most importantly — Auto Test that keeps
**working on the 2nd / 3rd / Nth press** instead of silently adding nothing.

The real VPN core (real connection, health check, real-traffic proof,
watchdog, real stats — no `Random()`) is **unchanged**. All Golden Rules from
`AI_AGENT_GUIDE.md` are preserved.

---

## 🐞 Bugs fixed in v5.9

### A + D — Fake connection error / random progress bar (CRITICAL)
Previously the Auto-Test screen filled the progress line, then showed a
**fake "connection error"**, and only ~20 s later auto-added configs. The wait
was empty and the error was not real.

**Fix:** the progress bar is now driven by the **real source-fetch work**.
`ConnectivityProbe` fills the bar as it actually opens the live feeds and
extracts valid **vless / vmess** configs (`pct = added * 96 / target`). The
moment it has confirmed it can pull configs from a source, the bar completes
and the configs are **added immediately**. A connection error is shown **only**
when *not one* source could be opened (a genuine no-internet state) — never as
a fake step.

### B — 240-config batch replace
Auto Test (and the big purple manual button) adds **240 configs per press**,
tests them, and pins the pinging ones to the top of **My Configs**. If a list
already exists, the previous list is now **cleared first** and a fresh batch of
240 is added (`AutoTestActivity` wipes My Configs via
`store.saveServers(emptyList())` before adding the new batch renamed `Server N`).

### C — Scroll jump to the bottom
While the next 240 were being added, or during pinging, the user was flung to
the **bottom** of the list (down among the dead configs).

**Fix:** both **My Configs** and **Free Configs** now keep the user **at the
section that shows the pinging configs**. When a live update arrives while the
user is at the top, the list re-pins healthy configs to the top and holds the
user there (`isAtTop()` + `scrollToTop()`), and reloads preserve scroll
position otherwise. No more auto-jump to the bottom.

### E — Second / third Auto Test adds nothing (CRITICAL)
The first Auto Test worked, but pressing it again added **no configs**. Root
cause: the persistent dedup memory (`SeenConfigStore`) + in-run `seenKeys`
accumulated every config ever seen, so on the next run every currently-live
config looked like a duplicate and was dropped → empty result.

**Fix:** when a run finds the sources **reachable** (`reachedSource == true`)
but every fetched config is a duplicate, the app now **resets the dedup memory**
(`seenKeys.clear()` + `SeenConfigStore.performReset(ctx)`, re-seeding only the
current My-Configs keys) and retries the same batch — so the currently-live
configs are served again. Applied in **both** `ConnectivityProbe` and
`AutoTestEngine`. Auto Test now works repeatedly, without limits.

### Handler robustness (240-loop)
The 240-at-a-time loop runs under a `SupervisorJob` with bounded dedup memory
and periodic prune (`PRUNE_FLOOR = 400`) so it runs continuously **without
cache bloat, crashes or freezing**.

### My Configs — PING ALL instant pin
Pressing **PING ALL** now **instantly** pins pinging configs to the top and
sinks non-pinging / unstable ones to the bottom, live, as results arrive
(`pingAllInFlight` + `observePingStatuses`).

---

## ✅ Golden-rule checklist (all preserved)
- Post-connect health check + real-traffic proof: **kept**.
- All stats real, **no `Random()`** in any stat path: **kept**.
- Only **vless / vmess** parsed / added: **kept**.
- No ad-network scripts, no hardcoded credentials / tokens: **kept**.
- Admin-panel sync unchanged & permanent.
- `versionCode` (40) and `versionName` (5.9) bumped together.

## Carried over from v5.8 / v5.7
- Reaching the source feeds is the success signal (real work, not a fake step).
- Pings are content-keyed + persisted and stay stable across restart / tab
  switch / screen off-on.
- Free & My Configs pin healthy configs to the top and keep the user at the top.

---

## 📦 Download
Signed **universal** release APK: `ProfessorVPN-v5.9-universal.apk`
(from the **Releases** page or the build workflow artifacts).
- `versionName`: **5.9**, `versionCode`: **40**
- Signer: `C=US, O=NeonVPN, CN=NeonVPN`
