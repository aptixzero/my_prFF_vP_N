# Professor VPN v11.7

## The walk continues, and STOP is back

v11.6 stopped after one list. The old rule is back: a walked list is
replaced by the next 120, working rows stay, and only STOP ends the walk.

### What changed

- After a batch is pinged, the next batch is added and the walked rows
  that did not ping are cleared. Working rows stay.
- STOP is shown for the whole walk. PING ALL and DELETE ALL stay off
  until STOP.
- The probe targets are Cloudflare generate_204 (HTTP and HTTPS) and
  Microsoft connecttest. A miss on one target tries the next.
- The number is still a request through the proxy, not a ping of the
  server IP.

versionCode 98 / versionName 11.7. Same signing key as v6.7–v11.6.
