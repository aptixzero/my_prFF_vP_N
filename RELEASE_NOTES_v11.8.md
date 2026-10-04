# Professor VPN v11.8

## The ladder decides, every target gets its slice

v11.7 asked one private call per row and sliced the budget so thin the
third target was never tried. A row whose only working page was tried
last never got tested, and 5000 rows could pass with zero greens.

### What changed

- Every row runs the tested ladder first: same ping config as connect,
  same measured plan, pool permit, honest failure class.
- A ladder answer is counted with a fresh UrlTest request. A ladder miss
  still gets one fast UrlTest request.
- Each probe target gets a bounded slice inside a 6 second fast budget,
  so Cloudflare HTTP, Cloudflare HTTPS, and Microsoft are all tried.
- A no-verdict row (pool pressure, cancel, wall) stays untested for the
  next cycle. It is not painted red.
- Dead rows keep their real reason: which target, which failure.

The batch is still 120. The walk still continues until STOP. The number
is still a request through the proxy, not a ping of the server IP.

versionCode 99 / versionName 11.8. Same signing key as v6.7–v11.7.
