# Professor VPN v9.9

## Faster real pings, list no longer resets

- **List / Search ping is FIRST_ANSWER again.** A green number is still a
  real e2e Cloudflare answer through a live core. The 100 KB payload tax
  is no longer paid on every row — that is why v9.8 took minutes and
  often found zero greens.
- **The Free list is cumulative.** A later batch is merged. Saving the
  on-screen snapshot can no longer wipe the next batch. Untested rows
  stay until they are actually measured.
- **Port prefilter only runs when it is a real filter.** A 2-port sample
  no longer shrinks 240 configs down to 18 and then replaces the list.

No RNG. No invented pings. Rows are walked 0..N. Same signing key.

versionCode 80. Universal APK.
