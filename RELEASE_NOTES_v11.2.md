# Professor VPN v11.2

## A red row now means the core failed, not a raw socket

v11.1 still painted every config unreachable. The ladder stopped at a raw
TCP dial. On a filtered link that dial is dropped or reset even when the
same server answers through the core. The core never ran, so 15 minutes
produced zero greens.

### What changed

- A raw TCP miss is no longer a verdict. Only an invalid config skips the
  core. Every other row gets a real core round trip through the measured
  plan.
- One real core answer is a green ping. A missing second sample no longer
  turns a working node red.
- Probe URLs use hostnames with matching certificates. The old lead was
  `https://1.1.1.1/...`, whose certificate does not match, so the first
  probe of every config failed. Apple and Firefox portal checks are the
  fallbacks when Cloudflare does not answer from the node.
- The progress line counts the rows on screen (`Pinging n/N`) and the list
  refreshes every 200 ms, so the bar matches the list.

versionCode 93 / versionName 11.2. Same signing key as v6.7–v11.1.
