# Professor VPN — v5.6

## Rock-solid pings, stable lists, and a connect animation that never freezes

v5.6 is a stability + polish release focused on the exact issues reported: pings
that "jumped" or reset, the Free-Configs list flinging the user around while
testing, Auto Test silently dropping out, and the connect animation freezing
after the app was closed and reopened. It also confirms the live admin-panel sync.

## Admin panel sync (persistent settings)

- The app is wired to the **`prf-vpn-admin`** panel. The panel publishes a single
  `app_config.json` to `aptixzero/PRF_VPN/main/adminpanel/app_config.json`, and the
  app fetches it on every launch through a resilient mirror chain (jsDelivr / jina)
  so it loads inside Iran.
- Whatever the operator sets in the panel — **banner image, in-app Telegram link,
  contact id, CTA link, wallet / donate addresses, app logo** — is cached locally
  and stays applied **permanently**, across app restarts and **across every version
  the user installs**. Change it once in the panel and it sticks everywhere.

## Free Configs — no more scroll jumping + auto ping

- **The list no longer re-orders on every ping tick.** Previously, each time a
  config finished pinging the whole list re-sorted, so the row you were on (even at
  the very top) was yanked to the bottom and the scroll flew to the end. Now live
  ping results update **in place** — your position never moves.
- Sorting fastest-first now happens **only** on an explicit action (a manual
  PING ALL completing, or a list reload) — never mid-scroll.
- **Automatic ping.** The Free tab now pings new / untested configs by itself and
  keeps them fresh, with no need to press PING ALL. Freshly-searched configs are
  auto-tested the moment they're added.

## Pings are now permanent (Free **and** My Configs)

- Ping results are keyed to each config's **content**, not a throwaway id. That
  means the last measured ping **sticks to the config forever**: it survives
  closing the app, switching tabs, turning the screen off/on, an Auto-Test batch
  refresh, and the same config appearing in both Free and My Configs.
- Removed the memory-pressure code path that used to **wipe** measured pings after
  a few minutes (it fired routinely during long screen-off sessions). Results are
  also mirrored to disk, so they reappear even after a low-memory event.
- Re-running a test always shows the **new** ping while never blanking the previous
  one mid-measure.

## Auto Test — sticky lifecycle handler

- Auto Test runs in an **app-scoped** engine, so closing the screen, switching
  tabs, or toggling the phone display **no longer stops it**.
- New **sticky flag**: if the OS kills the process during a long screen-off run,
  Auto Test **automatically resumes** on next launch. It only stops when the user
  explicitly taps CANCEL.
- It keeps adding working configs correctly through all of the above.

## Connect animation — no more freeze

- The globe/connect animation now **re-arms itself** whenever the view is
  re-attached or the app returns to the foreground. The long-standing "close &
  reopen → animation frozen/stuck" bug is fixed; the connect visuals are smooth and
  always alive.

## Notes

- Real VPN core, real ping/upload/download, post-connect health check — all
  unchanged and intact (no fake "connected").
- Only vless / vmess are supported (project policy).
