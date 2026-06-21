# Phase 4 / Wave 1 — consolidated deferred-items ledger

**Purpose:** ONE tracked list of every Wave-1 item not yet shipped, so the end-of-wave audit is a
maintained ledger, not a from-scratch sweep (owner concern, 2026-06-21). Each page's full context lives
in its `docs/manual-tests/phase-4-wave1-<page>.md`; this rolls them up by bucket + unblock-condition +
target PR.

**Wave-1 status:** all pages (Options Chain, OI Spurt, OI Analysis, Straddle/Strangle, Connecting Dots,
VIX header) are **structurally built + live-QA'd cell-for-cell vs oipulse**. The items below are the
documented remainder. Only **Bucket 5** is data/Upstox-related; the rest is our own code, plan-sequenced
decisions, or intended divergences.

**Two axes (keep separate):** *feature-completeness* (code — Buckets 1–3) vs *value-verification* (data —
Bucket 5). Sequencing decision (owner, 2026-06-21): **finish PR-W1, then a dedicated Upstox/historical
"data foundation" milestone** before Wave 2/3, then value-verify every data page on known historical
sessions.

---

## Bucket 1 — Intended / permanent divergences (decisions, NOT gaps — no work owed)

| Item | Page(s) | Why it stays |
|---|---|---|
| black76 greeks (not oipulse server greeks) | Chain | parity §17.9 — our exact-recompute path |
| `+` prefix on positive deltas | Chain · OI Spurt · OI Analysis | a11y — sign not colour-only |
| ring badge vs solid fill (OI-Int) | Chain · OI Analysis | WCAG contrast (CD uses solid fill + `surface-0` glyph) |
| arrow glyph = `surface-0` not white on fills | Connecting Dots | AA contrast in every theme |
| `--ay-*` theming (not oipulse's exact palette) | all | the multi-theme system |
| header labels drop the Call/Put prefix | OI Analysis | the CALL/PUT colgroup carries it |
| zero-change strike → Long Unwinding bucket | OI Spurt | convention (oipulse treats 0 as down) |

## Bucket 2 — Our-own backend wiring (→ PR-W3 / W1-polish; Upstox-irrelevant)

| Item | Page(s) | Unblock | Target |
|---|---|---|---|
| **Underlying-quote header** (chg% · DH · DL · DO · timestamp) | Chain · Straddle · OI Analysis · OI Spurt | wire the underlying quote day-OHLC + prev-close (BOTH already exist — the VIX header proves the pattern) into the feeds — **ONE fix unblocks 4 page headers** | PR-W3 |
| IV Chng optional col | Chain | add an IV-delta field to `/chain-table` | PR-W3 |
| O=H / O=L optional cols | Chain | join `strike-session-stats` (already built) | PR-W3 |
| Premium / Combine-Premium optional cols | Chain | derive from our data | PR-W3 |
| Interval set Full-Day / 2h / 4h / custom-time | Chain · Straddle · OI Analysis | `OiInterval` extension (10m DONE) | PR-W3 |
| Grouped Name select (Index / Stocks headers) | Chain (shared FilterBar) | FilterBar option-grouping | W1-polish |
| Strike-col tan bg · stronger ATM · max-cell filled highlight | Chain | CSS polish | W1-polish |
| Rows-per-page selector (7/10/15/30/50/All) | OI Spurt | config dropdown | W1-polish |
| EOD label (`15:30-EOD`) | OI Analysis | label tweak | W1-polish |
| Hide the interval selector (oipulse has none here) | OI Spurt | FilterBar `showInterval={false}` | W1-polish |
| Search debounce 300ms | OI Spurt | minor | W1-polish |

## Bucket 3 — Chart widgets (→ PR-W4 / later; Upstox-irrelevant)

| Item | Page(s) | Unblock | Target |
|---|---|---|---|
| Strike-click chart sub-view | Chain | openalgo-chart adoption | PR-W4 |
| Chart optional column | Chain | openalgo-chart | PR-W4 |
| "Strategies" sub-tab (payoff builder) | Straddle | a separate page | later |
| "Tool" sub-tab | Connecting Dots | a separate page | later |

## Bucket 4 — Server-secret approximations (tunable, never exactly matchable)

| Item | Page(s) | Note |
|---|---|---|
| Composite weights + per-factor raw→enum cutoffs | Connecting Dots | oipulse's exact rule is server-side; ours = the study's fitted net→trend + reasonable bands. Tunable vs real data, never bit-exact. |
| D.H/L break from bucket **close** not true OHLC | OI Analysis | we capture point-in-time snapshots, not intraday bars. Improves once per-bucket OHLC is captured (see Bucket 5). |

## Bucket 5 — Data availability / value-verification (the Upstox "data foundation" milestone)

This is the ONLY bucket Upstox/historical-data touches — and the source of the "built but not
value-verified" debt. Plan authority: §21 (Kite-vs-Upstox routing) + §18 (OpenAlgo) — Upstox Plus is the
ONLY source for expired-contract OI history (ExpiryTrack) and provides global instruments (Dow).

| Item | Page(s) | Unblock |
|---|---|---|
| **Dow Jones** factor (currently Neutral) | Connecting Dots | Upstox global instruments — the one clearly data-blocked feature |
| Active-Strike IV/OI · FutOi factors (Neutral w/o capture) | Connecting Dots · Chain deltas | live OI capture (forward) OR ExpiryTrack historical OI (past dates) |
| Straddle & CD candle **values** (mock-synthetic off-hours) | Straddle · Connecting Dots | a valid broker historical session (Kite on-demand / Upstox depth) |
| Intraday VIX history | Connecting Dots · Chain header | INDIA VIX 1m candles (Kite ticker capture) |
| Per-bucket option **OHLC** (true D.H/L break) | OI Analysis | capture OHLC bars, not just snapshots |
| FINNIFTY / MIDCPNIFTY indices | Connecting Dots | broker instrument coverage |
| **VALUE-verify every data page** on a known historical session side-by-side with oipulse | all data pages | the milestone's payoff: turns structure-QA into value-QA, clearing the debt in one pass |

---

## How to keep this current

Add a row here whenever a page defers something (don't just bury it in the page doc). Strike a row when
it ships (note the PR). The end-of-wave audit = read this file, not re-derive from 6 page docs.
