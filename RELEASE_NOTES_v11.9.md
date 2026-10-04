# Professor VPN v11.9

## The sweep cannot freeze any more

v11.8 sat at Pinging 0/N, then cleared the list without one ping. Four
faults caused it, all in the sweep itself — not in the banks.

### What changed

- A hung core call leaks one thread, never a pool slot. Six hangs used
  to poison the pool for the process lifetime; every later probe then
  failed without touching the network.
- An inconclusive row (pool pressure, cancel, wall) is registered again.
  It was marked "pinged" before its verdict, then skipped without a probe
  on every later cycle — stuck Idle at 0/N forever.
- A definitive ladder miss settles at once. Verifying a dead row through
  UrlTest only doubled the cost on dead-heavy feeds.
- The fast wall matches the fast permit wait (3 s, not 30 s). A hung row
  holds its worker ~13 s instead of ~40 s.
- The bar shows rows in flight plus verdicts. Verdicts alone read 0/N for
  minutes while workers were busy — the "frozen" report.
- Cycle end never repaints a row that is still being probed. That repaint
  is what cleared rows mid-ping.
- The first probe target is now the official Cloudflare latency endpoint
  (`speed.cloudflare.com/__down?bytes=0`), then Cloudflare generate_204,
  then Microsoft. It is not pinned to an edge IP.

The batch is still 120. The walk still continues until STOP. The number
is still a request through the proxy, not a ping of the server IP.

versionCode 100 / versionName 11.9. Same signing key as v6.7–v11.8.
