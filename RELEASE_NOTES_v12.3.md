# Professor VPN v12.3

## The sweep never stops on its own — and every config gets its ping

- The ping sweep no longer ends itself after one pass. It walks the visible
  list round after round, re-probing every config that is not working yet,
  until the user presses STOP (or closes the app).
- Coverage first: while any row still has no verdict, a round probes only the
  rows that have none — so the tail of the list can never be left unpinged by
  a clipped round. Once every row has a verdict, later rounds re-probe the
  rows that are not reachable.
- Fixed the "it pings nothing" report: the sweep launched more workers (6–8)
  than the probe pool admits (2–4), and the fast lane gave a queued row only
  3 seconds — every surplus worker lost the race, the row was painted idle
  with no number, and each starvation halved the pool's width. Workers are
  now sized to the pool, admission is atomic, and a row that still misses a
  verdict is re-probed by the next round.
- The sweep plan now walks the list exactly as it is displayed. The
  live-port preference used to move rows to the front of the plan, so the
  sweep visibly "started at row 8" while the list's first rows sat at the
  plan's tail, untested.
- The first-answer lane gives the native core the wrapper's full 8 s window
  (was 4 s): a cold core on a filtered link was cut off mid-answer and the
  row stayed idle with no number.
- A lost connection now pauses the sweep and resumes when the link returns —
  it never switches the search off.
- The Free list keeps its order for the whole run; the one persisted sort
  happens when the sweep actually ends.
- Native-probe admission is fully atomic and abandoned slots are reclaimed
  after 45 s, so a burst of stuck calls can never pin the app at
  "device busy" until a restart.

versionCode 104 / versionName 12.3. Same signing key as version 12.1.
