# Phase 4 · Wave 3 — Active Strikes IV (manual test)

Faithful build of oipulse **Active Strikes IV** (`docs/oipulse-study/options/active-strikes-iv.md`).
Route `/options/active-strikes-iv` (Options → Active Strikes IV). IV counterpart of Active Strikes OI.

**Small BE add** — a 3rd `@JsonInclude(NON_NULL)` series (`activeStrikeIvSeries`) on the SAME
`/active-strikes?buckets=N` response, folded from the SAME `reader.series()` read that already feeds the
sentiment + OI series. `StrikePoint` already carries `iv` + `spot`, so **zero new DB read**, no new
endpoint/param. IV is per-strike and **unsummable**, so the series carries the **single peak-OI strike's**
CE/PE IV + price (vs the OI page's top-N aggregate). Additive contract recapture + TS regen this PR.

## Pre-req
Mock or live stack up, signed in. Pick an **index** + **expiry** with captured chain snapshots that carry
IV (greeks pipeline must have populated `iv`).

## Steps
1. Open **All Menu → Options → Active Strikes IV**.
2. Select **Name** + **Expiry** + **Interval** (3/5/10/15/30/60), click **Go**.
3. Header: **Call IV · Put IV · IV skew (P−C) · Last updated**.
4. Single **dual-axis line chart**: Call IV (green) + Put IV (red) on the LEFT axis, Price (orange dashed)
   on the RIGHT axis, x = time buckets. Put IV typically above Call IV (put skew).
5. Empty state: an index/expiry with no IV snapshots shows "No active-strike IV series…".

## Acceptance (vs study doc)
- Single dual-axis line — Call IV green / Put IV red (left) + Price orange dashed (right).
- Server picks the **single peak-total-OI strike per bucket** (ties → lowest strike, deterministic) and
  emits that strike's CE IV, PE IV, spot (BE unit test asserts the peak reselection + tie-break).
- `buckets`-absent response stays byte-identical (all three series NON_NULL-omitted — IT asserts it).
- IV/price are decimal strings end-to-end; only the chart coordinate crosses to number.

## Known divergences (documented, not bugs)
- **Single peak strike for IV** (rank-1), not the OI page's top-N (default 5) aggregate — IV cannot be
  summed across strikes. The IV strike equals the OI page's rank-1 strike on virtually every bucket.
- **Expiry picker shown** — our endpoint keys on expiry; oipulse hides it and server-picks across the chain.
- **NSE-indices-only** is an oipulse coverage caveat; we serve whatever index has captured IV snapshots.
- Peak strike chosen by **total OI** (our captured selection field); oipulse's exact activity metric is
  unspecified.

## Value-verify (gated)
Cell-for-cell parity vs oipulse on a real session is gated on the data-foundation milestone (Upstox
activation + backfill), same as Wave-1/Wave-2 — see §20.8 / the milestone plan.
