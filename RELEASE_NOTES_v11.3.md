# Professor VPN v11.3

## Ping starts as soon as the list has rows

v11.2 added the configs, then sat at 0/N. The counter only moved after a
core call returned, and that call ignores cancellation. On a filtered link
it never returned, so the sweep looked frozen. The list itself was late
because the search page measured the whole network before it downloaded
one config.

### What changed

- The search page downloads configs first. Network analysis still runs,
  beside the download. It no longer blocks the list or the ping.
- VLESS and VMESS banks download together, not one after the other.
- The TCP wave no longer runs before the ping. The core call is the ping.
- The progress bar moves when a worker picks up a row, not only when a
  verdict lands. 0/N cannot sit there while work is in flight.
- A hung native delay is abandoned after 5 seconds. The worker moves to
  the next row. One hung call cannot freeze the sweep.
- The probe hostname is pinned to 1.1.1.1 inside the ping config, so the
  first probe does not wait on device DNS. The certificate still matches
  the hostname.

versionCode 94 / versionName 11.3. Same signing key as v6.7–v11.2.
