# Phase 4 · Wave 3 — Options OI Statistics (manual test)

Faithful build of oipulse **Options OI Statistics** (`docs/oipulse-study/options/oi-statistics.md`).
Route `/options/oi-statistics` (Options → OI Statistics). **Zero backend** — folded entirely from feeds
already on the wire (`/oi-stats`, `/oi-analysis`, `/trending`); no new endpoint, no contracts recapture.

## Pre-req
Mock or live stack up, signed in. Pick an underlying + expiry **with captured chain snapshots** (mock
accrues from boot; live needs the capture run). PCR-vs-price needs ≥2 trending buckets.

## Steps
1. Open **All Menu → Options → OI Statistics**.
2. Select **Name** (e.g. `NIFTY BANK`) + **Expiry** (nearest), click **Go**.
3. Header strip shows **PCR · Max Pain · Total Call OI · Total Put OI · Last updated**.
4. **Cumulative OI** (left): two bars — Call OI (green) vs Put OI (red).
5. **Individual OI** (right, wider): per-strike grouped bars (Call green / Put red), an **ATM** dashed
   marker line on the spot strike, dataZoom opened centred on ATM.
6. **PCR vs Price** (full width below): dual-axis line — PCR (accent, left axis) vs price (warn, right axis).
7. Toggle **Show Chg. in OI** → both bar charts swap absolute OI for interval ΔOI (titles gain "(Chg.)");
   bars can go negative under the change view.
8. Empty state: an underlying/expiry with no snapshot shows "No OI snapshot…" and the metric strip dashes.

## Acceptance (vs study doc)
- Cumulative = Σ CE vs Σ PE OI; Individual = per-strike CE/PE; **Call green = resistance, Put red = support**.
- Max Pain in the header is the **server-side** `MaxPainCalculator` value (not re-derived in the FE).
- PCR series = `peOi/ceOi` per bucket against spot — matches oipulse `inPcr` vs `inClose`.

## Known divergences (documented, not bugs)
- **ATM marker** is a dashed markLine labelled "ATM", not oipulse's green-▲/red-▲ double arrow.
- **"Select Period"** (Full-day / last-N-min window) is not a separate control; window cadence rides the
  shared Interval. Full-day behaviour is the default.
- **Underlying DH/DL/DO quote strip** is not surfaced (`/oi-stats` serves OI totals, not underlying OHLC —
  the same cross-page header gap noted for the other OI pages).

## Value-verify (gated)
Cell-for-cell value parity vs oipulse on a real historical session is gated on the **data-foundation
milestone** (Upstox activation + backfill), same as Wave-1/Wave-2 — see §20.8 / the milestone plan.
