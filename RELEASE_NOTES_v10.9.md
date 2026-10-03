# Professor VPN v10.9

## The sweep that never interferes with itself

v10.8 made second use stable. This release fixes what the sweep does while
it runs: frozen starts, batch interference (55 rows then 18 rows), dead rows
that never sink, buttons that invite a second run on top of the first, and
the panel's wrong numbers and console errors.

### Panel (ships with this release, deploys on merge)

- **Dashboard counts devices only.** The legacy aggregate counters are gone:
  a fresh registry shows ZERO honestly until v10.9 devices report in — no
  more "113 installs", no window arithmetic, no double count. Online = seen
  inside 3 minutes (one row, one vote).
- **Console errors fixed.** The devices client called a `/api/devices/list`
  path that no longer exists (404 on every Users/Tracking visit); it now
  calls the combined `/api/devices?action=list`. The publish page parsed an
  empty baseline on every login (JSON SyntaxError); an empty baseline now
  diffs as an empty object.
- **Users table** gains Phone + Android columns (self-reported model and
  release, e.g. "Pixel 8 · Android 14"). **Tracking** is now a real list:
  status filter, device search, paging, row select — plus a device-detail
  card (presence, phone, app version, usage history, data, last seen).
- **Credentials rotated** via env. No credential text in code or labels.

### The sweep

- **Rotation forgets, always.** The status prune fired only above 400 rows —
  i.e. never on a normal batch — so rotated-away verdicts lingered in
  memory. The floor is gone: rotation removes every key the new list does
  not hold (Room was already cleaned), in one emission.
- **One sweep at a time, and the buttons say so.** While the engine sweep
  runs, START SEARCH, PING ALL, DELETE ALL and the row ping buttons lock.
  While a manual PING ALL runs, START SEARCH stays open (it launches the
  analysis page) but PING ALL / DELETE ALL lock. STOP stays live throughout.
- **My Configs sinks dead rows.** A just-failed row moves to the bottom
  through the same reorder path that pins a just-proven row to the top —
  lowest ping top, dead rows bottom, in Free and in My Configs alike.
- **Pause on no network, resume on return** (unchanged, load-bearing): the
  cycle waits with its wake lock instead of painting rows red, and resumes
  where it left off. STOP stays final and clears the carry.
- **Device facts, still no identifiers**: register + heartbeat now carry
  the self-reported model, Android release, and SDK (JSON-escaped, capped).
  No IP, MAC, serial, location, account, host, domain, or app — anywhere.

### Privacy (unchanged, structural)

Per-site and per-app history is not collected by design: the app reports
session counters only, so no browsing history can be reconstructed from the
panel. The store holds no IP, MAC, location, host, domain, or app.

versionCode 90 / versionName 10.9. Same signing key as v6.7–v10.8, so it
installs over the existing app.
