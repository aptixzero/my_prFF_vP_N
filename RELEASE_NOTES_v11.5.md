# Professor VPN v11.5

## URLTest: the ping is a real request through the proxy

The number is not a ping of the server IP. Each VLESS or VMess config opens
a real connection and sends an HTTPS request through that proxy. The clock
runs until a valid HTTP answer comes back.

### What a row now means

- Exactly 3 attempts, one after another, each on a fresh connection.
- The shown number is the median of the attempts that answered.
- A timeout is a failed attempt, not a large ping.
- 3/3 is Available. 1/3 or 2/3 is Unstable. 0/3 is Unreachable.
- A slow full success stays above an unstable fast miss.
- The list still adds 120 at a time. Three workers test at once.

versionCode 96 / versionName 11.5. Same signing key as v6.7–v11.4.
