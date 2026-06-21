# Phase 4 · Wave 3 — Active Strikes OI (manual test)

Faithful build of oipulse **Active Strikes OI** (`docs/oipulse-study/options/active-strikes-oi.md`).
Route `/options/active-strikes` (Options → Active Strikes OI).

**Small BE add** (the only Wave-3 page needing one): the existing `/active-strikes?buckets=N` already
served the RIGHT chart (Sentiment % series); this adds the LEFT chart's data — the active strike's Call
and Put OI per bucket — as a second `@JsonInclude(NON_NULL)` series on the SAME response, folded from the
SAME `reader.series(...)` read and the SAME per-bucket active-strike selection. No new endpoint, no new
query param, no new DB read, no migration. Additive contract (re-captured + TS regen'd in this PR).

## Pre-req
Mock or live stack up, signed in. Pick an **index** + **expiry** with captured chain snapshots (≥2 buckets
for a line).

## Steps
1. Open **All Menu → Options → Active Strikes OI**.
2. Select **Name** (e.g. `NIFTY BANK`) + **Expiry** + **Interval** (3/5/10/15/30/60), click **Go**.
3. Header strip: **Sentiment % · Active strikes (list) · Last updated**.
4. **Active Strike Change in OI** (left): Call OI (green line) + Put OI (red line) over the session.
5. **Active Strike Sentiment %** (right): a blue line, dashed zero baseline; can swing strongly negative
   when calls dominate (bearish).
6. Both charts share the same time axis (both fold the same buckets).
7. Empty state: an index/expiry with no snapshots shows "No active-strike series…".

## Acceptance (vs study doc)
- Server picks the active strike **per bucket** (top-N peak-OI), reused identically by both charts — so
  the OI lines and the sentiment line agree bucket-for-bucket (BE unit test asserts the per-bucket
  reselection with a multi-strike fixture where the peak strike flips).
- LEFT chart = CE OI + PE OI per timestamp (study's `obOiData:[{CE},{PE}]` row shape).
- `buckets`-absent response stays byte-identical (NON_NULL omits both series — existing IT asserts it).

## Known divergences (documented, not bugs)
- **Expiry picker shown** — our endpoint keys on expiry; oipulse hides it and server-picks across the
  chain. We require an expiry selection.
- **Active strike = aggregate of top-N** (D4 default 5) peak-OI strikes, not a single peak strike; the
  LEFT lines are the active-set aggregate (consistent with how the sentiment series is computed).
- **Sentiment sign convention** is flow-based `100·(ΣΔpe−ΣΔce)/Σ(ce+pe)OI` (pre-existing, shared with the
  sentiment series) vs the study's level-based `(ΣPut−ΣCall)/ΣPut·100`.
- **NSE-indices-only** scope is an oipulse coverage caveat; we serve whatever index has captured snapshots.

## Value-verify (gated)
Cell-for-cell parity vs oipulse on a real session is gated on the data-foundation milestone (Upstox
activation + backfill), same as Wave-1/Wave-2 — see §20.8 / the milestone plan.
