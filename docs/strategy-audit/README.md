# Options-Scalper Automation Audit — what the strategy doc says vs what the engine does

> **CLOSED — historical record (2026-06-28).** This whole directory audited the OLD *bloated*
> `Options_Scalper_Siva_Consolidated_Strategy.md`. It is superseded by the **debloated** S24 operative
> doc (`strategy-documents/options-scalper-siva-operative/Options_Scalper_Siva_Operative_Strategy.md`)
> and the S24 ratification chain it produced (COMPARISON → RATIFICATION-PACK → W1 operative doc →
> W2 prune → W3 6 drifts (built) → W4 triage+impl). **Forward work now lives in the consolidated
> roadmap:** [`../superpowers/plans/2026-06-28-scalper-to-100-roadmap.md`](../superpowers/plans/2026-06-28-scalper-to-100-roadmap.md).
> Kept here for provenance + the `GAP-DISPOSITION` / `RATIFICATION-PACK` decision trail; the gap
> *counts* below are against the bloated doc, not the debloated 100% scope.

**Wave-5 #1.** This audit answers one question: *of everything in the consolidated Siva options-scalper
strategy doc (`strategy-documents/options-scalper-siva/Options_Scalper_Siva_Consolidated_Strategy.md`),
what is NOT automated, and therefore must be carried by a human checklist?*

It is a **whole-document** sweep, not just a strategy-by-strategy pass. All seven doc sections were
audited: the 12 strategies (§3.1–§3.12), Global Risk Management (§2), every shared input (§4.1–§4.17),
the Introduction/Glossary (§1), and a completeness sweep over the §5 evolution log, the §7 open
questions, and an orphan check for any rule with no home. Each gap below survived an adversarial
verification pass (false-coverage and invented-claim flags are called out at the end).

Status legend used in the section files: **FULL** (automated, not listed here) · **PARTIAL** (partly
automated / soft dot, not a hard gate) · **NONE** (not in code at all) · **MANUAL_COVERED** (carried by
the existing 7-item `ScalperManualChecks`) · **UNCERTAIN** (doc itself is ambiguous).

The "derived-history caveat" recurs throughout: OI/VIX/IV/breadth/Dow "dots" degrade to NEUTRAL on
backtests (per `CLAUDE.md`), so a gap marked PARTIAL may be wired live yet inert on history. Status
judges **code presence**, not backtest behaviour.

---

## Second-pass review

This audit was **independently re-audited and corrected in place.** A second pass re-checked all 18 section
files against the strategy doc and the live engine, then put every change through adversarial verification.
The verdict — recorded in full in **[AUDIT-REVIEW.md](./AUDIT-REVIEW.md)** — is that the v1 audit was
**highly accurate**: of 521 re-counted v1 rules, **499 verified unchanged** (~96%), **16 inaccurate rows
were fixed** (false-coverage demoted, false-gaps promoted, one invented figure removed, the rest
loose-cite tightenings), and **45 genuinely-missed rules were added** (7 already-automated, 38 new gaps —
most visibly 6 per-stock screener rules under Market Movers). No correction overturned a headline
conclusion; **17 of 18 dimensions finish HIGH** confidence and 1 (Connect the Dots) MEDIUM. The adversarial
pass flagged only **6 cosmetic** new issues (count typos, a section-label slip, off-by-one notes) — none
substantive, none altering a status, figure, or table. The second-pass totals were 566 rules / 420 gaps; a
subsequent third (citation-validation) pass added 4 genuinely-missed rows (Two Candle multi-TF RSI, BTST
next-morning re-confirm, Morning-Trade lot-modulation, Trend-Change strong-trend pullback — all gaps), so the
corrected totals above now read **570 rules / 424 gaps**.

## Third-pass review

A third **citation-validation + convergence** pass opened every cited `file:line` / yaml key / doc-line, fixed 24 stale-but-non-substantive citations across 12 dimensions, added the 4 genuinely-missed rows folded into the totals above, and reconciled the index/summary counts — verdict: the rule set is **stable** and the audit **trustworthy** (no status overturned, no figure invented). Full record: **[AUDIT-REVIEW-PASS3.md](./AUDIT-REVIEW-PASS3.md)**.

---

## 1. Executive summary

> **Counts are post-second-pass-review (corrected).** The audit was independently re-audited and corrected
> in place; the v1 published totals were 499 rules / 382 gaps. See [§ Second-pass review](#second-pass-review)
> and [AUDIT-REVIEW.md](./AUDIT-REVIEW.md). The re-audit re-counted the v1 section files at 521 rules, added
> **45** missed rules (7 already-automated, 38 new gaps) → **566** rules, and fixed **16** inaccurate rows —
> all without overturning a headline conclusion. A third citation-validation pass then added **4** more
> genuinely-missed rows (all gaps) → the current **570** rules / **424** gaps.

| Metric | Count |
|--------|------:|
| Dimensions audited (doc sections) | **18** |
| Total rules audited | **570** |
| Total verified gaps | **424** |
| — PARTIAL (soft/partial coverage) | 178 |
| — NONE (no code at all) | 185 |
| — MANUAL_COVERED (already in `ScalperManualChecks`) | 53 |
| — UNCERTAIN (doc ambiguous) | 8 |
| Gaps marked **automatable** (candidate future work) | **~318** |
| Gaps **genuinely manual-only** (automatable=false) | **~106** |

**Reading the two headline numbers.** Of the 424 gaps, **~318 are automatable-but-not-yet** (a coding
task could close them — wire an existing feed, add a gate, grade sizing) and **~106 are genuinely
manual-only** — discretionary judgement, psychology, capital governance, or inputs with no data source
(pre-market prep, FII/DII reads with no scorer, S/R eyeballing, "trade only money you can afford to
lose"). The high automatable count is expected: most gaps are *soft dots that should be hard gates* or
*management rules with no encoded equivalent*, both of which are mechanisable in principle. The
**operationally important** subset is the manual checklist below — every NONE / PARTIAL / MANUAL_COVERED
rule a trader must confirm by hand **today**, regardless of whether it could be automated later.

The existing engine carries only a **7-item** human checklist (`ScalperManualChecks`:
`news_clear`, `level_respected`, `not_parabolic`, `regime_ok`, `vix_normal`, `global_cues_ok`,
`clean_setup`). The audit's central finding for the operator: **51 gaps map onto those 7 items, but the
S21–S24 session additions and the §4.13/§4.17 FII-DII inputs add at least 8 manual-only rules that have
NO checklist item at all** (see `session-additions-and-manual-coverage.md` final row) — the trader gets
no on-card reminder for constituent-weight, FII long/short ratio, pre-open advance/decline, Sensex
participation, intraday-vs-positional OI, expiry-day IV crush, straddle-VWAP, or time-of-day VWAP
weighting.

---

## 2. All dimensions

Counts are **post-second-pass-review**: the `Rules` column is the re-audited row count (v1 recount + missed
rules added in place) and `Verified gaps` reflects the corrected gap inventory. Per-dimension correction
detail (v1 rules · missed-found · inaccuracies-fixed · confirmed · verdict) is in
[AUDIT-REVIEW.md § 1](./AUDIT-REVIEW.md#1-accuracy-verdict-per-dimension).

| Dimension | Doc § | Rules | Verified gaps | Section file |
|-----------|-------|------:|--------------:|--------------|
| Two Candle Theory | §3.1 (+5.1/6.1) | 40 | 29 | [two-candle.md](./two-candle.md) |
| Open=High / Open=Low | §3.2 | 32 | 18 | [open-high-low.md](./open-high-low.md) |
| Market Movers | §3.3 (+6.3) | 25 | 23 | [market-movers.md](./market-movers.md) |
| Gap Theory | §3.4 | 26 | 17 | [gap-theory.md](./gap-theory.md) |
| Trending OI Crossover | §3.5 (+6.5) | 34 | 29 | [trending-oi.md](./trending-oi.md) |
| Golden Crossover | §3.6 (+6.6) | 19 | 15 | [golden-crossover.md](./golden-crossover.md) |
| Hero-Zero (Expiry-Day OI) | §3.7 | 32 | 22 | [hero-zero.md](./hero-zero.md) |
| BTST / STBT | §3.8 (+6.8) | 27 | 23 | [btst-stbt.md](./btst-stbt.md) |
| Morning Trade | §3.9 (+6.9) | 28 | 22 | [morning-trade.md](./morning-trade.md) |
| Connect the Dots (framework) | §3.10 (+6.10) | 41 | 22 | [connect-the-dots.md](./connect-the-dots.md) |
| Straddle (Long & Short) | §3.11 (+6.11) | 24 | 21 | [straddle.md](./straddle.md) |
| Trend Change | §3.12 | 31 | 26 | [trend-change.md](./trend-change.md) |
| Global Risk Management | §2.1–§2.14 | 45 | 42 | [risk-framework.md](./risk-framework.md) |
| Indicators / OI / VIX / IV | §4.1–§4.6 | 39 | 20 | [indicators-oi-vix-iv.md](./indicators-oi-vix-iv.md) |
| Cues / A-D / Strike / Time / S&R / OIP / FII-DII | §4.7–§4.13 | 25 | 18 | [gates-strike-sr-fiidii.md](./gates-strike-sr-fiidii.md) |
| Session-21..24 additions + open-questions + checklist coverage | §4.14–§4.17, §7 | 43 | 36 | [session-additions-and-manual-coverage.md](./session-additions-and-manual-coverage.md) |
| Introduction & Terminology / Glossary | §1.1–§1.2 | 33 | 18 | [intro-terminology.md](./intro-terminology.md) |
| Whole-document completeness sweep | §1, §5, §7 + orphans | 26 | 23 | [completeness-sweep.md](./completeness-sweep.md) |
| **Total** | **§1–§7** | **570** | **424** | |

---

## 3. The consolidated manual checklist

Every verified MANUAL / NONE / PARTIAL gap, as a checkable item, grouped per-strategy then by
shared-input. Each line carries its doc § and a tag:

- **[in-checklist]** — already covered by one of the 7 `ScalperManualChecks` items (the key is named).
- **[NEW]** — no checklist item exists; a human must remember it unaided (candidate to add to
  `ScalperManualChecks`).

For full per-rule evidence (file:line, YAML key) follow the section link.

### 3a. Per-strategy gaps

#### Two Candle Theory — §3.1 · [two-candle.md](./two-candle.md)
- [ ] RSI band actually fires on §4.2's 60–80 / 20–40, not §3.1's 50–75 — confirm the live band you intend (§3.1#3) **[NEW]**
- [ ] If RSI was >80/85 on the 2nd candle, wait for cool-off and enter the red/pullback candle (§3.1 S21(f)/S24(a)) **[NEW]**
- [ ] Eyeball that PSAR + VWMA + Supertrend are all on the far side (only VWAP is a hard gate) (§3.1#8) **[NEW]**
- [ ] Confirm the futures OI quadrant supports the side (weighted dot, not a gate, for two-candle) (§3.1#2,#6) **[NEW]**
- [ ] Verify the OI-change gap is substantial, not marginal (the 50% imbalance gate is not armed here) (§3.1#6) **[NEW]**
- [ ] Only size up when the Trending-OI directional difference exceeds 50% (sizing is fixed in YAML) (§3.1 S21(g)) **[NEW]**
- [ ] Bull from Support / bear from Resistance; opposing S/R nearby = low prob (§3.1#7) — **[in-checklist: level_respected]**
- [ ] Don't chase a parabolic/vertical move; wait for a pullback (§3.1 filters) — **[in-checklist: not_parabolic]**
- [ ] Directional VIX (down=CE / up=PE); abnormal spike is the only manual leg (§3.1 filters/§4.5) — **[in-checklist: vix_normal]** (direction is also a soft dot) **[NEW for direction]**
- [ ] Global cues match direction + the 3:15 PM re-check (§3.1 filters/§4.7) — **[in-checklist: global_cues_ok]** (3:15 re-check is **[NEW]**)
- [ ] Confirm the chosen strike's premium is in band (backtest selector ignores the band) (§3.1#6/§4.9) **[NEW]**
- [ ] Check IV trend on the chosen strike (only a 6-strike avg pair + IV-rank proxy is scored) (§3.1 desirables/§4.6) **[NEW]**
- [ ] If price ran far pre-entry, use VWAP as the SL instead of the 1st-candle level (§3.1 exit) **[NEW]**
- [ ] On a very large 1st candle, consider the 2nd-candle low / size down for the wider risk (§3.1 S21(b)/S24(c)) **[NEW]**
- [ ] Manage the 1–2% / next-S-R target by hand (auto exit is VWAP-cross or 10-bar timeout) (§3.1 exit) **[NEW]**
- [ ] Confirm the VWAP break carried volume (signal_exit has no volume discrimination) (§3.1 exit) **[NEW]**
- [ ] Trail on PSAR then Supertrend to ride the move (no trailing logic encoded) (§3.1 exit) **[NEW]**
- [ ] Execute the scale-in averaging ladder by hand (YAML is single fixed entry, max_positions 1) (§3.1 risk/S22(d)) **[NEW]**
- [ ] Take only ONE ST/VWAP-rejection trade per 2-candle event (not counted in code) (§3.1 S21(c)/S23) **[NEW]**
- [ ] Note when a Golden Crossover coincides and ride the whole trend (no combo detection) (§3.1 S21(d)) **[NEW]**
- [ ] On a bearish 2-candle with RSI <20, prefer the ST-rejection reversal (no explicit routing) (§3.1 bear#3) **[NEW]**
- [ ] No fresh two-candle entries after ~2:30 PM (the seam only blocks ≥15:30) (§3.1 S23/S24) **[NEW]**
- [ ] Candle right at the open is not valid — the 09:45 floor indirectly enforces this (acceptable) (§5.1 S23) **[NEW]**
- [ ] Scale SL/target ~3× for SENSEX point-scaling (signal-on-NIFTY/execute-on-SENSEX is automated, scaling is not) (§3.1 S23/§4.16) **[NEW]**
- [ ] Scan news/calendar before entry (§3.1 filters/§2.13) — **[in-checklist: news_clear]**
- [ ] Only the CE (bullish) leg is registered — trade the bearish two-candle by hand or add PE YAMLs (§3.1 bull/bear) **[NEW]**

#### Open=High / Open=Low — §3.2 · [open-high-low.md](./open-high-low.md)
- [ ] Read the OI-Pulse AI badge ≥90% (red dot) before entering (§3.2) **[NEW]**
- [ ] Prefer entries in 9:15–10:00; deprioritise after 10:30 (§3.2) **[NEW]**
- [ ] Confirm the picked strike premium sits in the 150–350 Nifty band (hardcoded to older 100–250) (§3.2) **[NEW]**
- [ ] Set the target ~5 pts inside the OH/OL extreme; take 30–50 pts (engine = VWAP stop + 20-bar) (§3.2) **[NEW]**
- [ ] Trail the stop once in profit (§3.2) **[NEW]**
- [ ] Exit if premium falls >50% or the strike's OI change crosses 50% after entry (only a pre-entry block) (§3.2) **[NEW]**
- [ ] Exit on an adverse candle printing >50K (BN)/125K (N) volume (the floor is only an entry downgrade) (§3.2) **[NEW]**
- [ ] Keep this trade ≤30% of capital (only budget + max_daily_loss_pct encoded) (§3.2) **[NEW]**
- [ ] Confirm VIX direction (§3.2) — **[in-checklist: vix_normal]** (direction is a soft dot) **[NEW for direction]**
- [ ] Confirm IV is rising in the bought strike (per-strike IV direction not gated) (§3.2) **[NEW]**
- [ ] Confirm RSI5m<75/80, RSI(D)<75 and price above ALL of VWAP/ST/VWMA (only RSI>50 + VWAP hard) (§3.2) **[NEW]**
- [ ] Check the identified strike's own ΔOI% <50 (reject reuses chain-wide %) (§3.2) **[NEW]**
- [ ] Confirm the picked strike is ATM/ITM, not OTM (footprint window is symmetric ATM±3) (§3.2) **[NEW]**
- [ ] Pick the strike closest to its OH level (picker selects on delta-nearest-midpoint) (§3.2) **[NEW]**
- [ ] Confirm the OI build-up direction on the live chain (folded into the aggregate, not a hard OH gate) (§3.2) **[NEW]**
- [ ] Confirm the OH side aligns with the day's trend (only an optional 60-min bias filter gated) (§3.2) **[NEW]**

#### Market Movers — §3.3 · [market-movers.md](./market-movers.md)
- [ ] Run the OI-Pulse Market Movers screen and pick the actual leading stock (YAML trades an index CE surrogate) (§3.3) **[NEW]**
- [ ] Confirm the candidate prints an 8D/9D high (long) or low (short) (§3.3) **[NEW]**
- [ ] Read the OL/OH flag for the chosen stock (OL for long, OH for short) (§3.3) **[NEW]**
- [ ] Read the per-stock OI Interpretation (engine only reads index-level OI) (§3.3) **[NEW]**
- [ ] Check the candidate's Daily RSI <75 (long) / >40 (short) (only a 3m RSI is wired) (§3.3) **[NEW]**
- [ ] Confirm the stock's own intraday RSI(5m) is in band (auto band runs on the NIFTY future at 3m) (§3.3) **[NEW]**
- [ ] Confirm the stock itself is above its own VWAP (VWAP-reclaim trigger runs on the index surrogate) (§3.3) **[NEW]**
- [ ] Volume-20 MA is not declared — only a static volume floor is gated (§3.3) **[NEW]**
- [ ] Confirm ST/VWMA/SAR cross, S/R breakout, IV direction on the stock's strike (scored at index level) (§3.3) **[NEW]**
- [ ] Manually pick a liquid, high-volume name (only a static index volume floor is gated) (§3.3) **[NEW]**
- [ ] Read direction from the top index constituents (no constituent-weight input exists) (§3.3 S21) **[NEW]**
- [ ] Book ~1–2% on the stock (no percent-target exit; exit = close<vwap + 20-bar) (§3.3) **[NEW]**
- [ ] Set SL by own risk appetite on the actual name (entry-candle stop is on the NIFTY future) (§3.3) **[NEW]**
- [ ] If carrying overnight, verify the close shows Long Build-up (carry rule deferred) (§3.3) **[NEW]**
- [ ] Trade the bearish path (8/9-day low + OH + Short Build-up) by hand — YAML is long/CE only (§3.3) **[NEW]**
- [ ] On an adverse move judge volume: high stock volume → cut, low → may hold (no volume-conditional exit) (§3.3) **[NEW]**
- [ ] Skip ambiguous names with OI heavy on both call and put sides (no per-stock both-sides check) (§3.3) **[NEW]**

#### Gap Theory — §3.4 · [gap-theory.md](./gap-theory.md)
- [ ] Eyeball the stricter prior-high/low→open gap for a higher-probability entry (detector uses close→open) (§3.4) **[NEW]**
- [ ] If trading the counter-trend gap-fill scalp, place it manually (deliberately deferred as risky) (§3.4) **[NEW]**
- [ ] Compare the engine's pre-gap-candle SL against the live SuperTrend level and tighten if closer (§3.4) **[NEW]**
- [ ] Confirm the post-fill side matches the higher-TF prevailing trend (1h ST bias alias not gated in backtest) (§3.4) **[NEW]** ⚠ *see false-coverage flag §5*
- [ ] At the ~30–40 min mark, drop an unfilled gap and take the trend trade (only a 60-min time-stop) (§3.4) **[NEW]**
- [ ] Avoid fresh gap entries in the 11am–1pm drift and before post-3:30 events (§3.4) **[NEW]** ⚠ *see false-coverage flag §5*
- [ ] Confirm RSI<75 (CE) and outside the 40–60 no-trade zone (backtest YAML feeds RSI to scoring only) (§3.4) **[NEW]**
- [ ] Confirm the fill/gap candle prints at/above the volume threshold (backtest has no volume gate) (§3.4) **[NEW]**
- [ ] Set a manual target at the next S/R (~1:2 R:R, ≤1–2%) — no take-profit in any gap YAML (§3.4) **[NEW]**
- [ ] Manually trail the SL ~5 pts below price once in profit (no trailing stop) (§3.4) **[NEW]**
- [ ] Prefer pullback entries to VWMA/ST/VWAP, not extended (no proximity/pullback band) (§3.4) **[NEW]**
- [ ] Confirm the chosen strike sits in the 0.6–0.7 delta / premium range (backtest uses ATM window only) (§3.4) **[NEW]**
- [ ] Confirm OI/VIX/DOW align before the trade (§3.4) — **[in-checklist: vix_normal + global_cues_ok]**
- [ ] On a gap-up, look for a support/long entry, never a short (active "seek support" not encoded) (§3.4) **[NEW]**
- [ ] Confirm no market-moving news against the trade (§2.13) — **[in-checklist: news_clear]**
- [ ] Confirm a clean, non-parabolic setup at a respected level (§3.1) — **[in-checklist: not_parabolic / clean_setup / regime_ok / level_respected]**

#### Trending OI Crossover — §3.5 · [trending-oi.md](./trending-oi.md)
- [ ] Confirm a real PE-over-CE (or CE-over-PE) line cross before entry (engine treats it as one soft dot) (§3.5) **[NEW]**
- [ ] Confirm the OI Sentiment line slopes the trade's way (slope is a soft dot, not paired with %-change) (§3.5) **[NEW]**
- [ ] Eyeball that the two OI lines diverge ≥20–30% right after the cross (engine has only a boolean flag) (§3.5) **[NEW]**
- [ ] Note the engine uses §4.2's narrower band, not §3.5's <75/>25 (reconcile the bearish-RSI conflict) (§3.5) **[NEW]**
- [ ] Read the cross on a 5–15 min interval and confirm the 60-min OI trend agrees (engine uses default bucket) (§3.5) **[NEW]**
- [ ] Confirm a real price thrust accompanied the OI cross (no price-impulse magnitude tied to it) (§3.5) **[NEW]**
- [ ] Grade size: small on a low-prob/RSI-cooling cross, full on a drastic-shift+volume cross (sizing fixed) (§3.5) **[NEW]**
- [ ] Take PE / short-premium setups by hand — these YAMLs are CE-LONG-ONLY (§3.5) **[NEW]**
- [ ] Book at ~1–2% per scalp / scale while the OI gap widens (exits = VWMA-break + 12-bar timeout) (§3.5) **[NEW]**
- [ ] On a fake/double cross, book SL and rotate to the next genuine cross (side-flip not automated) (§3.5) **[NEW]**
- [ ] Don't chase an up-move where the cross never finishes (CE writers re-add) (§3.5 exec-notes) **[NEW]**
- [ ] Verify the opposite side is genuinely building, not just one side covering (soft dot, not a hard reject) (§3.5 S22(b)) **[NEW]**
- [ ] If absolute OI is unchanged all day, skip even at a 50% diff (flat-OI caveat currently degrades to PASS) (§3.5) **[NEW]**
- [ ] At series end with both sides covering, defer to next-series OI (not modelled) (§3.5 exit.time) **[NEW]**
- [ ] On monthly expiry, cross-check positional vs intraday OI yourself (engine SUPPRESSES OI that day) (§3.5 S21(d)) — **[in-checklist: partial]** **[NEW]**
- [ ] Watch the Trending-OI direction-change arrows after a big gap/morning move (no arrow primitive) (§3.5 S21) **[NEW]**
- [ ] Re-centre the Trending-OI strikes to ATM±7 after a >1% move (fixed ATM±3 window) (§3.5 S21(e)) **[NEW]**
- [ ] Near a weekly-expiry edge, adjust delta by hand (expiry-phase delta band doc-sanctioned-deferred) (§4.9/§4.14.7) **[NEW]**
- [ ] Prefer the 10–11:30 AM window; avoid fresh entries after ~1:30 PM (only hard rails encoded) (§3.5 S21(b)) **[NEW]**
- [ ] Cut size on a low-probability / RSI-extreme cross (sizing is fixed premium_budget) (§3.5 risk) **[NEW]**
- [ ] Read the EOD OI gap for next-day directional bias (no EOD-OI carry) (§3.5 S21(f)) **[NEW]**
- [ ] Intraday-flat is forced by YAML; overnight rails moot here (SPAN-deferred) (§3.5 risk) — informational
- [ ] Scan news/calendar before entering (§2.13) — **[in-checklist: news_clear]**
- [ ] Check the 1-day and 15-min S/R zones (§4.11) — **[in-checklist: level_respected]**
- [ ] Skip vertical/forced entries (§3.1) — **[in-checklist: not_parabolic + clean_setup]**
- [ ] Count today's VWAP crossovers; stand aside if choppy (§3.10) — **[in-checklist: regime_ok]**
- [ ] Glance India VIX vs recent sessions; check DOW/Asian direction (§4.5/§4.7) — **[in-checklist: vix_normal + global_cues_ok]**

#### Golden Crossover — §3.6 · [golden-crossover.md](./golden-crossover.md)
- [ ] Confirm the entry bar is the actual same-candle VWAP pierce by both ST and VWMA with a real body (§3.6 setup-3) **[NEW]**
- [ ] Confirm the crossover candle's own volume clears the correct index floor (SENSEX uses the NIFTY floor) (§3.6 setup-4) **[NEW]**
- [ ] A Golden-Cross bull at RSI 50–60 is doc-eligible (<75) but the 60–80 band rejects it — judge by hand (§3.6 bull-3) **[NEW]**
- [ ] Bearish (Buy PE / Sell CE) side is not seeded; the PE band differs from the card — trade short by hand (§3.6 bear-3) **[NEW]**
- [ ] Confirm a genuine drastic two-sided ΔOI move (soft dot, weight 1.0; the 50000 floor is a placeholder) (§3.6 setup-5) **[NEW]**
- [ ] Read the Trending-OI dashboard over 5/7 strikes around ATM (not a Golden-Cross-specific knob) (§3.6 setup-5) **[NEW]**
- [ ] On the support-trade form, place SL at the Supertrend line (only breakout crossover-candle stop is automated) (§3.6 exit) **[NEW]**
- [ ] Manage to the ~50–300 point move expectations (exits = VWMA-undone or 12-bar time stop) (§3.6 exit) **[NEW]**
- [ ] Skip extension entries when RSI is already at the band edge; wait for VWAP to hold (§3.6 S21(e)) **[NEW]**
- [ ] Tick news_clear before Take (§2.13) — **[in-checklist: news_clear]**
- [ ] Tick vix_normal (VIX direction is a soft dot) (§4.5) — **[in-checklist: vix_normal]**
- [ ] Tick global_cues_ok (Dow soft dot is live-only) (§4.7) — **[in-checklist: global_cues_ok]**
- [ ] Tick regime_ok; no-body/no-volume crossover traps not separately automated (§3.10) — **[in-checklist: regime_ok]**
- [ ] Tick not_parabolic + clean_setup (rare ~3–4×/month discipline) (§3.1) — **[in-checklist: not_parabolic + clean_setup]**
- [ ] Resolve the bearish RSI rule (card >25 vs matrix <25) with the owner before automating the PE side (§3.6 UNCERTAIN) **[NEW]**

#### Hero-Zero (Expiry-Day OI) — §3.7 · [hero-zero.md](./hero-zero.md)
- [ ] Hold fresh entries to ~2:30–2:45 PM (encoded floor is 14:30) (§3.7) **[NEW]**
- [ ] Confirm the chosen side matches where premium is decaying and shorts are building (side is a VWAP proxy) (§3.7) **[NEW]**
- [ ] Note live square-off is 15:20, ~10 min before the doc's 3:20 PM hard close (§3.7) **[NEW]**
- [ ] Exit at ~15:10 if the move is not happening (3:10 no-move exit not encoded) (§3.7) **[NEW]**
- [ ] Confirm the break is at the marked max-OI build-up level (gate doesn't verify S/R break) (§3.7) **[NEW]**
- [ ] Confirm >50% OI+price on the actual entry strike (the >50% check runs on the index, not per-strike) (§3.7) **[NEW]**
- [ ] Confirm the OI fall is genuinely drastic (drasticFloor exists but is not consulted) (§3.7) **[NEW]**
- [ ] Apply a ~30-pt (Nifty)/~75 (BN)/wider Sensex point stop (only 50%-premium + opposite-extreme stops) (§3.7) **[NEW]**
- [ ] Ensure the stake is a small slice of profits only (sizing is a flat budget_inr:2000) (§3.7) **[NEW]**
- [ ] Never average down a Hero-Zero loser (only incidentally covered by 1-position cap) (§3.7) **[NEW]**
- [ ] Skip when price is being pinned at VWAP into the close (no pin-skip) (§3.7) **[NEW]**
- [ ] Avoid either side when sellers pin a round strike (double-zero warning) (§3.7) **[NEW]**
- [ ] Skip when both-side IV is flat/similar (HeroZeroGate never reads ceIvAvg6/peIvAvg6) (§3.7) **[NEW]**
- [ ] Do not buy a PE when CE is at a discount (§3.7) **[NEW]**
- [ ] Distinguish a real short-cover squeeze from a PE-premium-adjustment fake move (§3.7) **[NEW]**
- [ ] US/global cues give the clue for the next move (§3.7) — **[in-checklist: global_cues_ok]**
- [ ] India VIX not abnormally spiking (Macro.vix exists but the gate doesn't read it) (§3.7) — **[in-checklist: vix_normal]**
- [ ] No market-moving news against the trade (§3.7/§2.13) — **[in-checklist: news_clear]**
- [ ] Not chasing a parabolic/vertical move (§3.7/§3.1) — **[in-checklist: not_parabolic]**
- [ ] Do the evening prep (4–5 strikes either side, round strikes, last 3–4 days OI) (atm_window:3 is not the ritual) (§3.7) **[NEW]**

#### BTST / STBT — §3.8 · [btst-stbt.md](./btst-stbt.md)
- [ ] Only carry BTST if the day closed at/near its HIGH (STBT at/near LOW) — engine uses direction:both (§3.8) **[NEW]**
- [ ] Manually verify the entire OI/VIX/breadth/sentiment/IV confluence at the close (BTST path bypasses the gate) (§3.8) **[NEW]**
- [ ] Map close + OI position to the correct SC/LB/SB/LU quadrant before carrying (§3.8) **[NEW]**
- [ ] Confirm the 3:15pm Futures OI direction matches the carry side (§3.8) **[NEW]**
- [ ] Confirm Trending OI + Sentiment graph agree with the side at 3:15pm (§3.8) **[NEW]**
- [ ] Confirm your carry view matches the OI-Pulse AI read at 3:20pm (§3.8) **[NEW]**
- [ ] Confirm global cues at the 3:15pm BTST stamp (generic global_cues_ok is not the stamp; Dollar index not coded) (§3.8) — **[in-checklist: global_cues_ok]** **[NEW for stamp]**
- [ ] Confirm VIX direction + close-at-low rule (vix_normal is a spike check, not the directional rule) (§3.8) — **[in-checklist: vix_normal]** **[NEW for direction]**
- [ ] Confirm daily RSI (rsiBand gate is inside the bypassed confluence gate) (§3.8) **[NEW]**
- [ ] Never carry a fresh stock position with daily RSI >75 (no hard block; stock universe deferred) (§3.8) **[NEW]**
- [ ] Pick the ATM±3, delta 0.6–0.7, in-band option leg yourself (StrikePicker bypassed on BTST path) (§3.8) **[NEW]**
- [ ] Size/execute SELL legs manually if traded (deferred to margin-service SPAN #47) (§3.8) **[NEW]**
- [ ] Confirm strong, directional volume into the close (YAML gate is the trivial volume>0) (§3.8) **[NEW]**
- [ ] Confirm breadth (adv/dec) agrees with the carry side (>32 gate unreached on BTST path) (§3.8) **[NEW]**
- [ ] Do not open a BTST/STBT carry on a Friday (preCloseClock fires MON–FRI, no day-of-week block) (§3.8) **[NEW]**
- [ ] Skip BTST near expiry against trend (parabolic case covered; expiry-vs-trend not coded) (§3.8) — **[in-checklist: not_parabolic]** **[NEW for expiry]**
- [ ] STBT stock short-sell delivery penalty in monthly expiry (index-only here, stock universe deferred) (§3.8) **[NEW]**
- [ ] Next morning confirm the prev-day-level read and trail winners (engine just time-exits after 1 day) (§3.8) **[NEW]**
- [ ] The stock BTST/STBT n-day-low variant is not built (equity universe deferred, #3) (§3.8) **[NEW]**
- [ ] Identify the max-OI support/resistance strikes before the carry (not coded) (§3.8) **[NEW]**

#### Morning Trade — §3.9 · [morning-trade.md](./morning-trade.md)
- [ ] Only take if the prior session closed at its high (CE) or low (PE) — skip an inside/near-open close (§3.9) **[NEW]**
- [ ] Confirm a rejection wick has formed at/around the prior-day close (§3.9) **[NEW]**
- [ ] Watch how the 2nd candle breaks the 1st and only fire on alignment (§3.9) **[NEW]**
- [ ] Set the profit target at the next mapped Futures S/R level (engine exits on close<vwap + 10-bar) (§3.9) **[NEW]**
- [ ] On a gap-down with RSI already oversold (PE), wait for RSI to recover to resistance (§3.9) **[NEW]**
- [ ] Confirm the OI-Pulse AI direction and the pre-market direction agree (§3.9) **[NEW]**
- [ ] Once in profit, trail the stop to your buy price (engine keeps a static first-candle stop) (§3.9 S22(b)) **[NEW]**
- [ ] If price hits the opposite-side Open=High against you, exit (not wired into the opening-tick path) (§3.9 S22(e)) **[NEW]**
- [ ] Book or scratch within the first candles; don't ride to max_bars:10 (§3.9 time exit) **[NEW]**
- [ ] Confirm CE RSI is not above 75 (code caps at 80); variants are CE-only (§3.9) **[NEW]**
- [ ] Use the prior-day VWAP as the respected level (only current-VWAP-before-10:30 suppression is automated) (§3.9) **[NEW]**
- [ ] At 3:20 PM the prior session confirm Futures OI + Option OI align (engine scores current-bar OI) (§3.9) **[NEW]**
- [ ] Review FII/DII positioning in the prior-evening view (fiiLongPct fetched but not scored) (§3.9) **[NEW]**
- [ ] Size off profits only (engine uses a fixed premium_budget) (§3.9 risk 2) **[NEW]**
- [ ] Confirm a >50% OI-direction change supports the view (the ≥50% filter is behind an unused tag) (§3.9 S22(g)) **[NEW]**
- [ ] Global cues match direction (Dow not fed to the scorer) (§3.9/§4.7) — **[in-checklist: global_cues_ok]**
- [ ] India VIX not abnormally spiking (VIX dot is hard-null in code) (§4.5) — **[in-checklist: vix_normal]**
- [ ] Stand aside if post-close news invalidates the EOD positioning (§3.9/§2.13) — **[in-checklist: news_clear]**
- [ ] Experienced-traders-only / clean one-good-trade discipline (§3.9 risk 1) — **[in-checklist: clean_setup + not_parabolic + regime_ok]**

#### Connect the Dots (framework) — §3.10 · [connect-the-dots.md](./connect-the-dots.md)
- [ ] Book 90% at RSI 75–80, last 10% at RSI 85 (short mirror 25–20/15); don't over-hold (§3.10 exit) **[NEW]**
- [ ] On a close below VWAP confirm whether the break carries volume (YAML exits on any close<vwap) (§3.10 exit) **[NEW]**
- [ ] Set the stop at the 1st/entry candle low/high (connect-the-dots arms StructuralStop.NONE) (§3.10 exit) **[NEW]**
- [ ] Confirm the 2-green/2-red formation with a strong 2nd candle and aligned indicators (coarse gate = close>vwap) (§3.10 entry) **[NEW]**
- [ ] Confirm the heavier OI side leads by ≥50% (callPutDeltaFilter armed only by an absent tag) (§3.10 S22(b)) **[NEW]**
- [ ] Read India VIX direction vs price (scalper VIX dot degrades to pass — null vixLevel/vixRising) (§3.10 entry 10) **[NEW]**
- [ ] Check DOW futures direction (Dow rides only the OI-page matrix, not the scalper scorer) (§3.10 setup 3) **[NEW]**
- [ ] Scan dollar index, Asian/European indices, crude, bond yields, USD/INR (none in any field/gate/scorer) (§3.10 setup 3) **[NEW]**
- [ ] Manually weigh FII-DII (fiiLongPct fetched but not scored) (§3.10) **[NEW]**
- [ ] Cross-check the AI/OSPL-suggested strike (only the delta/premium-band picker is automated) (§3.10 setup 6) **[NEW]**
- [ ] Target the next S/R and cap at ~1–2% (YAML exits = close<vwap + time_stop, no take-profit) (§3.10 exit) **[NEW]**
- [ ] Size up near VWAP, wait out a wide VWAP-to-candle gap (sizing is a fixed premium_budget) (§3.10 S22(a)) **[NEW]**
- [ ] Confirm engine PSAR default = 0.02/0.2 and read PSAR flips as the early trend cue (PSAR is a soft dot) (§3.10 setup 2) **[NEW]**
- [ ] ST(7,3) is wired only as the 60m bias, not a selectable scalp-vs-intraday mode; no 15m variant (§3.10) **[NEW]**
- [ ] The gate uses §4.2's 60–80 band over §3.10's 50–75; confirm you accept it (§3.10 setup 2) **[NEW]**
- [ ] Confirm no impending event before sitting after 15:30 (blanket cap encoded, event semantics not) (§3.10 filters) **[NEW]**
- [ ] Count today's VWAP crossovers; >2–3 = choppy, stand aside (§3.10) — **[in-checklist: regime_ok]**
- [ ] Scan the news feed and economic calendar (§2.13) — **[in-checklist: news_clear]**
- [ ] Check the pre-marked 1-day and 15-min S/R zones (§4.11) — **[in-checklist: level_respected]**
- [ ] Glance at India VIX vs the last few sessions (§4.5) — **[in-checklist: vix_normal]**

#### Straddle (Long & Short) — §3.11 · [straddle.md](./straddle.md)
- [ ] The short straddle is unbuilt (legs are BUY-only, SPAN-deferred #47) — place/manage entirely by hand (§3.11) **[NEW]**
- [ ] Enter long only when the combined Call+Put premium closes above its own VWAP on volume (§3.11/§4.15.2) **[NEW]**
- [ ] Enter short only when the combined premium is below both legs' VWAP after 09:30 (no short path) (§3.11) **[NEW]**
- [ ] Enter an event long straddle ~12:30 PM on the both-leg VWAP close (overrides the 11:00–13:00 block) (§3.11) **[NEW]**
- [ ] Set/trail the real SL on the combined-premium VWAP (engine uses only a 50%-premium proxy) (§3.11) **[NEW]**
- [ ] Exit when the combined premium rolls over from its peak / a lower-low forms (engine = 30-bar + 15:15) (§3.11) **[NEW]**
- [ ] Exit short on decay, at EOD, or instantly on a VWAP re-break (no short path) (§3.11) **[NEW]**
- [ ] After the combo clears VWAP, exit the losing leg and ride the winner (static two-leg draft) (§3.11) **[NEW]**
- [ ] Decide long vs short from the IV / event / range view (only the long draft exists) (§3.11) **[NEW]**
- [ ] Confirm IV/premiums are low before buying (neutral path never reads the per-strike IV feed) (§3.11) **[NEW]**
- [ ] Confirm both-side IV is similar before selling (no CE-vs-PE IV-symmetry check) (§3.11) **[NEW]**
- [ ] Skip the long buy when IV >40; treat ~40/40 as the short-straddle condition (no IV-threshold gate) (§3.11/§4.6) **[NEW]**
- [ ] Compute the combined-premium breakeven and confirm the expected move exceeds it (flat budget, no check) (§3.11) **[NEW]**
- [ ] Read Trending-OI together (short) vs divergence (long) (neutral path uses a NEUTRAL stand-in) (§3.11) **[NEW]**
- [ ] Read entries on the 5-min combined-premium chart (engine runs 3m on the index-future chart) (§3.11) **[NEW]**
- [ ] If running a short straddle, a hard SL above VWAP is mandatory + guard freak-candle multi-hits (unmodelled) (§3.11 risk) **[NEW]**
- [ ] Deploy only from a profit slice (daily/position caps encoded; the discretionary slice is not) (§3.11 risk) **[NEW]**
- [ ] News overrides the data (§2.13) — **[in-checklist: news_clear]**
- [ ] India VIX not spiking (distinct from the straddle's own LOW-IV / similar-IV gates) (§4.5) — **[in-checklist: vix_normal]**
- [ ] Global cues not against the trade (§4.7) — **[in-checklist: global_cues_ok]**

#### Trend Change — §3.12 · [trend-change.md](./trend-change.md)
- [ ] Classify the prevailing trend (trendline + HH/HL vs LH/LL) before trusting a reversal (§3.12) **[NEW]**
- [ ] Manually confirm a trendline break when no fractal-pivot break printed (swing-pivot break automated) (§3.12) **[NEW]**
- [ ] The PE <40 down-reversal RSI path never executes (YAMLs are CE/long-only) (§3.12) **[NEW]**
- [ ] Confirm volume is expanding (rising sequence), not just above floor (§3.12) **[NEW]**
- [ ] The avoid-fresh-DOWN-reversal-after-14:30 cap never engages (bearish-only, YAMLs CE-only) (§3.12) **[NEW]**
- [ ] Avoid wrong naked morning entries (§3.12) — **[in-checklist: not_parabolic]** (+ 09:45 floor)
- [ ] Judge whether the VWAP break carried volume before honouring the close<vwap exit (§3.12) **[NEW]**
- [ ] Apply the ±10–20pt SL pad only with convincing OI (persisted stop is the raw swing pivot) (§3.12) **[NEW]**
- [ ] Manage the ride-to-VWAP target (exit is VWAP-break or a 30-bar time-stop proxy) (§3.12) **[NEW]**
- [ ] Confirm VIX is moving with the reversal (VIX direction is a soft dot, unknown never blocks) (§3.12) **[NEW]**
- [ ] India VIX not abnormally spiking (§3.12/§4.5) — **[in-checklist: vix_normal]**
- [ ] Check the index heavyweights support the reversal direction (no constituent-contribution analytics) (§3.12) **[NEW]**
- [ ] Mark max-CE/PE-OI S/R (max-OI S/R is not a trend-change gate) (§3.12) — **[in-checklist: level_respected]** **[NEW for max-OI]**
- [ ] Confirm intraday AND positional OI have both rotated (gate reads one windowed OI delta) (§3.12) **[NEW]**
- [ ] News overrides data on gap/event/war days (§2.13) — **[in-checklist: news_clear]**
- [ ] Manually spot the 1-2-3 failed-attempt pattern (no multi-failed-attempt detector) (§3.12) **[NEW]**
- [ ] Manually note a held prior-day trendline pivot (PSAR position is a soft dot) (§3.12) **[NEW]**
- [ ] Check OI is not building on both sides (no both-sides-building / hourly-unwinding detector) (§3.12) **[NEW]**
- [ ] After a vertical fall, wait for RSI recovery toward ~40 + a level (no oversold-recovery sequencing) (§3.12) **[NEW]**
- [ ] Avoid chasing into a higher-premium side without cues (no per-side premium-skew warning) (§3.12) **[NEW]**
- [ ] Scale expectations to regime (low-VIX expiry: 10–15pt is a big hit) (§3.12) — **[in-checklist: regime_ok + vix_normal]**
- [ ] Trade the bearish/futures legs by hand (only buy-CE up-reversal is shipped) (§3.12) **[NEW]**
- [ ] Note the 11:00–13:00 / after-15:30 gate blocks a doc-valid 11:00 OI-flip reversal (§3.12) **[NEW]**

### 3b. Shared-input gaps (apply across all strategies)

#### Global Risk Management — §2 · [risk-framework.md](./risk-framework.md)
*Largely manual-only and capital-governance.* Highlights (full list in the section file):
- [ ] Set a 1:2 RR target (0.5% risk : 1% reward) before entry (no take_profit, target null) (§2.1) **[NEW]**
- [ ] Verify the trade risks ~0.5% of capital (sizing is a fixed budget_inr) (§2.2) **[NEW]**
- [ ] Premium outlay ≤10–20% of capital; day deployment ≤20% (no % cap coded) (§2.2) **[NEW]**
- [ ] Scale in manually at VWAP/Supertrend/S-R (engine emits one full suggested_qty) (§2.2) **[NEW]**
- [ ] Skip when price is far from VWAP/Supertrend (wide SL) (no distance check) (§2.2) **[NEW]**
- [ ] Keep loss-day lot count ≤ win-day lot count (no per-outcome qty symmetry) (§2.2) **[NEW]**
- [ ] Size next trade's risk off prior P&L (suggested_qty ignores prior P&L) (§2.2) **[NEW]**
- [ ] Set day profit/loss targets; configure daily_loss_limit to 0.5% (off by default) (§2.3) **[NEW]**
- [ ] YAML max_daily_loss_pct:2.0 is a DEAD key — rely on the global daily_loss_limit (§2.3) **[NEW]**
- [ ] Stop at +1–2% for the day (only a 5-WIN count cap exists) (§2.3) **[NEW]**
- [ ] Run real 5-account rotation + per-account 1% target manually (day-granularity count model only) (§2.4) **[NEW]**
- [ ] Aim for a high-conviction first trade (not automated, not in checklist) (§2.4) **[NEW]**
- [ ] Enforce per-account first-loss stop manually (only the aggregate 5-loss-freeze is coded) (§2.4) **[NEW]**
- [ ] Cut fast on a clear failure (only fixed time-stop + structural stop fire) (§2.5) **[NEW]**
- [ ] Don't average a losing position below VWAP/Supertrend (engine blocks add-ons structurally) (§2.6) **[NEW]**
- [ ] Don't over-trade / revenge-trade; stop at the loss target (no afternoon size-creep detection) (§2.6) **[NEW]**
- [ ] Confirm higher-TF trend agreement (gated by indicator alignment + 1h ST bias; doc trend is broader) (§2.7) **[NEW]**
- [ ] Trade only when the setup fits, else no-trade (§2.7) — **[in-checklist: clean_setup + regime_ok]**
- [ ] Pre/post-market analysis; connectivity/hardware; calm place (psychological/operational) (§2.9) **[NEW — partly not automatable]**
- [ ] Maintain a trade journal (a Journal page exists but journaling is not gated) (§2.9) **[NEW]**
- [ ] Trade only money you can afford to lose; preserve capital; survive a losing quarter (§2.10) **[NEW — mostly not automatable]**
- [ ] Raise base lots only every 3–6 months; sell only hedged, never naked (§2.11) **[NEW]**
- [ ] Cap Hero-Zero/OTM stakes to a tiny profit slice (delta/premium bands bias to ATM/ITM only) (§2.12) **[NEW]**
- [ ] Confirm structural SL roughly matches the index point band (Nifty ~30, BN ~75, Sensex ~200–250) (§2.12) **[NEW]**
- [ ] Keep only 5–10% of net worth in the trading account (out-of-app) (§2.13) **[NEW — not automatable]**
- [ ] Single-day hard loss cap ~10–12% relies on the optional daily_loss_limit (no seed) (§2.14) **[NEW]**
- [ ] Size to VIX & the Trending-OI gap (suggested_qty is volatility-blind) (§2.14) **[NEW]**
- [ ] India VIX not spiking (§4.5) — **[in-checklist: vix_normal]**
- [ ] Global cues not against the trade (§4.7) — **[in-checklist: global_cues_ok]**
- [ ] News overrides data (§2.13) — **[in-checklist: news_clear]**
- [ ] Avoid chasing opening prints except the explicit Morning-Trade setup (§2.13) **[NEW]**

#### Indicators / OI / VIX / IV — §4.1–§4.6 · [indicators-oi-vix-iv.md](./indicators-oi-vix-iv.md)
- [ ] Confirm the continuous front future is the intended structure spine (§4.1) **[NEW — not automatable]**
- [ ] Mark 1d + 15m S/R zones pre-market (§4.11) — **[in-checklist: level_respected]**
- [ ] Confirm the live trending read is on a 5–15-min view (gate uses a fixed 20-bucket window) (§4.4) **[NEW]**
- [ ] Confirm daily RSI <75 (CE) / >25 (PE) (no daily-RSI indicator is wired) (§4.2) **[NEW]**
- [ ] Confirm SAR actually flipped sides toward the trade (gate reads price-vs-PSAR level only) (§4.2) **[NEW]**
- [ ] Confirm the strict all-aligned golden cross visually (scored as soft dots, only VWAP decisive) (§4.2) **[NEW]**
- [ ] Confirm you are not entering a slide-OI/slide-price chop (off-side quadrants only fail to confirm) (§4.3.2) **[NEW]**
- [ ] Eyeball price-move-per-OI demand (only absolute ΔOI is modelled) (§4.14.3) **[NEW]**
- [ ] Compare intraday vs positional Trending-OI + PCR progression (only an intraday read exists) (§4.17.3) **[NEW]**
- [ ] Check VIX direction every entry (VIX is null in the live signal gate — never confirms/blocks) (§4.5) **[NEW]**
- [ ] Read the VIX absolute regime band (no absolute-VIX banding in the gate) (§4.14.1) **[NEW]**
- [ ] Compare VIX to prev-day close; ignore VIX on erratic days (§4.5) — **[in-checklist: vix_normal]** **[NEW for compare]**
- [ ] Confirm ATM IV is in the absolute 10–12 trend-friendly band (only an IV-rank<50 soft dot) (§4.6) **[NEW]**
- [ ] Confirm the chosen strike's IV slope (emitter uses the static CE-vs-PE IV gap) (§4.6) **[NEW]**
- [ ] Be aware of expiry-day IV crush (not modelled) (§4.17.5) **[NEW — not automatable]**
- [ ] Read whether the high-vol candle was a bull or bear pump (gate checks only the volume floor) (§4.15.3) **[NEW]**
- [ ] The dual-series basis term-structure nuance is not modelled (front-contract basis sign only) (§4.14.2) **[NEW]**

#### Cues / A-D / Strike / Time / S&R / OIP / FII-DII — §4.7–§4.13 · [gates-strike-sr-fiidii.md](./gates-strike-sr-fiidii.md)
- [ ] Confirm DOW futures + Asian indices + crude + USD all align (§4.7) — **[in-checklist: global_cues_ok]**
- [ ] At 15:15 re-confirm global cues for any overnight/BTST setup (§4.7) **[NEW]**
- [ ] Confirm the delta/premium-picked strike matches the OIP AI-suggested strike (§4.9) **[NEW — not automatable]**
- [ ] Skip strikes carrying heavy CE and PE OI together (only #2 has a both-sides stand-aside) (§4.9) **[NEW]**
- [ ] Verify open=high CE / open=low PE before entry (encoded only for #2) (§4.9) **[NEW]**
- [ ] Confirm the chosen strike's premium hasn't moved >50% and its OI change <50% (only #2, aggregate %) (§4.9) **[NEW]**
- [ ] Prefer the 9:15–10:00 window (only the hard ≥09:45 floor is coded) (§4.10) **[NEW]**
- [ ] Confirm no impending event after 3:30 PM (time cap automated, event-awareness manual) (§4.10) — **[in-checklist: news_clear]**
- [ ] Don't convert a morning scalp into a hold (§4.10) — exit mechanics automated, discipline manual
- [ ] Observe short-covering near S/R between 2:30–3:00 PM on expiry (S/R input absent; time gate is 14:30) (§4.10) **[NEW]**
- [ ] Confirm price is reacting at a marked 1-day/15-min S/R zone (§4.11) — **[in-checklist: level_respected]**
- [ ] Confirm OIP AI direction == pre-market == your own view (the scorer reimplements the dots, not OIP's verdict) (§4.12) **[NEW]**
- [ ] At 3:20 PM confirm your view matches OI Pulse for next-day setups (§4.12) **[NEW]**
- [ ] Glance at India VIX vs recent sessions + intraday direction (VIX dot never fires live) (§4.12) — **[in-checklist: vix_normal]**
- [ ] Read NSE participant-wise OI (FII/Pro majority + change-in-OI) for next-morning bias (no scorer dot reads it) (§4.13) **[NEW]**

#### Session-21..24 additions + open questions — §4.14–§4.17, §7 · [session-additions-and-manual-coverage.md](./session-additions-and-manual-coverage.md)
*This dimension's closing finding: at least 8 manual-only S21–S24 inputs have NO checklist item.* Key gaps:
- [ ] India VIX absolute regime bands + direction dot + VIX/price grid + prev-day compare (§4.14.1) **[NEW]**
- [ ] Index-constituent contribution (top movers, BankNifty top3 ~60%, crude→BankNifty adverse) (§4.14.4) **[NEW]**
- [ ] Pre-open (9:00–9:07) positioning + advances/declines for the morning bias (§4.14.5) **[NEW]**
- [ ] Time-of-day data weighting (prev-day VWAP until 11AM, current after) (§4.14.5) **[NEW]**
- [ ] Refresh Trending-OI strikes to ATM±7 once a >1% move prints (§4.14.6) **[NEW]**
- [ ] Strike/delta by expiry phase (0.7–0.8 weekly-end / ~0.5 day-1) and VIX (§4.14.7) **[NEW]**
- [ ] Options selling / hedging — never naked; SL = straddle VWAP +10–15pt (SPAN-deferred, manual) (§4.14.8) **[NEW]**
- [ ] Straddle combined-premium VWAP-break entry + one-leg mgmt (LIVE-deferred, manual) (§4.15.2) **[NEW]**
- [ ] PSAR distance read (dots close = short-lived / wide = lasting) (§4.15.3) **[NEW]**
- [ ] OSPL volume bull/bear colour attribution + VWAP-proximity sizing (§4.15.3) **[NEW]**
- [ ] Buyer delta up to 0.9 / seller ~0.4 (fixed 0.6–0.7 by design) (§4.15.4) **[NEW]**
- [ ] IV trending-difference band 7–10 pts; IV >40 stay away as a buyer (only a 40/40 stand-aside coded) (§4.15.4) **[NEW]**
- [ ] Open=High operative premium bands (BN 250–550, Nifty 150–350) — StrikePicker uses superseded 100–250 (§4.15.4) **[NEW]**
- [ ] Trending-day regime def (new high/low ~every 45–60 min) (§4.15.5) **[NEW]**
- [ ] Sensex ~3× point-scaling of the SL/target (§4.16.1) **[NEW]**
- [ ] Skip thin-volume Sensex, prefer Nifty; monitor both on a Sensex expiry; treat NSE-vs-BSE pre-open gap as HFT arb (§4.17.2) **[NEW]**
- [ ] Trending-OI 15-strike read + intraday-vs-positional OI agreement + PCR progression (§4.17.3) **[NEW]**
- [ ] FII futures Long/Short-ratio gate (~87–94% short; ~50% crossover = short-covering trigger) (§4.17.4) **[NEW]**
- [ ] IV crash 2nd-half expiry + post-event crush (§4.17.5) **[NEW]**
- [ ] Spot-OI-bar S/R + volume-turning-point S/R (§4.17.6) **[NEW]**
- [ ] §7 open questions: code picked one reading per conflict; the trader has no on-card note an alternate exists (§7) **[NEW]**
- [ ] **At least 8 S21–S24 manual-only inputs have NO `ScalperManualChecks` item — add them.** (§4.14–§4.17) **[NEW — actionable]**

#### Introduction & Terminology — §1 · [intro-terminology.md](./intro-terminology.md)
- [ ] Before ~10:30 eyeball yesterday's VWAP as the defended level (engine VWAP is today-session-only) (§1.2) **[NEW]**
- [ ] Judge trend strength by price separation from VWAP (vwap dot is a boolean side check) (§1.2) **[NEW]**
- [ ] Confirm the 20-period VWMA matches the WMA Siva uses (§1 states no period) (§1.2) **[NEW]**
- [ ] Confirm premium-in-band only matters live (backtest selector ignores it) (§1.2) **[NEW]**
- [ ] Prefer the early 9:15–10:00 window manually (the 9:45 floor is hard) (§1.2) **[NEW]**
- [ ] Hero-Zero gate fires after 14:30, not the doc's 14:00 — minor discrepancy (§1.2) **[NEW]**
- [ ] Be aware of expiry-day ~3 PM gamma acceleration (no detector) (§1.2) **[NEW — not automatable]**
- [ ] Dollar index, Asian markets, crude not in the scalper macro context (§1.1/§4.7) — **[in-checklist: global_cues_ok]**
- [ ] VIX is null in the live scalper path; read level & direction yourself (§1.2/§4.5) — **[in-checklist: vix_normal]** **[NEW for level]**
- [ ] Confirm absolute ATM IV is in a tradeable range (the 10–12 band is not encoded) (§1.2/§4.6) **[NEW]**
- [ ] In an extreme-VIX crash, stand aside (falling-knife rule not enforced; VIX unwired) (§1.2) **[NEW]**
- [ ] Treat ~200% OI / ~300% price spurts as extra-strong (only the 50% floor; no escalation tier) (§1.2/§4.3.2) **[NEW]**
- [ ] One-Good-Trade discipline (§1.1) — **[in-checklist: not_parabolic + clean_setup + regime_ok]** (no daily-trade-count cap **[NEW]**)
- [ ] Verify current exchange lot sizes (doc literals are stale/UNCERTAIN) (§1.2) **[NEW]**

#### Completeness sweep — §1/§5/§7 + orphans · [completeness-sweep.md](./completeness-sweep.md)
Confirms the S22/S21/S24-RESOLVED items that the engine has NOT picked up:
- [ ] S22-RESOLVED Open=High premium bands (BN 250–550 / N 150–350) — StrikePicker still uses superseded bands (§5.2/§7) **[NEW]**
- [ ] S22-RESOLVED Hero-Zero numeric SL (BN ~75 / N ~30) + 3:10 no-move exit + deploy ≤10% of profits (§5.7/§7) **[NEW]**
- [ ] S21-RESOLVED Golden Crossover support-form SL = the Supertrend level (§5.6/§7) **[NEW]**
- [ ] S21-RESOLVED BTST/STBT EOD OI-quadrant gate (deferred in the BTST yaml) (§5.8/§7) **[NEW]**
- [ ] S24 Two-Candle overbought-defer (RSI >85 → cool to ~75 → enter pullback) (§5.1) **[NEW]**
- [ ] S24 Connect-the-Dots RSI booking ladder (book 75–80/85, re-enter lower) (§5.10) **[NEW]**
- [ ] S24 hourly-new-high cadence: no fresh high in ~an hour + ~30-pt box = erosion → exit (§5.10) **[NEW]**
- [ ] S24 no-trade box (ST/VWMA↔VWAP): size down to 1–2 lots (§5.10/§5.6) — **[in-checklist: regime_ok]** **[NEW for box]**
- [ ] S24 Gap Theory volume-direction validity + 30–40-min give-up timer + 50–60-pt SL (§5.4) **[NEW]**
- [ ] S24 Trend-Change counter-trend volume veto (skip if the counter-move carries >125K Nifty volume) (§5.12) **[NEW]**
- [ ] S24 BTST validity (held VWAP/ST into the close) + risk only 5–10% (§5.8) **[NEW]**
- [ ] Straddle combined-premium VWAP entry + LOW IV + combined-VWAP SL (LIVE-deferred) (§5.11/§3.11/§7) **[NEW]**
- [ ] Per-strategy point/percent profit targets (Golden +200–300 BN/+50–100 N; OH ~40–50; Movers ~1–2%) (§5.6/§5.2/§5.3/§7) **[NEW]**
- [ ] FII/DII participant-wise OI directional bias — fiiLongPct fetched but consumed by NO gate/dot and has NO checklist item (§4.13/§4.17.4) **[NEW — actionable]**
- [ ] Global cues beyond Dow (Dollar index, Asian, Crude) (§4.7) — **[in-checklist: global_cues_ok]**
- [ ] §7 doc-ambiguity items (5m vs 3m primary, ≥50% OI day-cumulative vs interval, bearish RSI conflicts) — owner to confirm (§7) **[NEW]**

---

## 4. Automatable gaps — candidate future work

~318 gaps are tagged `automatable=true` (the §1 headline figure). The recurring, highest-leverage themes
(one line each):

- **Wire the VIX feed.** VIX is hard-null in `MarketOiClient.macro` (level + direction), so every VIX
  dot/gate degrades to pass and never confirms or blocks. A VIX market-data endpoint (§12.2 follow-up)
  unblocks §4.5/§4.14.1/§4.17.5 directional + absolute-band rules across all 12 strategies.
- **Consume FII/DII.** `fiiLongPct` is fetched into `Macro` but read by NO gate or dot, and
  `nse_eod_participant_oi` holds participant-wise OI no scorer touches — add an FII Long/Short-ratio dot
  (§4.13/§4.17.4).
- **Promote soft dots to hard gates** where the doc states a precondition: indicator-alignment
  ("ALL soldiers on the far side"), the ≥50% OI imbalance, the Trending-OI cross itself, drastic-ΔOI.
- **Per-strike checks** instead of chain-wide aggregates: the identified strike's own ΔOI%, its premium
  freshness (>50% move), and its IV slope (§3.2/§4.6/§4.9).
- **Encode profit targets / trailing / partial-booking.** No YAML carries a `take_profit`; exits are
  VWAP-cross + time-stop only. Candidates: 1–2% / next-S-R targets, PSAR→Supertrend trailing, the RSI
  75–80/85 booking ladder (§3.1/§3.10 exits).
- **Probability-graded sizing.** `suggested_qty` is a flat `premium_budget`; the doc wants size tied to
  Trending-OI gap %, VWAP proximity, and VIX regime (§2.14/§3.5).
- **Daily risk rails.** `daily_loss_limit` is off by default and `max_daily_loss_pct:2.0` is a DEAD YAML
  key never read by `StrategyCompiler` — seed 0.5%/2%/10–12% caps and a profit target (§2.3/§2.14).
- **Backtest-vs-live band parity.** The backtest premium-replay selector ignores the StrikePicker
  delta/premium bands (picks nearest-strike); align it or document the divergence per strategy.
- **Bearish / short legs.** Most YAMLs are CE-LONG-ONLY; the PE branch / short-premium legs exist in
  code but are unseeded (gated on margin-service SPAN #47). Seeding PE YAMLs closes a large class of gaps.
- **Time-window refinements.** Encode the soft 9:15–10:00 ideal window, the per-strategy 2:30 PM /
  1:30 PM no-fresh-entry bounds, the 30–40-min gap give-up timer, and the 3:10 Hero-Zero no-move exit.
- **Session-resolved numerics.** Apply the S22-RESOLVED Open=High premium bands (N 150–350), the
  Hero-Zero point SL (BN 75 / N 30), and the Golden-Crossover support-form Supertrend SL.
- **SENSEX ~3× point-scaling** of SL/target (the instrument decoupling is automated; the scaling is not).

---

## 5. Audit-quality flags

The adversarial verification pass cleared the great majority of rows but flagged a few. They are
recorded here so a future reader trusts the section files with eyes open. **None overturn a gap
verdict**; the false-coverage items make a gap *less* of a gap (the live path already automates it).

**False-coverage (manual-check overstates the gap) — [gap-theory.md](./gap-theory.md):**
1. *"Post-fill direction must follow the prevailing trend"* (PARTIAL) claims the 1h-Supertrend
   `bias60m` alias "is not gated." **False on the live path** — `bias60m` IS a hard validity AND-term
   (`ScalperConfluenceGate.java:252` → `ConnectTheDotsScorer.java:111,114-115`); only the **backtest**
   lacks it. Treat the prevailing-trend filter as automated live.
2. *"Intraday time filters"* (NONE) says "only a flat 09:45→15:00 window is encoded" and lists the
   11am–1pm exclusion as unautomated. **The live path DOES block 11:00–13:00 and no-fresh-entry-after-15:30**
   (`ScalperGates.java:23-25,37-42`). Only the 9:15–10:00 ideal window and the post-3:30 event lockout are
   genuinely unautomated.

**Invented-claim caveats (minor doc-§ slips, NOT fabricated figures):**
- [btst-stbt.md](./btst-stbt.md): the "advance/decline must match" gap is cited as "§3.8 (breadth
  implied)"; §3.8 has no breadth rule — the >32 threshold actually originates in Morning-Trade §6.9. The
  parenthetical "(breadth implied)" honestly signals an inference; the >32 number is correctly placed on
  the code, not on §3.8.
- [connect-the-dots.md](./connect-the-dots.md): the dollar-index thresholds ">105 / <90" are attributed
  to §3.10; the figures are real but live in the Global-Cues block (doc line 406), not §3.10. Loose
  pointer, genuine numbers. Likewise the "one good trade" heading cites §3.10 but maps to a §3.1 item.
- [risk-framework.md](./risk-framework.md): the Sensex point-SL label wrote "~80" — §2.12 line 269 says
  "wider for Sensex (~80000)" where 80000 is the **index value**, not an 80-pt SL; the genuine Sensex
  point-SL is ~200–250 pts (§2.14). Also "scale lots every 3–6 months" is cited to §2.11 but is numbered
  under §2.12. Mislabels only; the NONE verdicts stand.

All other 14 dimensions reported **no false coverage and no invented claims** — the FULL rows they marked
as automated were each traced to present code (spot-checked file:line in the section files).
