# S24-PRUNE — backlog gaps that automate an S24-dropped rule

> **ARCHIVED (2026-07-03 doc sweep):** historical planning doc — the work here is delivered, superseded, or consciously parked. Anything still open lives in `../2026-07-02-remaining-items.md` (ledger) or `../2026-07-03-10x-value-roadmap.md`. Do not mine this file for TODOs.


Status: PRUNE MAPPING (W2 backlog-prune). Owner: single-owner. Date: 2026-06-27.

## What this is

The S24 ratification (`docs/strategy-audit/RATIFICATION-PACK.md` Part 1) **DROPPED 72 rules** from the
operative strategy doc (every Part-1 row defaults to DROP; rows 13/24/46 are the annexed borderline rows,
treated here as **NOT dropped**). This doc cross-checks each of those 72 drops against the **12 backlog
stream plans** in this directory plus the two follow-up plans
(`../2026-06-27-followup1-expand-manual-checks.md`, `../2026-06-27-followup2-soft-dots-to-hard-gates.md`)
and flags any backlog **gap / work-item** that would *build a rule S24 deliberately dropped*.

**Headline finding: the vast majority of the 72 drops are pure DOC bloat** — illustrative figures,
session-wrapper headings, terminology primers, anecdotes, changelog ledgers, dead-feed plumbing the owner
already replaced — with **NO backlog match**. Only a small set of backlog gaps automate a dropped *rule*.

- **66 of the 72 drops have NO backlog match** (doc-only: glossaries, anecdotes, R:R/overshoot/weightage
  figures, status/changelog metadata, the FU1/FU2/backlog wrapper rows themselves, etc.).
- **6 drops map onto a backlog gap.** Of those gaps, **3 are clean DESCOPEs**, **3 are KEEP-PARTIAL**
  (the gap also serves a KEPT rule — kept, partial noted), and **2 gaps are UNCERTAIN** (flagged, kept).
- **Dead-feed drops (#37 OSPL-3:20 / #43 OSPL-AI / #44 Trending-OI+PA) have NO backlog gap** building
  them — confirmed across all 14 files. The owner's U7/U8 ruling ("instead of OIP-AI we have our own OI
  confluence gate, already implemented") means the OI-confluence work is the KEPT replacement, not the
  dropped feed. Nothing to descope.

## Mapping table (only rows that HAVE a backlog match)

| Drop# | Dropped rule (quoted from RATIFICATION-PACK P1) | Matching backlog gap (file + section) | Decision | Reason |
|---:|---|---|:--:|---|
| 25 / 62 / 74 | "strike housekeeping ATM±7" / "60-min + 15m=5×3m + ATM±7" / "§4.14.6 OI-interval ladder + ATM±7" (S24 KEEPS 5-15min; DROP only 60m/equivalence/ATM±7) | `strike-premium-selection.md` §3.2 `dynamic-strike-recenter` (L373) | **DESCOPE** | Encodes the exact dropped S21(e) rule: keep window if move <1%, **reset to ATM±7** on a >1% move (`recenterWidth` default 7). The ATM±7 strike-housekeeping recenter is the dropped sub-rule; S24 keeps only the 5-15min interval, not the ATM±7 re-centre. |
| 25 / 62 / 74 | "…+ ATM±7" (the same dropped ATM±7 recenter, OI-stream copy) | `oi-fidelity-gates.md` P13 `trending-oi-strike-window` (table row L54; design `### M7` P13 block L385-396; PARITY row L429) | **DESCOPE** | P13 is the OI-stream's "reset to ATM±7 on >1% move" — same dropped strike-housekeeping recenter as above. The plan already marks it deferred/Open-Point-9; descope removes it from the automatable backlog. (M7's 5-15min interval read = KEPT; only the ATM±7 P13 sub-package drops.) |
| 26 / 63 | "direction-change arrows" (S5 Trending-OI / Shared-S3 OI tool-UI primitive) | `oi-fidelity-gates.md` M5 `oi-direction-change-arrow` hard veto / package P5 (`### M5` L307; table row P5 L46) | **DESCOPE** | M5 builds an OI "direction-change arrow" (slope-sign flip) veto — the literal dropped tool-UI primitive (Rec #5). No KEPT rule depends on it (the KEPT confluence uses the cross + quadrant + sentiment level, not the arrow). |
| 6 | "S22 premium bands 250-550/150-350" (premium-band numeric debate; the premium-FLOOR avoid<130/prefer150+ is SHARED/KEPT) | `strike-premium-selection.md` §3.1 `strike-premium-band-backtest` (L191) incl. Step 4 "S22-operative band swap" (L348) + Open Point 2 (L636) | **KEEP-PARTIAL** | The band-aware backtest selector + the live S22-band swap automate the dropped numeric bands (N 150-350 / BN 250-550). BUT the same package also implements the KEPT premium-FLOOR / ATM-ITM-only selection (avoid<130, prefer 150+, not-deep-OTM) that live `StrikePicker` needs for parity. KEEP the package; the dropped half is only the specific lo/hi band numerals — tune those, don't build them as fixed dropped values. |
| 18 | "New-High/Low panel" (S3 Market Movers display primitive) | `stock-universe-market-movers.md` §3.1 `equity-fno-universe-screener` — the `newHighMaker`/`newLowMaker` fields (`ScreenerRow` L231-232; narrative L211-212, L240-242) | **KEEP-PARTIAL** | The `newHighMaker`/`newLowMaker` panel feed is the dropped #18 New-High/Low display panel. BUT it is two fields inside the otherwise-KEPT Market-Movers screener (N-day breakout / OH-OL / OI-interpretation / daily-RSI / liquidity are all KEPT, futures-only). KEEP the screener; the two panel fields are the only dropped sliver — leave them but do not treat the panel as a load-bearing deliverable. |
| 25 / 62 / 74 + (60m) | "60-min … read" half of the dropped OI-interval ladder (S24 KEEPS 5-15min, DROPS the 60m equivalence) | `indicators-supertrend-volume.md` §3.3 `multi-timeframe-supertrend` — the 15m-vs-60m ST-agreement leg (L213) | **KEEP-PARTIAL** | The 15m Supertrend confirmation is a KEPT adopted primitive; only the agreement dot's reliance on a 60m bias brushes the dropped 60m/equivalence half — and the plan already hives the OI-window 15m-vs-60m half off to other streams. KEEP; partial 60m reliance noted, not load-bearing. |
| 5 | "IV Desirable (rising bull / falling bear)" (S1 filter — per-strike IV-direction desirable) | `strike-premium-selection.md` §3.3 `per-side-premium-skew` (L419) — and adjacent `iv-fidelity.md` §3.A.1 `iv_slope` dot (L168) | **UNCERTAIN** | Drop #5 is the per-strike "rising-IV-bull / falling-IV-bear" Desirable. The `per-side-premium-skew` dot + the `iv_slope` dot both encode a per-side IV/premium-direction read that overlaps #5. BUT #5 is ambiguously a *Desirable* (may survive as a KEPT soft cue, and the IV automations also serve the KEPT 7-10pt IV band / IV-rank framework). KEEP and flag UNCERTAIN — do not descope without an owner ruling on whether the per-strike IV-direction Desirable is dropped or retained as a soft dot. |
| 39 | "RSI >60/<40 example bands" (DROP the example bands; KEEP the directional not-overbought>75/oversold gate) | `rsi-multi-timeframe.md` §3.7 `post-vertical-rsi-recovery` (L386) — the "recover toward ~40" numeric | **UNCERTAIN** | The recovery-to-~40 sequencer brushes the dropped <40 example band. BUT it is an oversold-recovery *sequencing* mechanism (trend-change Day-07), not a static entry band, and the rest of the RSI stream automates the KEPT 75/25 not-overbought framework + per-strategy override. KEEP and flag UNCERTAIN — the ~40 is a recovery waypoint, likely KEPT, but worth an owner glance. |

## Files touched by a DESCOPE marker

In-place `> **DESCOPED …**` markers are added ONLY for the 3 clean DESCOPE rows above:

1. `strike-premium-selection.md` — §3.2 `dynamic-strike-recenter` (ATM±7, drop #25/#62/#74).
2. `oi-fidelity-gates.md` — §M5 `oi-direction-change-arrow` (drop #26/#63) **and** §M7 P13
   `trending-oi-strike-window` (ATM±7, drop #25/#62/#74).

The 3 KEEP-PARTIAL and 2 UNCERTAIN rows get **no in-place marker** (per the task rule: only descope a gap
that builds a dropped rule and serves no kept rule). They are recorded here for the owner.

## Summary count

- **72 dropped rules checked** against 12 backlog streams + FU1 + FU2.
- **66 / 72 had NO backlog match** (pure doc bloat: glossaries/primers #56-59, R:R/overshoot/weightage
  figures #15/#16/#21, anecdotes #4/#31-34, status/changelog/evolution ledgers #52/#69/#70, the FU1/FU2/
  backlog wrapper rows #71/#72/#73, the §4.17 "Additions" wrapper #68, lot-size/expiry-day tables #75,
  sell-only-hedged #53, dead OSPL/OIP-AI feeds #37/#43/#44, etc.).
- **6 / 72 mapped onto a backlog gap.**
- **3 gaps DESCOPED** (in 2 files): `dynamic-strike-recenter` (strike-premium-selection.md),
  `oi-direction-change-arrow` (oi-fidelity-gates.md M5), `trending-oi-strike-window`/P13
  (oi-fidelity-gates.md M7). All three build the dropped ATM±7 recenter (#25/#62/#74) or the dropped
  OI direction-change arrow (#26/#63), serve no kept rule, and are tag-gated/deferred default-OFF — so
  descoping them removes dead-rule build work without touching any shipped behaviour.
- **3 gaps KEEP-PARTIAL** (serve a dropped + a kept rule): `strike-premium-band-backtest` (premium
  FLOOR is kept; only the dropped band numerals), `equity-fno-universe-screener` New-High/Low fields
  (screener is kept), `multi-timeframe-supertrend` (15m ST is kept; only the 60m-equivalence brush).
- **2 gaps UNCERTAIN** (kept + flagged): `per-side-premium-skew` / `iv_slope` (drop #5 IV-Desirable
  ambiguity), `post-vertical-rsi-recovery` (drop #39 ~40 band brush).

## Leave the FU1/FU2 plans intact?

**Yes — FU1 and FU2 are left fully intact.** The S24 comparison flags FU1 (#71), FU2 (#72) and the backlog
(#73) as *derived-automation* to **exclude from the strategy DOC** — i.e. they don't belong in the operative
strategy prose. That is a doc-scope ruling, NOT a directive to delete the engineering plans. FU1's 9 manual
checks and FU2's 4 soft-dot→hard-gate promotions were each scanned: **every FU1 check and every FU2 promotion
automates a KEPT rule** (FII L/S, constituent contribution, VIX-regime bands, 50/50 OI + PCR ladder,
indicator-alignment, OI quadrant, breadth, basis — none builds ATM±7, an OI direction-change arrow, the
200/300% OI-spurt extreme, or a dead OSPL/OIP-AI feed). The single UNCERTAIN inside FU1 is `iv_crush_awareness`
(expiry-day IV-decay reminder, brushes end-of-series #29/#64) — left intact and flagged, not descoped.
**No FU1/FU2 item is descoped.**
