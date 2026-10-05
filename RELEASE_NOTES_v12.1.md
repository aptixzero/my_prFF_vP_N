# Professor VPN v12.1

## Stable, bounded ping sweeps

- START SEARCH now tests one immutable Free Config generation from start to finish.
- The ping engine no longer sleeps, cools down, fetches another batch, or replaces the visible list mid-sweep.
- Progress has one fixed total and never renders an intermediate `0/0` ping state.
- Probe-pool admission timing no longer cuts off a real probe after it has acquired a slot.
- Native wrapper timeouts are inconclusive and are never persisted as dead-server verdicts.
- My Configs uses a moderate shared worker width and remains in stable order until the sweep completes.
- Single-row PING always performs a fresh real measurement.
- User-deleted My Configs have durable tombstones, so hydration and automatic promotion cannot recreate them.

versionCode 102 / versionName 12.1. Same signing key as version 12.
