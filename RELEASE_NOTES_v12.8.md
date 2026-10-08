# v12.8 — the Free list actually shows the batch it just fetched

**versionCode 108 · built and tested on CI (see `.github/workflows/release.yml`), not claimed from an un-run build**

> This release is small on purpose. The last several versions (v11.x–v12.7)
> each reported fixing "fake ping" / "Free list stays empty" / "freezes", and
> each time the next release reported the same symptoms. Rather than add
> another unverified round of the same claim, this release makes exactly two
> changes, both with a concrete, readable root cause, and leaves everything
> else untouched. See "What this release deliberately does NOT do" below.

## 1. The actual bug behind "Start Search finishes, the Free list is still empty"

**Root cause, found by reading the code, not guessed:**
`FreeConfigsFragment.reloadFromStore()` was the ONLY code path that ever
copies rows from `FreeConfigStore` into the visible adapter — the initial
`onViewCreated` population is a one-time snapshot taken before any search has
run. `reloadFromStore()` has an early-return when `adapter.freezeOrder` is
true (added in v12.7, to stop a sweep's live pin-to-top/sink-to-bottom row
moves from being clobbered by a full re-sort). But `freezeOrder` flips true
the moment the sweep is reported running — which is also the moment the
engine hands off the first (or next) batch of configs into the same store.
So the only code path that could show the new batch was skipping itself,
every time, because by the time it ran the sweep already "looked" like it was
mid-run. The store genuinely had the 120 configs; the screen never asked for
them.

**Fix:** when `freezeOrder` is true, `reloadFromStore()` now diffs the store
against what's already shown **by id** and appends only the rows that are
genuinely new (`FreeServerAdapter.appendItems`, a plain `notifyItemRangeInserted`
with no reorder), instead of skipping entirely. Rows already on screen are
not touched — the v12.7 pin/sink behavior this guard was protecting is
unaffected, because that logic only ever moves rows that are already in
`adapter.items`.

**Why this plausibly explains "fake ping" too, not just "empty list":** if
this is the first time this exact bug has been looked at this way, what the
previous few releases' testers were likely looking at was **not** the fresh
batch at all — it was whatever stale rows were already in the store from an
earlier session, re-colored by the ping-status collector (which does run
independently of this bug). A wall of old, likely-dead servers all showing
some ping number while the real, fresh, untested batch sat invisible in the
store would look exactly like "it shows ping for everything, but nothing
connects." This is a hypothesis the evidence supports, not a verified
conclusion — see the testing note below.

## 2. Notification text was Persian in the English string set

`app/src/main/res/values/strings.xml` (the default/English resource file —
confirmed English everywhere else in that file) had `autotest_notif_title`
and `autotest_service_text` copy-pasted verbatim from `values-fa/strings.xml`.
Any device not running the in-app Persian override saw a Persian foreground
notification. Fixed to plain English in the default file; `values-fa` is
untouched and still correctly Persian.

## 3. What this release deliberately does NOT do

- **Does not touch the ping/connect core** (`Pinger`, `MeasurementEngine`,
  `XrayManager`, the probe ladder). `AI_AGENT_GUIDE.md` §0–3 and §9e document
  real, specific invariants here (no `Random()` in any stat path — checked,
  zero hits outside `SecureRandom` used for TLS ClientHello padding; ping
  path must equal connect path — found intact). No concrete, readable bug was
  found in this layer this pass. If configs still don't connect after this
  fix on a build that genuinely shows the fresh 120, that is a different,
  real bug in the probe/connect layer and deserves its own investigation with
  actual device logs — not another guess.
- **Does not change dedup, pagination/rotation, or the Stop button.** All
  three have specific, deliberate-looking implementations already
  (`FreeListMerge.canonicalId`, the batch-rotation logic in
  `AutoTestEngine`, `SweepController.stopAll`). No concrete bug was found in
  any of them this pass.
- **Does not add per-site/per-app traffic logging.** The current registry
  design (`AI_AGENT_GUIDE.md` §5ad "Registry") is explicit that this is
  deliberate: *"Privacy is structural: no IP/MAC/Android-ID/serial/location/
  account, no hosts/domains/apps/traffic content — in the app AND in the
  panel API."* Logging which sites/apps a user reaches through the VPN is a
  direct reversal of that, for an app whose users are specifically relying
  on it to evade network surveillance. That is a deliberate product/ethics
  decision for the project owner to make explicitly, not something to fold
  into a bug-fix release.

## 4. Testing

JVM unit tests run for real on CI (`.github/workflows/release.yml`), not
claimed from memory. **Not verified: real-device, real-network behavior** —
no Android SDK or emulator is reachable from the environment this patch was
written in. Please confirm on a real device that (a) the Free list populates
with the full batch immediately after Start Search, and (b) check whether
configs that show a ping now actually connect. If (b) still fails on a list
that is genuinely the fresh batch (not stale rows), that confirms a second,
separate bug in the probe/connect layer worth investigating on its own.

## 5. Build

- `versionCode 108`, `versionName "12.8"`.
- Same release key as v6.7–v12.7 (certificate SHA-256
  `6a5ed5e32014ee77b41ca9ef9c71c5ab3397156d25fe22c7f1d52bb8907eb82d`) —
  CI fails the build rather than publishing anything if this ever changes.
- Built by `.github/workflows/release.yml` on push of tag `v12.8`, not by a
  local or sandboxed `./gradlew`.
