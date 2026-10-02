# Professor VPN v10.5

## Real pings on every row, and a list that keeps moving

- **The list advances again — and it rotates.** v10.4 compared a row's parse-time
  id (a random UUID) against the canonical content hash the sweep records its
  verdicts under. No bank-drawn row could ever be seen as completed, so the whole
  batch was carried back into the next cycle for ever: the sweep re-walked the
  same rows, no new batch was ever fetched, and the list stayed frozen at the
  first batch (Server 1…84, or 1…120) with the numbers no longer moving. Every
  row now carries its canonical id from the moment it is parsed, so a finished
  batch really finishes.
- **The next batch replaces the previous one.** When a batch has been walked the
  list moves on — Server 1…120 is followed by Server 121…240, and the numbering
  keeps counting up. Rows that PROVED working stay (pinned at the top) and are
  already saved to My Configs the moment their verdict lands, so rotating can
  never lose a working config and nothing is ever repeated.
- **No row is marked unreachable without a real probe.** v10.4 painted rows red
  from the cheap socket wave alone, and the port prefilter painted a row red
  purely because its port was not one the profiler had seen `cloudflare.com`
  serve — a batch of 60 configs on port 23576 + 60 on 443 was painted exactly
  50 % red in one instant, deterministically. Every row of the batch now goes
  through the real ladder (TCP gate → real core handshake → real answer through
  the node) and only that verdict can turn a row red. The socket wave still
  orders the work; it no longer decides anything.
- **The probe cap no longer collapses on a dead-heavy batch.** The AIMD
  controller was fed the CONFIG's own timeouts (`TCP_TIMEOUT` / `NO_EGRESS`) as
  if they were pool pressure, so on a public feed the deep-probe cap halved on
  nearly every round until it sat at 2 — that is what made a sweep crawl for
  30+ minutes and time rows out before they were ever probed. Only real pool
  starvation feeds the controller now, and the memory-pressure latch needs a
  real heap spike (85 %, releases at 75 %) instead of the 75 % resting line.
- **A starved probe never paints a config dead.** The per-row outer wall no
  longer includes the wait for a probe permit, and a row that produced no
  verdict twice goes back to untested ("tap PING to test") instead of being
  shown as unreachable — a probe that never ran is not evidence about a node.
- **The 100 KB confirmation is measured correctly.** The bundled core performs
  two sequential downloads and returns the faster one's duration; the app was
  dividing by the wall time of BOTH plus the core start, so every node was
  scored at roughly half its real speed and the 50 KB/s bar silently demanded
  ~100 KB/s — live nodes were rejected on slow links. The native duration is
  used now, and a confirmation that was cut by the budget is inconclusive
  instead of a rejection.
- **The confirmation reuses the row's TCP gate**, and a TCP timeout during
  confirmation keeps the earlier real answer (the node answered end to end
  seconds ago; a fresh dial that times out is the socket, not the node).
- **The ping service leads with Cloudflare again.** The DNS-free
  `1.1.1.1/cdn-cgi/trace` is the first URL of the ladder — it needs no resolver,
  so no poisoned DNS can sit between the node and the number — and your selected
  service is the second. AUTO never picks a Google endpoint by itself any more.
- **The bank walk is more forgiving.** A bank that answered with an error page
  is retried instead of being retired for ever; the 30-day reset re-opens every
  bank; and a batch is remembered as seen the moment it is drawn, so a config
  can never come back under a second name after a restart.
- **The list always moves on.** The untested backlog is consumed as it is walked
  (it used to be re-carried for ever, which stopped new batches from ever being
  drawn), a slow partial batch is merged instead of replacing the list, and a
  rotation never runs while the app has no status information to protect.
- **Deleting a config from the list now sticks.** The delete write used to union
  the row back in, so a deleted config reappeared on the next reload.
- **A green row stays green across a restart.** When the confirmation could not
  produce its own verdict, the sweep keeps the earlier real answer — and now
  persists it, so the row no longer comes back as "unreachable" after a restart.

versionCode 86 / versionName 10.5. Same signing key as v10.4, so it installs
over the existing app.
