# Professor VPN v10.2

## Real search — consecutive numbers, honest verdicts, sources that learn

- **Server numbers are consecutive again.** The number is issued when a row
  reaches the list, not when the corpus ingests it, so the sequence reads
  Server 1, 2, 3 … instead of jumping from Server 36 to Server 3456. Rows that
  are never shown no longer consume numbers.
- **Nothing is marked dead without a real probe.** A probe that was starved,
  cancelled or clipped by the wall clock is no longer painted "✕ unreachable";
  the row is carried to the next cycle and re-probed, and only a second
  inconclusive outcome settles it.
- **A cycle that runs out of time hands its unfinished rows to the next one.**
  Through v10.1 those rows stayed "tap PING to test" forever while a fresh batch
  was drawn on top. The backlog is now walked before anything new is drawn, so
  the list grows only as fast as it is measured.
- **The app learns which source works on your network.** `probeSuccesses` — the
  dominant term of the source ranking — had no writer at all, so the ranker
  could never prefer the feed that actually yields working configs. Every config
  that proves working is now credited to the feed that supplied it.
- **Source rotation works again.** One permanently failing feed (the
  documented-404 Pages bridge entries) used to keep the corpus from ever
  refreshing; a failed attempt is no longer treated as "still reading".
- **The source list was repaired.** 18 duplicate entries were removed: feeds
  whose URL is a `vmess.txt` file were listed a second time under VLESS, so
  every fetch of that copy was rejected line by line and then charged to the
  feed as parse errors. No source was lost — the VLESS list drops from 136 to
  118 entries and the VMESS list is unchanged.
- **The per-network strategy now really tunes the tunnel.** The strategy engine
  computes a full profile for the current network fingerprint; through v10.1
  only its "shaping" ever reached the connection. Mux on/off, mux concurrency,
  the freedom fragment ranges and the handshake budget now all reach the plan,
  and the profile is re-applied whenever the link is measured — not only during
  a search. The mux-concurrency stepper, which wrote a value nothing read, now
  shows and edits the value actually in force.
- **An explicitly picked mode keeps its promise.** The automatic values above
  apply only while the connection mode is automatic; pick GAMING, SHIELD or
  GHOST by hand and the mode's own mux/handshake decision stands.
- **Choosing a config from the Free list really saves it.** A row the corpus
  already held was silently dropped by the insert (primary-key conflict), so it
  never appeared in My Configs and the connected-server name stayed the raw feed
  remark — which the lock screen then showed. The row is now promoted in place
  with its numbered name, and the notification sanitises the name as well.
- **The per-network strategy is loaded per network.** Only the first network
  seen in a process restored its learned state; every later one (a Wi-Fi ↔
  mobile handover) sampled from blank priors, i.e. an effectively random
  profile. Each fingerprint now loads its own.
- **"Green" is honest.** A measurement may only report the sustained (100 KB)
  stage when that stage actually ran.

versionCode 83 / versionName 10.2. Same signing key as v10.1, so it installs
over the existing app.
