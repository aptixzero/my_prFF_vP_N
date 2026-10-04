# Professor VPN v11.6

## A fast probe, then a real check of the ones that answer

v11.5 waited on one private address. If that address was empty or blocked,
every config looked dead, and each row paid for three long waits.

### What changed

- The test targets are public connectivity checks: Google generate_204
  (HTTP and HTTPS) and Microsoft connecttest. No key, no account, no
  private domain.
- A miss on one target tries the next. One blocked target does not make
  a live config unreachable.
- Every row gets one fast probe with a 4 second hard stop.
- Only the best 20 that answered get the 3-attempt median.
- Workers follow the phone: 3, 6, or 8.
- A broken config never enters a worker.
- The search stops when it has a real best, instead of walking banks for hours.

The batch is still 120. The number is still a request through the proxy,
not a ping of the server IP.

versionCode 97 / versionName 11.6. Same signing key as v6.7–v11.5.
