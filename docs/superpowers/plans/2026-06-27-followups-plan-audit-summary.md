# Follow-ups 1 & 2 — cross-plan readiness summary

> Status: AUDIT SUMMARY. Date: 2026-06-27. Single-owner. Target service for both:
> `services/strategy-signal-service` (the index-option scalper confluence seam).
> Plans summarised:
> - [Follow-up 1 — expand `ScalperManualChecks`](2026-06-27-followup1-expand-manual-checks.md)
> - [Follow-up 2 — promote soft-dots to hard gates](2026-06-27-followup2-soft-dots-to-hard-gates.md)

Both plans went through a 2-pass independent audit (every cited `file:line` re-opened against the
working tree). This is the consolidated readiness view + the full open-point inventory the owner asked
to have captured in one place.

---

## Readiness verdict (per plan)

**Follow-up 1 — expand `ScalperManualChecks` (manual reminders): SOUND.**
Adds 9 manual-only `Check` records (CHECKS 7 → 16) plus two `hasSize` test bumps and one new
keys-present test — all mechanical, no production FE/DTO/schema/migration/contract change. Parity is
not at risk: `manual_checks` is a static-constant array written onto the V009 `scalper_detail`
side-channel, which `GoldenSignalsJson.write()` never serializes and the deterministic replay never
reaches (independently confirmed at the `GoldenSignalsJson` source in both passes). Pass 1 was
"sound-with-open-points" (1 wrong cite corrected — the stale "FE prepends `§`" claim; 1 parity
re-check); pass 2 cleared it to **sound**, catching one residual `§`-prefix claim pass 1 had left in §3.
Implementation is gated only on the owner settling the final item set (OP-1, which fixes the test
counts) and red-penning copy (OP-4) — neither is a soundness risk.

**Follow-up 2 — promote soft-dots to hard gates (tag-gated): SOUND-WITH-OPEN-POINTS.**
Promotes four soft dots (indicator-alignment, futures-OI quadrant, breadth, basis) to opt-in early-return
hard gates behind new per-strategy YAML tags absent from all 36 shipped configs (default-OFF, every
config byte-identical today). Architecture copies the proven `oi-cross-filter` (#5) template: no
`ConnectTheDotsScorer.score`/`valid` change, no serialized-golden field, gate is LIVE-only. Pass 1 was
"sound-with-open-points" (3 cites corrected: dot count 19→18, CFG-literal count 9→8, WI-4 doc-ref;
1 compile fix — `misalignedPsarBank()` had a non-existent `barOf` factory). Pass 2 stayed
"sound-with-open-points" and caught one arithmetic error both the author and pass 1 missed (aggregate
denominator 20.6 → **19.6**; 15 → 14 unit-weight dots), with no missing data-flow/contract/parity hole.
The residual open points are deferrable owner design choices, not blockers.

---

## Consolidated open points (all, both plans)

### Follow-up 1 (manual checks) — 8 open points

- **F1-OP1 — exact item set: 8 vs 9, and trim candidates.** Audit names 8 gaps; plan proposes 9 (splits
  `vix_regime_bands` out from `vix_normal`). Sub-decisions: (a) ship `vix_regime_bands` separately vs fold
  into `vix_normal`; (b) is `oi_intraday_positional` redundant with the automated OI dots. *Default: ship
  all 9.* **This is the gating decision — it sets the final CHECKS count and the test literals.**
- **F1-OP2 — checklist length / grouping UX.** 16 items is long on the ~480px mobile target. Functional as
  flat (mobile accordion + override exist); grouping by automatable/judgement is a deferred FE enhancement.
  *Default: ship flat.*
- **F1-OP3 — context-conditional checks.** Some new items only apply in context (`straddle_vwap_entry`,
  `sensex_participation`, `iv_crush_awareness`). Today the same fixed list stamps every scalper signal.
  Making `appendTo` context-aware is a behaviour change. *Default: ship the fixed list.*
- **F1-OP4 — exact `label`/`assist` wording + docRef granularity.** Strings are drafted from the audit;
  owner (Siva-method author) to red-pen copy and confirm e.g. `4.13` vs `4.17.4` for the FII item before merge.
- **F1-OP5 — docRef target sections (RESOLVED).** All 8 consolidated-doc sections confirmed present; FE shows
  the bare string (no `§`). Items 3 + 8 deliberately share `4.14.5` — flagged for the OP4 wording review.
- **F1-OP6 — stale class javadoc.** `ScalperManualChecks.java:15-16` asserts the FE prepends `§`; it does
  not. Pre-existing wrong text, left in place per CLAUDE.md; recorded so a future reader doesn't trust it.
- **F1-implementer nit — the new keys-present test is also OP-1-coupled.** If the owner trims the set, the
  implementer edits THREE things (both `hasSize` literals + the new test's `.contains(...)` list), not two.
- **F1-downstream (recorded so not lost).** The real automation of these reminders (VIX dot, FII `fiiLongPct`
  dot + §4.13 participant change-in-OI matrix, pre-open feed, Sensex comparator, positional-OI + PCR ladder,
  expiry-IV-crush model, combined-premium-VWAP straddle seam, prev-day-VWAP switch) each touch emitted signals
  and MUST follow the parity-safe-additive convention — home is Follow-up 2 or a plan modelled on it.

### Follow-up 2 (hard gates) — 11 open points

- **F2-OP1 — VIX directional gate.** `MarketOiClient.macro()` passes null VIX → a `vix-gate` tag is a live
  no-op until the feed is wired. *Default: defer to a separate "wire VIX feed" plan.*
- **F2-OP2 — Dow / global-cue dot.** No Dow dot exists; adding one changes the scorer denominator and breaks
  goldens — a dot-addition, not a gate-promotion. *Default: separate plan.*
- **F2-OP3 — fail-open vs fail-closed for `basis-gate` (WI-4).** `futuresBasis` degrades to pass on null, so
  the gate never blocks on derived history. *Default: keep fail-open (matches #5/VIX); strict variant only if
  the owner wants basis to suppress history.*
- **F2-OP4 — new record-field / constructor order.** *Default: append after `requireStraddle` (minimal churn,
  clear "FU2" cluster).*
- **F2-OP5 — PR granularity.** *Default: two PRs (PR-1 = indicator-alignment alone; PR-2 = the three OI/macro
  gates).*
- **F2-OP6 — arming tags on real strategies (PR-3, deferred).** *Default: leave to the owner as a forward-paper
  experiment ("tune on live, not backtest"); keep PR-1/PR-2 behaviourally inert.*
- **F2-OP7 — optional FE "this dot is a hard gate" affordance.** *Default: no FE change for PR-1/PR-2 (payload
  unchanged); revisit with PR-3.*
- **F2-OP8 — positive deterministic (scalper-golden) coverage of the gate.** Not built; would require driving
  the LIVE-only gate through `TickwiseGoldenRunner`. *Default: rely on the seam unit tests (pass/block/unaffected).*
- **F2-OP9 — `close == vwap` edge for WI-1.** Side decision routes `close == vwap` to CE (`>=`) but
  `indicatorAlignment` CE requires strict `gt(close, vwap)`, so the armed gate blocks an exact-equal tick.
  Intended (alignment demands price strictly beyond VWAP), parity-irrelevant; documented, no action.
- **F2-OP10 — gate vs soft-dot read different sources in the seam test.** The WI-1 gate reads the bank-derived
  `chart`; the mocked scorer reads `ctx.chart()`. So the WI-1 fixture can't drop the soft-dot aggregate — the
  "drop one dot below 0.6" worry is a WI-2/3/4 concern only (pass 2 re-derived the real `bullContext()`
  aggregate at 14.8/19.6 = 0.7551; all three WI-2/3/4 non-gated PASS tests clear 0.6 with margin).
- **F2-OP11 (framing, pass 2) — WI-3/WI-4 doc backing is weaker than WI-1/WI-2.** README §4 L538 names the
  audit's next promotions as indicator-alignment / ≥50% OI imbalance / Trending-OI cross / drastic-ΔOI — NOT
  breadth or basis. The plan picked the four soft dots with a ready-made `ScalperGates` function (lowest
  friction), so WI-3/WI-4 are author-prioritised, not audit-mandated, and the README's named trio stays
  unpromoted after this plan. No action; recorded so a later "did we do the audit list?" check isn't misled.

---

## Recommended execution order

1. **Follow-up 1, PR-1** — append the manual checks (single small PR: `ScalperManualChecks` 7 → N,
   the test bumps + new keys-present test, the README/audit doc flips). Lowest risk, no behaviour change,
   no parity exposure. **Resolve F1-OP1 first** (it fixes the count/test literals) and apply the OP-4 copy.
2. **Follow-up 2, PR-1** — `indicator-alignment` hard gate (the documented high-value promotion, reviewed
   on its own). Default-OFF, no YAML armed.
3. **Follow-up 2, PR-2** — `futures-oi-gate` / `breadth-gate` / `basis-gate` (mechanically identical batch).
   Default-OFF, no YAML armed.
4. **Follow-up 2, PR-3 (deferred, owner-driven)** — arm a tag on chosen `scalper-strategies/*.yaml`
   variants as a forward-paper experiment. Not part of the infra delivery; gated on the owner per
   "tune on live, not backtest".

Rationale: Follow-up 1 is the safest opener (pure reminder, zero behaviour/parity surface) and surfaces no
cross-plan dependency; Follow-up 2's infra PRs are inert until PR-3 deliberately arms a tag, so they can land
in any order after. The two plans only touch overlapping ground in the downstream automation Follow-up 1 defers
to Follow-up 2 — none of which is in the current PR delivery of either plan.

---

## Parity-safety confirmation (Follow-up 2)

**Confirmed parity-safe:** the four hard gates are opt-in (new tags absent from all 36 shipped YAMLs →
every config byte-identical, default-OFF), live-only (`ScalperConfluenceGate` is never instantiated by
`TickwiseGoldenRunner`/`ReplayEngine`, so it is inert on every deterministic replay), and add NO term to
`ConnectTheDotsScorer.score`/`valid` and NO field to the serialized golden — so `GoldenDeterminismTest`
and `BacktestParityTest` stay byte-identical with no fixture regenerated (re-run both as tripwires; do NOT
regenerate). (Follow-up 1 is likewise parity-safe — `manual_checks` rides the same V009 side-channel the
golden never serializes.)
