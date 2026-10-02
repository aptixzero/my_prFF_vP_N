# Professor VPN v10.6

## Testing that tells the truth, tests every config, and finishes

v10.5 made every row go through a real probe. This release fixes what that
probe did on a real device: it clipped rows that were still queued, skipped
some rows without ever testing them, tested a degraded copy of imported
configs, showed "Pinging…" for hours, and never said WHY a config failed.

### The list and the tests

- **No row is skipped without a verdict.** A row whose `address:port` had
  already been measured in the same run was silently skipped — no probe, no
  status, no reason — while the counter counted it as tested. Such a row now
  inherits the measured verdict of the row that owns that server, and if the
  owner has not finished, the row is probed itself in the next cycle.
- **Endpoint copies are accounted for.** Two configs for the same server
  (different UUID) were probed once and the copy was left at "tap PING to test"
  for ever. The copy now inherits the twin's verdict — in the automatic sweep
  and in the manual PING ALL, which previously excluded it from the plan
  entirely.
- **"Pinging…" only appears while a row is really being probed.** PING ALL used
  to paint every row of the list as "Pinging…" the moment it started — on a
  large list that is thousands of rows stuck for hours. Each worker now marks
  the one row it is probing.
- **A clipped probe is no longer counted, and no longer gives up after two
  tries.** The per-row wall was 21 s while the probe pool may legitimately make
  a caller wait 30 s for a permit: queued rows were killed before they ran,
  counted as "Checked", retried, killed again and finally settled untested.
  The wall now covers the pool's own acquire window, a row that produced no
  verdict is retried up to three times, and the counter only advances on a real
  verdict.
- **Every failure shows its reason**: "✕ unreachable · no TCP answer",
  "· invalid config", "· handshake reset by network", "· too slow to carry
  data", and so on — instead of a bare "unreachable" that reads like "we did
  not check".
- **A dead row is dialled once, not twice.** The gate refresh and the lane were
  both dialling the same dead endpoint; the refresh result is now forwarded, so
  the dominant cost of a dead-heavy batch is paid once.

### The config that is tested is the config you imported

- **Room no longer drops protocol fields.** `ConfigEntity` cannot store
  `alterId`, the VMess cipher, `headerType`, `spiderX` or `seed` — all of which
  the Xray config builder emits — so a config read back from My Configs was
  tested and connected with those replaced by defaults (a kcp node lost its
  type and seed, a legacy VMess node its alterId, a Reality node its spider
  path). The full config is now rebuilt from the stored raw link, while the
  row's identity and display name stay exactly as they were.
- **Tapping a config can no longer connect to a different one.** The Free
  list's "save to My Configs" write could be refused by the 15-per-location
  bulk cap, while the tap still selected the row — and the connect path then
  fell back to the first config in My Configs. An explicit tap now bypasses the
  bulk cap, the selection is set only after the row is really stored, and a
  selection the store does not hold resolves to "not found" instead of an
  unrelated server.

### Automatic, link-aware test settings

- **Wi-Fi is finally recognised as Wi-Fi.** The network key became
  `wifi-<hash>` in v8.8 but the mapper still matched the bare `wifi`, so every
  normal Wi-Fi network was classified "unknown" and no Wi-Fi-aware default ever
  applied.
- **The test tuning is chosen from the measured link** on every START SEARCH —
  deep-probe ceiling, lane wall and per-probe budget, derived from link class,
  weakness, RTT, loss and whether the OS reports a constrained mobile link.
  A healthy Wi-Fi link runs at the full RAM-safe width; a lossy cellular link
  gets fewer probes and a longer wall.
- **An explicit setting always wins.** A probe-concurrency pin the user set is
  used as-is (it used to be silently clamped by the strategy profile's hint),
  the measured tuning is written to the automatic namespace only, and tapping
  AUTO on the mode row no longer wipes every other explicit preference (it used
  to clear all fifteen override flags at once).

### Also

- A fingerprint taken while the VPN is connected now classifies the REAL link
  underneath the tunnel instead of reporting "unknown".
- A single-row ping can no longer leave a row stuck on "Pinging…", and the Free
  tab's PING ALL is disabled while the automatic sweep is running (they share
  one status map).

### Accuracy follow-ups from the review round

- The forwarded gate failure keeps its real cause: a refused connection and a
  DNS failure are no longer reported as "no TCP answer".
- A pool-pressure verdict is no longer retried immediately behind the same
  saturated pool (it is carried instead), and the confirmation's wall covers
  the pool's queue just like the first-answer wall does.
- The measured tuning is keyed to the network it was measured on, so a
  Wi-Fi-derived width never silently applies after a switch to cellular.
- The Free list's tap is honoured end to end: the last tap wins, a row that
  could not be stored is not selected, and the toast says so instead of
  claiming success.
- The manual sweep's counter counts verdicts (not attempts), its cleanup only
  touches its own rows, and stale reasons are pruned with their statuses.

versionCode 87 / versionName 10.6. Same signing key as v10.4/v10.5, so it
installs over the existing app.
