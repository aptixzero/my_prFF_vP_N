# Professor VPN v10.3

## Pings work again, and every config shows its verdict live

- **The v10.2 regression that made every ping fail is fixed.** v10.2 let the
  per-network strategy profile turn multiplexing (mux) on for the connection
  *and for the measurement*. A mux session the server does not speak fails the
  whole connection, so a mux-enabled pick turned an entire sweep red. v10.3:
  the measurement never carries mux (the vendored core's own upstream strips it
  from its latency test for the same reason), Reality and XTLS-Vision configs
  never take a mux block, and the automatic mux/handshake/fragment overrides are
  reverted — the plan is the mode table plus your own switches again, with the
  measured shaping kept.
- **The list now says which config pinged and which did not.** Every row of a
  batch gets a real verdict: green and pinned to the top when it works, red and
  pushed to the bottom when it does not, updated live while the sweep runs.
  Rows the sweep dropped — a socket that never opened, a port this network
  blocks — are shown red instead of sitting at "tap PING to test" for ever.
- **The counter matches the batch.** "Checked N/120" counts every config the
  sweep took on, including the ones that failed at the socket stage. It used to
  report only the survivors, which is why the label said 0/11 while the list
  held 36 configs.
- **A batch is 120 configs: 60 VLESS + 60 VMESS.** The corpus draw is
  protocol-balanced and the connection-test page collects a full batch instead
  of stopping at 18 + 18.
- **No more rows stuck on "Pinging…".** A probe that produced no verdict
  (starved, cancelled, clipped by the wall clock) no longer blocks its own
  re-probe: the row returns to untested, is walked first in the next cycle, and
  only a second no-verdict outcome settles it.
- **The untested backlog is never discarded.** Rows already on the list when a
  run starts are carried until they are actually walked, so nothing is left
  behind after a batch handed over by the connection-test page.
- **Endpoint copies inherit their twin's verdict** instead of staying untested.
- **A green config is pinned in the Free list and added to My Configs** the
  moment its verdict lands.

versionCode 84 / versionName 10.3. Same signing key as v10.2, so it installs
over the existing app.
