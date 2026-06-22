# Phase 4 · Wave 3 — Trending OI - PA (manual test)

The oipulse **Trending OI - PA** (`docs/oipulse-study/options/trending-oi-pa.md`), React route
`/options/trending-oi-pa`, mega-menu **Options → Trending OI - PA**. The Trending OI table **plus
price-action columns** — the summed Call/Put premium and its deltas, so you see whether OI shifts are
confirmed by premium expansion/contraction.

## What was built
- **BE (small add)** `OiTrendingService.TrendPoint` now also carries `ceLtp` / `peLtp` — the **summed CE/PE
  premium** (option ltp) across the captured chain per bucket, from the SAME `reader.series()` read. The
  `/trending` typed response gains two additive fields (the existing Trending OI page ignores them).
- **FE** `foldTrending` extended (`TrendingRow`) with **Total Call/Put Ltp** + **Call/Put Ltp chng** + the
  **CE+PE straddle change** (Δ premiums vs the session-open baseline; `addDecimal`/`subtractDecimal`, exact
  decimal, never parseFloat). New `TrendingOiPaPage` renders the Trending OI columns + the 5 premium columns.

## Preconditions
- Stack up; sign in. Forward-only options snapshots: before capture accrues the table is empty.

## Steps
1. Open **Options → Trending OI - PA**.
2. Pick an underlying + expiry; Interval **5 min**; press **Go**.
3. Verify the full Trending OI table (Date · Time · LTP · Day H/L Break · Chng Call/Put OI · Diff · Direction
   · Chng Direction · Net PCR · Sentiment) **with five extra premium columns** between Chng-Direction and
   Net PCR: **Total Call Ltp · Call Ltp chng. · CE + PE Ltp Chng. · Put Ltp chng. · Total Put Ltp**.
4. The Δ premium columns are signed (green +, red −); the **CE+PE Ltp Chng** = Call Δ + Put Δ (the straddle
   premium change — the key PA confirmation).
5. Confirm the OI columns + Sentiment match the existing **Trending OI** page for the same selection (same
   feed, same fold) — this page only ADDS the premium columns.

## Faithful divergences (documented)
- **Premium summed over the whole captured chain** (not oipulse's auto-selected ~15-strike band) — the same
  band the existing Trending OI OI-columns already use; consistent, documented.
- Δ baseline = the **session-open** (first captured) bucket (forward-capture), not the prior-day EOD — the
  same reduced-history divergence as Trending OI.
- Interval set 3/5/10/15/30/60 (no 1m).

## Verify (green at build)
`Push-Location frontend-react; npm run lint; npm run test:ci; npm run build`
BE: `mvnw -pl services/market-data-service -am test -Dtest='OiTrendingServiceTest,OptionsAnalyticsControllerIntegrationTest'` (no regression — additive fields).
Contract: recaptured (`TrendPoint` typed record gains ceLtp/peLtp) + TS regen — additive, ci-contracts WARN.
