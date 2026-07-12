# Professor VPN — v6 (versionCode 41)

Update 6 is a focused reliability + UX release for the config discovery, connection
test, and ping pipeline. Nothing about the real Xray-core / tun2socks VPN engine
changed — only the config-sourcing, connectivity-probe and list behaviour.

## 🐞 Bug fixes

- **Removed the fake "connection error".** The bogus *"Connection error — please
  try again"* that used to flash for no reason is gone. The Auto-Test page never
  surfaces a hard error now — if the link is momentarily unreachable it simply
  closes quietly and the background engine keeps trying.
- **Fixed "stuck at 2%".** The connection-test progress bar is now driven by real
  network work (each source actually probed), not a timer, so it always advances
  past the first few percent and climbs honestly to 100%.
- **Fixed "stops adding after a couple of tries".** A re-entrancy guard
  (`AutoTestEngine.lifecycleLock` + `restart()`) means opening/closing the page,
  interrupting a test mid-run, or pressing Auto-Test repeatedly can never wedge
  the engine or leave two loops fighting. It **always** produces configs, no
  matter how many times you press.
- **Ping no longer "stops working".** Removed the `BACKOFF_MS` cool-down that
  silently swallowed a rapid second Ping-All request.

## 🔌 New two-phase connection test

Tapping **Search** or **Auto Test** now opens a real connection-test page:

- **0% → 60%** — a genuine connectivity test against the 50 live source feeds.
  It opens each source and finds which one you can actually reach, then **bonds**
  to it. The bar advances as each source is really probed (no `sleep`/random
  timer).
- **60% → 100%** — it pulls a full fresh **240** batch (120 VLESS + 120 VMESS)
  of configs **from that same reached source, in the background** (you don't see
  the Free tab). The bar climbs as configs are actually collected.
- At 100% the configs are added, the page closes, and a **Ping-All** fires
  automatically so working configs sort to the top.

The next 240 configs always come from the **same source you first connected to**
(sticky bond, remembered in `ConnectedSourceStore`, cleared on the 30-day reset).

## 📌 Scroll stability

You now **stay at the top of the list by default** — where the pinging configs
are. Adding 240 more configs, a config failing to ping, the first server dropping
to the bottom, or a full reorder will **not** fling you to the bottom. Only your
own manual scroll moves you.

## ⚡ Live "pin lowest ping to top"

When you press **Ping All** (My Configs *and* Free Configs), the moment a config
returns a lower ping it is pinned to the top **immediately** (live), not only at
the end of the sweep.

## 🤖 Auto Test

Auto Test keeps auto-pinging and adds the configs that ping into **My Configs**,
240-at-a-time from the same bonded source, until you press Cancel.

## 🏗️ Build

- `versionCode 41`, `versionName "6"`.
- The signed universal APK (`ProfessorVPN-v6-universal.apk`) is built on GitHub
  Actions CI and published to the **v6** release + committed into `build/`.
