# Professor VPN v9.8

## Stable ping, stable connect, bounded caches

- **A green ping is a working tunnel.** List ping and Auto Test now use the
  STANDARD lane (real e2e plus a 100 KB payload). A number on screen means
  the node already carried real bytes, so tapping Connect on it must work.
- **Pings survive close / tab switch / reopen.** A later session-path miss
  no longer poisons Room. Hydrate restores the last proven millisecond
  unless the probe ladder itself rejected the node.
- **My Configs no longer jumps while pinging.** Order is frozen mid-sweep
  and sorted once at the end by the real canonical key.
- **Free PING ALL keeps its result if you leave the tab.** The post-sweep
  sort and persist run on the process-lifetime sweep, not the destroyed
  fragment.
- **Caches stay bounded.** SourceFetcher body/fail maps are capped and
  pruned every 2 minutes. The Free list cannot grow past the soft cap on
  save. Long sessions no longer fill heap.
- **Connect waits longer for SOCKS** (3 s) so a slow core start is not
  torn down as "not responding".

No RNG feeds any displayed ping, count, or server pick.

versionCode 79. Same signing key as v9.7. Universal APK.
