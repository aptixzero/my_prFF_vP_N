# Professor VPN v12.4

## The sweep walks the list in order, one config at a time — no queue, no sleep, no freeze

- The sweep is rebuilt as one ordered pass: row 1, row 2, … row N, start to
  finish, with no queue, no sleep and no cooldown between pings. The moment a
  worker finishes a config it takes the NEXT one.
- Fixed the freeze at ~4 configs / 3%: the sweep no longer queues for a
  probe-pool permit (the old 3 s admission lane starved most rows and halved
  the pool's width on every starvation), and it no longer abandons the native
  core call at 8 s — each abandoned call kept a full Xray core running, and
  four workers piled up enough cores to lock the phone. The sweep now waits
  for the core's own bounded answer (up to 25 s) and runs exactly as many
  workers as the device can carry (two on a low-end phone, four on an
  eight-core phone; the settings pin still wins).
- Every config now ends with a REAL result: a latency, or an unreachable
  verdict with the real failure reason. Rows are no longer left silent.
- When the list is finished it is replaced: the finished generation is
  remembered, the next batch is fetched, and the new list is rotated in
  (working configs are kept). The sweep continues from row 1 of the new list
  — it never stops on its own; only STOP ends it.
- A lost connection pauses the sweep and resumes when the link returns.

versionCode 105 / versionName 12.4. Same signing key as version 12.3.
