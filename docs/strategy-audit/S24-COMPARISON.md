# S24-COMPARISON — OLD consolidated concept vs S24-only debloated doc

**NEW** = the S24-only, debloated strategy doc (single-session, terse: keeps live-decoded essentials, drops per-session restatement scaffolding).
**OLD** = the consolidated concept = doc **A** (multi-session §3/§5/§6 prose + JSON) + impl **B** (scalper YAMLs + engine gates + audit/disposition corpus) + plans **C** (FU1/FU2) + backlog **D** (work-packages) — where B/C/D were all *derived from A* across sessions S20–S24, so OLD-only rules are prime bloat suspects but many are load-bearing rules S24 silently assumes.

This document folds every pass-2 adversarial audit correction (missed rules added, misattributions moved, classification errors re-bucketed, count fixes) into the final tabulation. Figures are quoted from the diffs/audits, never invented.

---

## 1. Executive summary

**Is the OLD side bloated? Yes — but unevenly, and less than the raw pass-1 counts implied.** The OLD side is genuinely over-specified in three places: (a) the per-session *restatement* layer (§4.14/§4.15/§4.17 "Session-N Additions" wrappers, §5 Strategy-Evolution log, the Changelog ledger); (b) the older-session illustrative figures and tool-UI primitives (crore-OI examples, ~400pt targets, direction-change arrows, OSPL/OIP-AI feeds, premium-band debates); and (c) the entire *derived automation roadmap* (FU1 16-check card, FU2 promotions, the ~246-gap backlog) that has no place in a single-session strategy doc. But a hard core of OLD-only rules are **not** bloat — they are load-bearing execution rails (VWAP-as-stop, structural SLs, RSI overbought caps, the ≥50% OI gates, the futures-OI confirmation, the FII participant matrix) that the terse S24 silently assumes rather than deprecates.

**Quantified totals (audit-reconciled across all 18 scopes):**

- **Total OLD-only rules: 283** (12 strategies 204 + 6 shared 79).
- **Of which `bloat_safe_drop` (safe to defer to S24): 72** — roughly **25%** of OLD-only content is genuine droppable bloat.
- **Of which `keep_s24_silent` / `s24_simplified_equivalent` / `uncertain`: 211** — roughly **75%** must be retained (S24 is terse, not contradicting).
- **Total S24-only (new or sharpened): 95** rules across the 18 scopes — almost all SHARPENING/RE-EMPHASIS of existing concepts (concrete 2025 numbers, named thresholds), with a smaller set genuinely new (Day-17/20/21 live refinements).
- **Total drifted shared values: 50** — the dangerous silent value changes (RSI bands, delta floors, VIX band edges, point-SLs, daily-loss caps, DXY cutoff); the **45** material cross-scope drifts are tabulated in §3, the remaining 5 are finer intra-scope nuances noted in their per-strategy "Key drifts" lines.

**Headline recommendation: `simplify_partial`.** Shed the per-session restatement scaffolding, the derived plan/backlog corpus, and the named older-session illustrative figures (safe, low-risk). **Do not** debloat the per-strategy execution rails that S24 assumes silently — and reconcile the 50 drifted shared values *before* trimming, because several (point-SLs, daily-loss cap 2-3% vs 10-12%, delta 0.6-0.7 vs ≥0.7, premium bands) are live engine values that diverge from the S24 doc. Four scopes are explicitly `old_is_richer_keep` (Morning Trade, Straddle, Trend Change, plus Market-Movers-adjacent) where S24 is reference-only and OLD is the canonical home — those are *not* debloat targets.

---

## 2. Statistical rollup table (audit-reconciled)

| Scope | OLD-only | of which bloat-safe-drop | S24-only | shared | drifted | simplifyVerdict | audit accuracy% |
|---|---:|---:|---:|---:|---:|---|---:|
| S1 Two Candle Theory | 20 | 7 | 2 | 14 | 0 | simplify_partial | 86 |
| S2 Open=High / Open=Low | 22 | 8 | 7 | 17 | 0 | simplify_partial | 88 |
| S3 Market Movers | 19 | 5 | 4 | 12 | 4 | simplify_partial | 86 |
| S4 Gap Theory | 17 | 5 | 4 | 11 | 1 | simplify_partial | 88 |
| S5 Trending OI Crossover | 18 | 5 | 7 | 11 | 3 | simplify_partial | 88 |
| S6 Golden Crossover | 15 | 5 | 4 | 12 | 5 | simplify_partial | 82 |
| S7 Hero-Zero (Expiry-Day OI) | 13 | 2 | 5 | 14 | 3 | old_is_richer_keep | 72 |
| S8 BTST / STBT | 19 | 5 | 5 | 8 | 5 | simplify_partial | 84 |
| S9 Morning / Opening Trade | 15 | 0 | 6 | 9 | 3 | old_is_richer_keep | 82 |
| S10 Options Scalping (Connect the Dots) | 13 | 4 | 10 | 11 | 5 | simplify_partial | 82 |
| S11 Straddle (Long & Short) | 15 | 0 | 3 | 6 | 0 | old_is_richer_keep | 88 |
| S12 Trend Change | 18 | 4 | 3 | 6 | 0 | old_is_richer_keep | 88 |
| **Shared S1-risk** (Global Risk Mgmt) | 15 | 3 | 5 | 10 | 2 | simplify_partial | 86 |
| **Shared S2** (Terms & Indicators) | 10 | 4 | 6 | 19 | 8 | simplify_partial | 82 |
| **Shared S3** (OI / Quadrants / Trending-OI) | 11 | 5 | 4 | 8 | 1 | simplify_partial | 88 |
| **Shared S4** (IV / VIX / Global / FII-DII) | 16 | 1 | 9 | 9 | 2 | old_is_richer_keep | 88 |
| **Shared S5** (Strike / S&R / Time / Sensex) | 12 | 1 | 6 | 8 | 5 | simplify_partial | 88 |
| **Shared S6** (Cross-cut / tooling / accretion) | 15 | 8 | 5 | 9 | 3 | simplify_safe | 86 |
| **TOTALS** | **283** | **72** | **95** | **194** | **50** | simplify_partial | **85 (avg)** |

> Notes on reconciliation: counts are post-audit. S1 +3 missed oldOnly + 1 bearish-rider split, "1st+3rd substitute" and "VIX/global-cues (global half)" re-bucketed bloat→keep. S2 +1 premium-floor shared row, delta 0.6-0.7 re-bucketed bloat→keep. S3 oldOnly 18→19 net (3 missed added, 1 double-count removed; drift 5→4). S4 bloatSafe 5→5 (count-of-array fix). S6(GC) +5 missed oldOnly, 4 s24-ledger items kept as s24-introduced, bearish-RSI ambiguity bloat→keep, fake changelog/cheat-sheet cites stripped. S7 monthly-expiry & both-sides-LU moved to shared, 3 bloatSafe→keep gaps, +1 missed. S9 all 3 bloatSafe→keep (disposition contradicts drop) → bloatSafe 0; Day-20 rule moved shared→s24Only. S10 ≥50% OI gate moved oldOnly→shared, ATM±3 bloat→s24-equiv. S11 S23-nuance bloat→keep → bloatSafe 0. S12 oldOnly 21→20 fix +2 missed, edge-case bundle split. Shared-S2 A/D + IV-pair moved oldOnly→shared. Shared-S4 phantom "VIX historical anchors" removed from oldOnly (S24-only), FII-validity bloat→keep. Shared-S5 OIP-AI-strike bloat→keep. Shared-S6 session-addition containers split drop-vs-keep.

---

## 3. Drift table — every shared rule with drift=true (the dangerous silent value changes)

| # | Scope | Rule | OLD value | S24 value | Which to trust |
|---|---|---|---|---|---|
| 1 | S3 Market Movers | Strong-short variant qualifier | 15-day low + "completely bearish data" (S22) | >15% fall / 15-day low + Open=High | S24 (latest; explicit magnitude) |
| 2 | S3 | Time-of-day floor (coverage) | explicit "after 09:45" + YAML 09:45 | no explicit floor (move "by 9:45" framing) | OLD/engine (09:45 enforced) |
| 3 | S3 | Daily-RSI cool-off cap | bull cap 75 / bear floor 40 (S22) | bull >70 no-long / 67-68 buy / ~80 book / 25-30 short-caution | S24 (tightens 75→70) |
| 4 | S3 | Instrument vehicle | stock futures OR cash | futures only (drops cash) | S24 (futures-only) |
| 5 | S4 Gap Theory | Operating window | after 9:45, ideal 9:15-10:00, avoid 11-1 | suite window 9:40-2:30 | Reconcile (9:45 vs 9:40 anchor) |
| 6 | S5 Trending OI | RSI confirm band | §3.5 card bull <75 / bear >25 | long 50-75 / short 40-25 | S24 (adopts grid/golden bands) |
| 7 | S5 | Gap-quality gate measured quantity | ≥50% on CHANGE-in-OI imbalance (+ 20-30% sub) | ≥50% (50-100%) call-vs-put OI GAP | OLD/engine measures ΔOI; S24 prose loose |
| 8 | S5 | Operating window bounds | after 9:45 / best 10-11:30 / avoid ~1:30-2 | hard 9:40-2:30 | Reconcile (end-time 1:30-2 vs 2:30) |
| 9 | S6 Golden Crossover | Bullish RSI card | §3.6 card RSI <75 (no lower bound) | band 50-75 | S24 (promotes 50 floor) |
| 10 | S6 | Bearish RSI card | RSI >25 (+ UNCERTAIN matrix <25) | band 40-25 | S24 doc; engine PE 20-40 (UNCERTAIN open) |
| 11 | S6 | Rarity / frequency | ~3-4 times per month | ~3-5 times per month | S24 (minor) |
| 12 | S6 | Time window framing | after 9:45 / avoid 11-1 / best 10-11 | general 9:45-2:30 | S24 (house window) |
| 13 | S6 | Engine RSI band override | CE 60-80 / 40-60 dead (code) | doc 50-75 / 40-25 | UNCERTAIN_OWNER (doc-vs-code) |
| 14 | S7 Hero-Zero | Execution window | after 2pm, observe 2:30-3, hard close 3:20 | ~2:45-3:15 (deck ~2:30-3); engine 14:30-15:20 | S24 doc; engine matches neither exactly |
| 15 | S8 BTST | Overnight sizing | S23 10-20% of capital | 5-10% of capital | S24 (latest, tightened) |
| 16 | S8 | Overnight stop-loss | YAML/engine fixed 50% premium | NO explicit overnight SL (validity gate + cap) | Reconcile (50% vs none) |
| 17 | S8 | Carry-validity condition | close-at-day-high/low + OI quadrant | no-VWAP/ST-breach + hold into close | S24 (price-action gate) |
| 18 | S8 | Instrument vehicle | index F&O + stock/cash carry leg | index options only | S24 (index-only) |
| 19 | S9 Morning Trade | Scalp worked example | 9:16→9:18 / 250pts/2min | 9:15:02→9:16:13 / >1%/~1min | Either (illustrative) |
| 20 | S9 | SL reference level | first-candle low/high (engine) | strike's prior-day VWAP / bounce bottom | Engine uses OLD; reconcile |
| 21 | S9 | Strike/delta selection | delta 0.6-0.7 + N 100-250/BN 250-400 + ATM±3 | 2-3 strikes from settle, slight ITM, delta ~0.80 (estimation) | Reconcile (purpose differs) |
| 22 | S9 | Morning RSI band | morning-specific CE 60+/not>75/40-60 no-trade | inherits framework 40-50 no-trade / buy 50-75 | Reconcile (S24 defers to §3.10) |
| 23 | S10 Connect-the-Dots | RSI entry band (doc vs engine) | doc 50-75 / no-trade 40-50 | engine CE 60-80 / no-trade 40-60 | UNCERTAIN_OWNER |
| 24 | S10 | Buy-side delta | 0.6-0.7 (engine) | ≥0.7 + VIX/expiry conditioning | S24 (raises floor); engine on OLD |
| 25 | S10 | Stop-loss philosophy | structural 1st-candle + 5pt gap-trail | wide point SLs (N 50-60/BN 100/Sensex 200-250) | Both (different SL modes) |
| 26 | S10 | Intraday window close | after 9:45, avoid after 3:30 | 9:45-2:30 (post-2:30 = next-day) | S24 (tightens close) |
| 27 | Shared-S2 | Parabolic SAR notation | 0.02, 0.2 (two-value; engine) | 0.02, 0.02, 0.2 (three-value) | Engine = OLD two-value |
| 28 | Shared-S2 | RSI no-trade zone | 40-60 (engine) | 40-50 | Engine = OLD (40-60) |
| 29 | Shared-S2 | RSI buy floor | >60 (§4.2/engine) | buy 50-75 | UNCERTAIN_OWNER (60 vs 50) |
| 30 | Shared-S2 | IV 6-strike trending gap | >=10pt (S20) / 7-10pt (S22); engine 0.10 | no figure (glossary) / ~8-10pt (cheat) | S24 8-10 sits between; engine 10 |
| 31 | Shared-S2 | Buyer delta baseline | 0.6-0.7 (engine) | ≥0.7 + phase conditioning | S24 raises floor; engine on OLD |
| 32 | Shared-S2 | India VIX upper bands | 15-16 / 17+ (S21) | 15-18 / 18-20+ / 20-25+ | S24 (finer, latest) |
| 33 | Shared-S2 | Advance/Decline breadth | explicit >32 (engine FULL) | breadth row, drops >32 number | Engine = OLD (>32 live) |
| 34 | Shared-S1-risk | Bank Nifty point-SL | ~75 pts (S22) | ~100 pts (S24) | S24 (latest) |
| 35 | Shared-S1-risk | Nifty point-SL | ~30 pts (S22) | ~50-60 pts (S24) | S24 (latest) |
| 36 | Shared-S1-risk | Daily loss cap | 2-3% (S20 rule13; YAML 2.0) | 10-12% single-day (S24) | Reconcile (doc 10-12 vs engine 2) |
| 37 | Shared-S3-oi | Monthly-expiry OI handling | confirm both positional+intraday (S21) | IGNORE the OI data (engine suppresses) | S24/engine (suppress) |
| 38 | Shared-S4 | IV directional gap floor | 10pt (S20) / 7-10pt (S22); engine 0.10 | 8-10pt | S24 8-10 between OLD anchors |
| 39 | Shared-S4 | India VIX upper bands | 15-16 / 17+ (S21) | 15-18 / 18-20+ / 20-25+ | S24 (latest) |
| 40 | Shared-S5 | Buy-strike delta | 0.6-0.7 (engine, picks 0.65) | ≥0.7 floor + scaling | S24 raises floor; engine on OLD |
| 41 | Shared-S5 | Premium band | N 100-250/BN 250-400 (engine) vs N 150-350/BN 250-550 (S22 doc) | no band; qualitative "avoid 120-130, 150+" | S22-operative band (backlog PR-2 swaps engine) |
| 42 | Shared-S5 | Time rails | after 9:45 / midday 11-13 block / 15:30 cap (engine) | 9:45-2:30 soft 2:30 cutoff | Reconcile (engine keeps midday block) |
| 43 | Shared-S5 | Sensex point-scaling | ~3x (exact % figures) | ~3-4x (+ single-candle ~5000pt note) | S24 (wider/latest) |
| 44 | Shared-S6 | India VIX bands | 10-11/12-14/15-16/17+ | 10-11/12-14/15-18/18-20+/20-25+ | S24 (latest) |
| 45 | Shared-S6 | Dollar Index cutoff | >105 negative / <90 ideal (§3.10 primer) | >100 = FII selling / ideal 92-93 | S24 (latest live read) |

> This table tabulates the **45** material cross-scope drifts. §2's per-scope `drifted` column sums to **50**; the remaining 5 are finer intra-scope value nuances counted in their per-strategy "Key drifts" lines but not promoted here.
>
> The most operationally dangerous drifts are the ones where the **live engine value differs from the S24 doc**: #28/#33 (engine RSI 40-60 + breadth >32), #31/#40 (engine delta 0.6-0.7 vs doc ≥0.7), #36 (engine daily-loss 2% vs doc 10-12%), #41 (engine premium 100-250/250-400 vs S22-operative 150-350/250-550). Reconcile these in code, not just the doc.

---

## 4. Per-strategy sections (12)

### S1 — Two Candle Theory (`two_candle`) · simplify_partial · audit 86%

**OLD-only (20):**

| Rule | classification | source | sessionTag |
|---|---|---|---|
| Strike cue: slightly-ITM delta 0.6-0.7, ATM±3, premium 100-250/250-400 | keep_s24_silent | §3.1/§6.1 + YAML width:3 | S20 |
| Averaging ladder 3%/+7%/≤20% + 25%/more/MAX@VWAP | bloat_safe_drop | §3.1/§5.1/§6.1 | S20/S22 |
| 1st+3rd may substitute for light 2nd candle | keep_s24_silent *(was bloat)* | §3.1 S21(a) | S21 |
| One ST/VWAP-rejection trade per event | bloat_safe_drop | §3.1 S21(c) | S21 |
| Golden-Crossover-combo confluence | bloat_safe_drop | §3.1 S21(d) | S21 |
| Full-body 2nd candle + >50% Trending-OI sizing tie | keep_s24_silent | §3.1 S21(g) | S21 |
| RSI-40 bear trigger + 47K near-miss anecdote | s24_simplified_equivalent | §3.1 S22 | S22 |
| Midday-avoid + ~45min-1hr recurrence | keep_s24_silent | §3.1 filters | S20 |
| VIX + global-cues (global-cues half manual-covered) | keep_s24_silent *(was bloat)* | §3.1 filters | S20 |
| Multi-TF RSI cross-check (5m + Daily) | keep_s24_silent | §3.1 filters | S20 |
| Alternate SL = VWAP when extended | keep_s24_silent | §3.1 Exit | S20 |
| Target 1-2% + PSAR→ST trailing + VWAP-break-with-volume exit | keep_s24_silent | §3.1/§6.1 | S20 |
| Trading-zone S/R + avoid-parabolic | keep_s24_silent | §3.1 #7 | S20 |
| IV Desirable (rising bull / falling bear) | bloat_safe_drop | §3.1 filters | S20 |
| Sensex ~3x point-scaling (signal-NIFTY/exec-SENSEX) | keep_s24_silent | §5.1 S23 | S23 |
| No PE YAML seeded (automation-coverage gap) | keep_s24_silent | §3.1 bearish | S20 |
| **+ Pull-back-to-WMA/ST add/re-entry** *(audit-added)* | keep_s24_silent | §3.1 #8 L376 / §6.1 L1804 | S20 |
| **+ Trail aggressively as RSI nears extreme (S22c)** *(audit-added)* | keep_s24_silent | §3.1 L352c / §5.1 | S22 |
| **+ Large 1st candle → SL = 1st-high or 2nd-low** *(audit-added)* | keep_s24_silent | §3.1 S21(b) L350 | S21 |
| **+ Bearish RSI<20 oversold-skip → prefer ST-rejection** *(split from shared)* | keep_s24_silent | §3.1 bearish L381 | S20 |

**S24-only (2):** Overbought DEFER (RSI>85, cool to ~70-80, enter pullback candle) — *s24-introduced, also in OLD §5.1 evolution row*; Trader-type SL split (scalper trails prev-candle, positional keeps 1st-candle) + deep-SL sizing — *s24-introduced*.

**Key drifts:** none (all 14 shared rules byte-identical incl. 50K/125K volume, ST(10,2)/VWMA20/PSAR/RSI14, 1st-candle SL, bearish mirror).

**Verdict:** Keep the core + the 3 management rules S24 dropped; drop only the averaging ladders, per-event-rejection cap, Golden-X combo, IV Desirable.

---

### S2 — Open=High / Open=Low (`open_high_low`) · simplify_partial · audit 88%

**OLD-only (22, bloat-safe 8):** S22 premium bands 250-550/150-350 (bloat); strike 0.6-0.7 delta — *keep_s24_silent (S24 cheat retains buyer δ≥0.7; engine FULL)*; RSI overbought caps <75/80 (keep); badge ≥90% (s24-equiv); 9:15-10:00/10:30 window (s24-equiv); futures LB/SC + option-OI build-up confirm (keep); VIX direction (bloat); IV per-strike (bloat); 30-50pt target (keep); ~5pt-inside exit (s24-equiv); trail-up-from-OH (keep); **VWAP-as-stop (keep — most-implemented, hard in all 3 YAMLs)**; ATM±3 (s24-equiv); two-sided OH mechanics (bloat); >50%-reset timing (keep); S21/S22 skip sub-cases (bloat); Trending-OI 5-15min tooling (bloat); beginners 1-5% (bloat); Sensex ~3x via Nifty (keep); trend-alignment precondition (keep); **+ OH-not-hit ≠ creators losing (audit-added)**.

**S24-only (7):** ≥50K on ~3 consecutive recovery candles + 70-80-90% ladder; Day-20 directional-change precondition (quadrant); round strikes weigh more; abort = >50% premium-fall + >50% OI-rise crossover; ~90% reverse-on-tag; Day-6 baseline ≥3-strikes confirm; Day-14 positional-override worked example.

**Key drifts:** none in shared core (all 17 shared incl. ≥3-strikes-each-side+futures, 50%-no-fall, 50%-OI-cap, 50K/125K, 30% cap, both-sided ignore, probability matrix, 290/300 example). **Audit add:** premium-FLOOR "avoid <130 / prefer 150+" survives into S24 cheat = shared.

**Verdict:** Drop the numeric premium-band debate, VIX/IV direction, tooling lists, S21/S22 skip sub-cases. Keep VWAP-as-stop, futures+option-OI confirm, 30-50pt target, trend alignment.

---

### S3 — Market Movers (`market_movers`) · simplify_partial · audit 86%

**OLD-only (19, bloat-safe 5):** Large-cap-only (keep); operator low-volume trap (keep); STBT overnight OI-at-high+price-at-low (keep); next-day continuation (bloat); 5-10%+ overshoot (bloat); top-constituent weightage HDFC 29.46% (bloat); S23 open-type-opposite (s24-equiv, *cite §5.3 not phantom §3.3*); Sensex-via-Nifty heavyweights (bloat, *cite §4.16/§4.17 not §3.3*); alternative >1% entry (keep); radar-staging 1-2d→3-4d→8-9d (keep); OI-Spurt 4-quadrant (keep — *was bloat; engine scores it, backlog packages it*); New-High/Low panel (bloat); both-sides-OI avoid (keep); adverse-volume exit (keep); SL/time/scaling 1st-candle ref (s24-equiv); indicator settings + Desirables (keep); **+ review past 2-3 days' EOD (audit-added, keep)**; **+ dual-name OL-Gainers+OH-Losers cue (audit-added, keep)**; *(15-day-extreme double-count removed → lives in shared drift)*.

**S24-only (4):** Futures-only strict NO stock options (sharpening); daily-RSI >70/67-68/80/25-30 (sharpening); 2025 liquidity reads Maxhealth 150K/PayTM 100K (new); "3-4%+ already → 1-2% more" digest (re-emphasis).

**Key drifts (4):** strong-short qualifier (15d-low+bearish vs >15%+OH); 09:45 floor coverage; daily-RSI 75/40 vs 70/67-68/80; instrument vehicle (futures|cash → futures-only).

**Verdict:** Drop next-day-edge, overshoot colour, constituent-weightage, New-High/Low panel. Keep large-cap-only, operator-trap, STBT, OI-Spurt, radar-staging.

---

### S4 — Gap Theory (`gap`) · simplify_partial · audit 88%

**OLD-only (17, bloat-safe 5):** 3pt/60-tick significance floor (keep — engine MIN_POINTS=3); high/low gap variant (bloat); after-9:45 window (keep); RSI entry band + 40-60 no-trade (keep); SuperTrend-level SL — *uncertain (was keep; S24 prescribes competing structural+pts SL, engine defers)*; pre-gap-candle SL + 5pt trail (keep — engine StructuralStop.GAP_TREND); strike δ0.6-0.7/premium (keep); counter-trend toward-gap scalp (bloat); R:R 1:2.5 / 1:1.6-1.7 targets (bloat); higher-TF/option-gap-no-fill exclusion (keep); S21 lot-size scaling (bloat); deep-ITM gap-day note (uncertain); VWMA20 named (keep); index-FUTURE 3m chart (keep); gap-up seek-support (bloat); **+ positional/EOD-OI night-risk note (audit-added, bloat)**; **+ trendline-support entry + aggressive/conservative trendline SL (audit-added)**.

**S24-only (4):** Volume-DIRECTION validity (with-volume up = valid); bearish texture / fill-without-volume read (Day 17); 30-60min time-box + ~50-60pt SL (Day 21); 99% live fill-rate framing.

**Key drift (1):** operating window 9:45/9:15-10:00 vs suite 9:40-2:30. **Audit fix:** magnet-rule oldValue mis-cited (§5.4 S24 L1679, not §3.4/S23).

**Verdict:** Drop high/low variant, counter-trend scalp, R:R ratios, S21 lot-sizing, gap-up seek-support. Keep the 3pt floor, pre-gap SL, VWMA20, future-chart mapping (all engine-load-bearing).

---

### S5 — Trending OI Crossover (`trending_oi_crossover`) · simplify_partial · audit 88%

**OLD-only (18, bloat-safe 5):** OI Sentiment-slope co-confirmation (keep); ≥50% ΔOI-change filter + flat-OI caveat — *split: ΔOI gate = shared/drift (engine FULL); flat-OI degrade-to-PASS caveat = oldOnly keep (engine inverts)*; volume 50K/125K (keep); 1-2% target (keep); RSI-extreme trailing exit (keep — *was bloat; audit MISSED-row + AUTOMATE_PKG*); VWAP-decisive low-prob-near-VWAP (keep); failed-cross two-signed-delta test (keep); strike housekeeping ATM±7 (bloat); direction-change arrows (bloat); EOD >50% next-day bias (bloat); futures-OI LB/SC quadrant (keep); Bank Nifty / **Fin Nifty only = oldOnly** *(Bank Nifty moved to shared)*; S21 best 10-11:30 window (uncertain); 60m/15m=5×3m timeframe doctrine (s24-equiv); probability-graded sizing (keep); pairs-with-Two-Candle cadence (bloat); end-of-series ambiguity (bloat); **+ HIGH-probability strength grade drastic-fall+opposite-build+SC (audit-added, keep)**; **+ price-corroboration precondition (audit-added, keep)**.

**S24-only (7):** 15-strike (7+7+ATM) read; intraday+positional must agree (5cr/10-12cr); crossover-not-required-on-wide-gap (Day 8); fake-crossover exit + 2-3-crosses=avoid-day; OI-sentiment color code (Day 15); trending-down expiry read (11-12cr/4-5cr); new-series contradiction discipline (Day 20).

**Key drifts (3):** RSI band (<75/>25 vs 50-75/40-25); gap-quality measured quantity (ΔOI vs gap); operating window. *(Bank Nifty re-bucketed shared; ≥50% rule de-duplicated.)*

**Verdict:** Drop ATM±7 housekeeping, direction-arrows, EOD next-day, end-of-series, cadence note. Keep VWAP-decisive, failed-cross test, futures-OI quadrant, strength-grade, sentiment-slope.

---

### S6 — Golden Crossover (`golden_crossover`) · simplify_partial · audit 82%

**OLD-only (15, bloat-safe 5):** Bearish RSI ambiguity card >25 vs matrix <25 — **keep/UNCERTAIN (was bloat — open owner-blocker for PE-side automation; S24's 40-25 does not resolve >25-vs-<25)**; drastic two-sided ΔOI confirm (keep); no-body/partial crossover volume nuance (keep); strike δ0.6-0.7/premium/ATM±3 (keep); targets 100-150/50-70 layer (s24-equiv); SuperTrend-level support-form SL (s24-equiv→keep — backlog AUTOMATE_PKG); higher-TF ST(7,3) 15m/1h bias (keep — engine hard gate; verify vs S24 §4); engine RSI 60-80/20-40 override (uncertain); "stronger in Bank Nifty" (bloat); rarity ~3-4/month (s24-equiv); **+ directional OI build-up read (audit-added, keep)**; **+ RSI-exhaustion caveat S21(e) (audit-added, bloat)**; **+ first-candle-high resistance edge (audit-added, bloat)**; **+ two-crossovers-in-a-day rule (audit-added, bloat)**; **+ no-VOLUME crossover edge (audit-added, bloat)**.

**S24-only (4, all s24-INTRODUCED, also logged in OLD §5.6 evolution ledger — NOT shared-corpus):** Clustered-indicators warning; dip-buy pyramiding 20%@ST/80-90%@VWAP, SL 30-40pt; no-trade-zone ST↔VWAP range; reused-deck provenance caveat. *(Bullish/bearish RSI bands de-double-booked into shared drift; bearish-block de-double-booked.)*

**Key drifts (5):** Bullish RSI card (<75 → 50-75); bearish RSI card (>25 → 40-25); rarity (3-4 → 3-5/month); time window framing; engine RSI 60-80 vs doc.

**Audit caution:** the diff cited a non-existent "changelog" file and a "cheat-sheet §6" not in this repo — **strip those fake citations** (real loci: OLD §3.6 "Session 22 update" L734, §5.6 S24 L1699). Do NOT drop the bearish-RSI ambiguity.

**Verdict:** Drop the BankNifty colour, old target layer, S21-edge anecdotes. Keep the no-body/drastic-OI confirms, ST(7,3) bias, directional OI build-up, and the (unresolved) bearish-RSI ambiguity.

---

### S7 — Hero-Zero (Expiry-Day OI) (`hero_zero`) · **old_is_richer_keep** · audit 72%

**OLD-only (13, bloat-safe 2):** 50%-premium SL (keep — every YAML); hard 3:20 square-off (keep); **index-scaled point SL BN~75/N~30 — keep, automatable gap (was bloat — S22-resolved risk-floor, disposition AUTOMATE_PKG; DANGEROUS false-drop)**; 2:30-2:45 timing (s24-equiv); **3:10 no-move exit — keep, automatable gap (was bloat — disposition AUTOMATE_PKG)**; one-strike-below-SC strike + avoid-10-14 (keep — shipped StrikeSelector); BOTH OI>50% AND price>50% (keep — realMove() gate); no-PE-when-CE-discount caution (keep); RSI >75 — **keep (was bloat — ENCODED/FULL at 80; carry 80-vs-75 drift)**; **round-strike double-zero-pin SPLIT: keep pin-warning (KEEP_MANUAL_NEW), drop scale-in/both-sides tactics**; IV-flat-both-sides no-trade (keep); prep ritual 5±/round/3-4-day OI (s24-equiv); **+ cross-side LB/SB double-confirmation directional pattern (audit-added, keep)**; **+ LU-vs-SC discriminator (audit-added, keep)**.

**S24-only (5):** Size ~10% of PROFITS never capital; direction by second-half flow; Day-17 per-side OI thresholds (CE≥50%/PE~70-78%+85%); premium-level low-vs-high ITM bifurcation; VIX+OI two-sided framing (Day 21). *(Monthly-expiry-ignore moved oldOnly→shared; both-sides-LU de-double-listed → shared.)*

**Key drifts (3):** execution window; OLD point-SL set (BN~75/N~30) vs S24 deep-SL set (N~50-60/Sensex~200-250/BN~100) — **keep both, do not conflate**; budget flat-2000 vs ~10%-of-profits.

**Verdict:** OLD is materially richer. Keep nearly everything; the only clean drops are the BTST-borrowed RSI *number* (gate itself stays) and the scale-in/both-sides expiry tactics.

---

### S8 — BTST / STBT (`btst_stbt`) · simplify_partial · audit 84%

**OLD-only (19, bloat-safe 5):** **OI four-quadrant SC=Q3/LB=Q1/SB=Q2/LU=Q4 — keep_s24_silent (was bloat — AUTOMATE_PKG; muted-on-history not deleted; S24 reference-only never re-taught)**; **3:15pm Futures-OI direction — keep (was bloat — AUTOMATE_PKG, futOi factor exists)**; **3:15pm Option-OI Trending+Sentiment — keep (was bloat — AUTOMATE_PKG)**; 3:20pm OIP-AI alignment (bloat — no feed); "320 Strategy" wide overnight SL (bloat); global cues 3:15 (keep — global_cues_ok shipped); India VIX day-low/market-high (keep); **RSI split: >60/<40 example bands (bloat) + directional not-overbought>75/oversold gate (keep — AUTOMATE_PKG rsiBand)**; daily-RSI>75 stock hard limit (keep — stock scope); strike/premium leg ATM±3/δ0.6-0.7 (keep); option legs by side Buy-Fut/Sell-PE/Buy-CE (keep); stock n-day-low variant (bloat); 50%-premium SL + avoid-Friday + 1-night (keep); next-day continuation prev-day-high/low (s24-equiv); morning re-confirm 4-way (s24-equiv); 2:30-3:00 SC/SB window (bloat); strike S/R Put-support/Call-resistance (bloat); S23 premium-behaviour ~80-90% crush / ~50%-run-up / index-only-never-OTM (keep).

**S24-only (5):** Validity gate (no-VWAP/ST-breach + hold into close); profit-protection override (≥80-90% recovered → square off); news-rally distrust (2-3 days follow-up); near-expiry last-30-min trap; hit-rate honesty 6-7/10.

**Key drifts (5):** sizing 10-20%→5-10%; SL 50%-premium→none; carry-validity OI-quadrant→price-action; vehicle index+stock→index-only; exit timing (no drift, identical).

**Audit caution:** S24 is **reference-only** for BTST (§2 roster "deck-taught" is the documented mislabel). The OI-confluence layer is NOT bloat — it is muted-on-history AUTOMATE_PKG work; judge on forward paper with real captured OI.

**Verdict:** Drop the OIP-AI/320 feeds, stock n-day-low, 2:30-3:00 ritual, strike-S/R map. KEEP the OI-quadrant + 3:15 Futures/Option-OI confluence (re-classified from bloat).

---

### S9 — Morning / Opening Trade (`morning_trade`) · **old_is_richer_keep** · audit 82%

**OLD-only (15, bloat-safe 0):** Rejection-wick entry trigger (keep — the core fire trigger); 2nd-candle-breaks-1st formation (keep); EOD-formed prior-evening view (keep); convincing-close precondition (keep); OIP-AI 9:11 signal + 9:18 exit (uncertain, lean keep on timing); prior-day 3:20pm OI alignment (keep); OIP-AI-matches-premarket (keep); breadth adv>32/dec>32 (keep — FULL); gap-down+oversold RSI cool-off (keep); VWAP <10:30 suppression + prior-day-VWAP-as-level (keep); profit-trail-to-breakeven (keep); **RSI secondary exit <30 — keep (was bloat — disposition AUTOMATE_PKG; FALSE-DROP)**; **Open=High exit-trigger/CE-hedge — keep (was bloat — disposition KEEP_MANUAL_NEW)**; **add-only-around-prev-close — keep (was bloat — disposition KEEP_MANUAL_NEW)**; **+ >50% OI-direction-change for convincing view (audit-added, keep)**.

**S24-only (6):** Pre-market settle ~9:07-8 / ignore ±200pt swings; gap-read for side (300-400 gap-down no-put / 30-40 gap-up short-once); pre-market heavyweights +2-4% / ~80-100 Nifty pts (NEW); sizing ~10-20% + prev-day-profit-as-SL + delta~0.80 estimation; slight-ITM → rotate-higher rotation; *(Day-20 gap-up-overbought moved shared→s24Only)*.

**Key drifts (3):** scalp example (9:16-18 vs 9:15:02-16:13); SL level (first-candle vs prior-day-VWAP); strike/delta (0.6-0.7 vs 0.80-estimation).

**Audit caution:** all 3 bloat_safe_drop calls were FALSE-DROPS contradicting the disposition → bloatSafe **0**. Nothing in this scope is droppable bloat.

**Verdict:** OLD is the canonical home (S24 ships no deck). Keep everything; S24 only adds quantification.

---

### S10 — Options Scalping / Connect the Dots (`scalping_framework`) · simplify_partial · audit 82%

**OLD-only (13, bloat-safe 4):** 2-green/red + indicators-below + 3rd-candle (s24-equiv — routed to §3.1); **ATM±3 + premium bands — s24-equiv (was bloat — strikes.width:3 is live-armed FULL; do not strip)**; 1st-candle structural SL + 5pt trail (keep); RSI 90%/10% book ladder (keep); VWAP-distance sizing (keep); RSI multi-TF 5m+Daily (keep); support-trade pullback-entry (keep); OSPL/AI signals (bloat); profit-as-SL ₹25-30k/1-lakh (s24-equiv); Trending-OI+PA feature §4.15 (bloat); ST(7,3) intraday-mode (s24-equiv); 1-night/avoid-Friday (bloat); VIX four-scenario raw-deck label (keep). *(≥50% Call-vs-Put OI gate MOVED oldOnly→shared — S24 §4.1 carries it; the "active FU2 promotion" rationale was factually wrong.)*

**S24-only (10):** Full RSI zone table OB80/OS20/40-50-no-trade/buy50-75; hourly-new-high cadence 20→60→90→110pts; support-strength tiering weak/strong/very-strong; intraday-VWAP switch ~10:30; discount-premium read; breadth+heavyweight cluster; recycle-profit re-enter-lower; indicators-far-from-candles=avoid (**NEW, not re-emphasis**); VIX live-clarification; reused-deck provenance guard.

**Key drifts (5):** RSI doc 50-75 vs engine 60-80; delta 0.6-0.7→≥0.7; structural-SL→wide-point-SL; 9:45-3:30→9:45-2:30; (+ the moved ≥50% OI gate is shared not drift).

**Audit fix:** ADD the definitional "Connecting-Dots AGGREGATE must read Bullish/Bearish — chart AND data align" shared gate (engine biasAligned hard-gate) — the diff omitted the strategy's defining rule.

**Verdict:** Drop OSPL, Trending-OI+PA feature ref, 1-night/Friday. Keep structural-SL, 90/10 ladder, pullback-entry, multi-TF-RSI; do NOT strip the live ATM±3 window.

---

### S11 — Straddle (Long & Short) (`straddle`) · **old_is_richer_keep** · audit 88%

**OLD-only (15, bloat-safe 0):** Long-vs-short two-variant definition (keep); long-entry combined-premium-above-VWAP trigger (keep); short-entry below-VWAP-after-9:30 (keep); VWAP-anchored SL +10-15pt buffer (keep — *cite §4.14.8 only, not §4.15.2*); long/short exits + one-leg mgmt (keep); breakeven sizing (combined>move; 70+50=130 P&L) (keep); LOW-IV-for-long gate (keep); Trending-OI both-sides-together = short-day (keep); ATM-default + OTM-safer-bet (keep); event-play designation (keep); 5-min combined-premium chart (s24-equiv); two automation realities (long-only built, SPAN-deferred short, dead YAML keys) (keep); **S23 reinforcement nuances — keep_s24_silent (was bloat — FALSE-DROP; 'don't short low-premium expiry' + 'Sensex thinner cushion' are load-bearing risk gates, §4.16 corroborated)**; **+ short worked example sell-54000-C+P (audit-added, keep)**; **+ short-VWAP-break = buyer's long cue (audit-added, keep)**.

**S24-only (3):** Day-character "who is winning" read; ~50% OI-gap as buy-the-dip sentiment param (Day 5); combined-premium-VWAP as whole-day directional gate (Day 17).

**Key drifts:** none (4 shared rules byte-identical incl. 40/40 IV→short-straddle, 24,800 erosion example, unlimited-risk warning). **Audit fix:** the "40/40+20/20" oldValue cite is §4.6 table, NOT §6.11 L2724 (that's §6.10).

**Verdict:** OLD is canonical (S24 reference-only, no deck). Keep everything incl. the S23 risk gates; nothing is droppable bloat.

---

### S12 — Trend Change (`trend_change`) · **old_is_richer_keep** · audit 88%

**OLD-only (20, bloat-safe 4):** Three-way reversal trigger taxonomy (keep — strategy identity); RSI >60/<40 (keep — FULL); 2-candle confirm (keep); volume 50K/125K break-confirm (keep); 09:45-2:30 window + 2:30 down-cap (keep); **crore-OI shift example 1.72→2.5-3cr (bloat — but RETAIN the ≥50% abstraction in shared)**; ~400pt target (bloat); structural SL broken-swing-pivot + 10-20pt leeway (keep); VWAP-without-volume discipline (keep); max-OI S/R box (keep); index-heavyweights confirm (keep); VIX-into-reversal (s24-equiv); intraday-bearish/positional-bullish precondition (keep); data-leads-price ~15-30min (keep); **edge-case bundle SPLIT: failed-attempt+held-pivot (bloat) / consolidation-both-sides+post-vertical-RSI (keep — AUTOMATE_PKG)**; don't-chase-high-premium + regime-scale (bloat); news-overrides-data (s24-equiv); strong-trend late-entry don't-chase (bloat); instrument 6-leg mapping (keep); status/versioning provenance (bloat); **+ multi-TF S/R + chart-pattern vocabulary (audit-added, keep)**; **+ named ST(10,2)/VWMA20/PSAR chart-indicator set (audit-added, keep)**.

**S24-only (3):** Divergence counter-trend 125K-volume gate (Day 21); monthly-expiry caveat (ignore OI); dual-confirmation crisp VWAP-broken-AND-OI-changed.

**Key drifts:** none (6 shared incl. Trending-OI primary, data-over-chart, ≥50% shift, 15-min/15-strike, 125K — same number two roles, VWAP defend-line). **Audit fix:** oldOnly count 21→20.

**Verdict:** OLD is canonical (S24 reference-only). Drop only the crore examples, ~400pt, per-day anecdotes, status metadata. Keep the trigger taxonomy, S/R framework, chart-indicator set.

---

## 5. Shared-section findings (6)

### Shared S1-risk — Global Risk Management Framework · simplify_partial · audit 86%

**OLD-only (15, bloat-safe 3):** 1:2 RR (0.5%:1%) (keep); 0.5%-rule all-accounts micro-stop — **keep (was bloat — backlog documents 0.5% as tighter owner option)**; deploy ≤10-20%/trade ≤20%/day (s24-equiv); 5-account/1%/5-wins/first-loss-freeze (keep — partly automated); win-qty=loss-qty symmetry (keep); SL-on-deployed/target-on-overall (s24-equiv); sell-only-hedged + gamma-skip (bloat — out-of-scope buy-side); **process routine SPLIT: pre/post-market+hardware+calm (bloat) / journal r37 (keep — AUTOMATE_PKG)**; capital-preservation survive-a-quarter + backtest-≥1yr (keep); slow lot-ramp 3-6mo (s24-equiv); wide-SL scale-in <5%/+5%/+10% + skip-if-VWAP-gap-wide (keep); no-trade-is-good + 11-1 sideways (bloat); wait-for-SL-or-target no-interference (keep); **+ hard-SL-in-the-system r20 (audit-added, keep — most-automated rule in §2)**; **+ decide-both-targets-before-first-trade r11 (audit-added, keep)**.

**S24-only (5):** 10-12% single-day hard cap; geometric 1/2/4/8→16 pyramiding; volatility sizing ~4pt/100-200pt; trending-OI-gap confidence + >50-60K exit; recycle-profit + never-contra-trade (RSI 20→9). *(4 self-flagged "also-in-OLD §2.14" = s24-foregrounded shared-origin.)*

**Key drifts (2):** BankNifty SL 75→100; Nifty SL 30→50-60; daily-cap 2-3% (engine) vs 10-12% (doc).

**Verdict:** Shed the S20 RR/5-account-numeric/hedging/process scaffold and per-session restatements. Keep hard-SL-in-system, decide-targets, 5-account rotation, survive-a-quarter, backtest-before-deploy.

---

### Shared S2 — Terminology & Indicator Set · simplify_partial · audit 82%

**OLD-only (10, bloat-safe 4):** Full Greeks glossary (bloat — Vega/Rho gone; Gamma/Theta relocated); derivative/CE-PE/spot primer (bloat); moneyness primer (bloat); IV-pair 5-row table — **moved oldOnly→shared (S24 cheat L40 carries it, drift)**; VIX correlation 5-row grid (s24-equiv); Falling-Knife/Basket-Selling (bloat); IV-Crash + Historical-Vol (keep); **A/D breadth — moved oldOnly→shared (S24 cheat L44 carries it, drops >32 number)**; ST 7,3 broad-view (keep — engine FULL); volume-colour + PSAR-distance (keep); OI-spurt 200/300% (bloat); daily-RSI 75/25 cross-check (keep).

**S24-only (6):** 15-strike (7+7+ATM) "beats 5/9/11"; intraday+positional both-agree; VIX ladder 18-20+/20-25+ + 90%-bounce; Kingdom chess mnemonic; explicit 9:45-2:30 window. *(Plus audit-added shared: VWAP-timing yesterday-until-10:30, wider-gap=stronger-trend.)*

**Key drifts (8):** PSAR 0.02,0.2 vs 0.02,0.02,0.2; RSI no-trade 40-60 vs 40-50; RSI buy floor 60 vs 50; IV gap 7-10/≥10 vs 8-10; buyer delta 0.6-0.7 vs ≥0.7; VIX upper bands; A/D >32 vs no-number; (engine follows OLD on PSAR/RSI-zone/delta).

**Verdict:** Drop the definitional primers and the OI-spurt flourish. Keep ST(7,3), daily-RSI, IV-Crash awareness. Re-bucket A/D and IV-pair to shared.

---

### Shared S3 — OI Interpretation / Quadrants / Trending-OI · simplify_partial · audit 88%

**OLD-only (11, bloat-safe 5):** OI-Spurts numbered Q1-Q4 table (s24-equiv); 200/300% extreme (bloat — keep 50/50 gate in shared); full §4.13 FII participant matrix (keep — distinct from L/S ratio); Trending-OI+PA LTP-vs-ΔOI overlay (keep); 60-min + 15m=5×3m + ATM±7 (bloat — *S24 KEEPS 5-15min, only 60m/equivalence/ATM±7 drop*); direction-change arrows (bloat); end-of-series ambiguity (bloat); EOD >50% next-day (keep); strike-level OI direction pair (s24-equiv); ≈34-gate automation backlog (bloat); **+ price-move-per-OI demand read §4.14.3 (audit-added, keep)**.

**S24-only (4):** Classify-by-close-in-range; decode-from-ATM 5-6 strikes; OTM-penny-strike OI-decreasing; sellers-both-sides=pin + writer-creates-position.

**Key drift (1):** monthly-expiry confirm-both (OLD S21) vs ignore (S24/engine suppress).

**Audit fixes:** PCR 1.2→1.5→2 ladder is carried by BOTH docs' §3.5 (the S24 CHEAT SHEET drops it, not the doc); FII §4.13 cite → macro-vix-global-fii.md L37 not README:521.

**Verdict:** Drop the Q-numbering, 200/300% flourish, arrows, end-of-series, the entire automation backlog. Keep FII participant matrix, PA overlay, EOD-bias, price-move-per-OI.

---

### Shared S4 — IV / VIX / Global cues / FII-DII · **old_is_richer_keep** · audit 88%

**OLD-only (16, bloat-safe 1):** FII participant-wise OI Dot + FII>Pro>DII>Client (keep); FII/DII change-in-OI 4×2 classifier (keep — most-automatable FII sub-rule); FII leg-level seller read (keep); FII next-morning-validity — **keep (was bloat — backlog fii-dii-bias §3.3 leg)**; A/D >32 (keep — FULL); 3:15pm global re-check (s24-equiv); Dollar/Asian/Oil must-match (s24-equiv); VIX 5-row grid + erratic-ignore (s24-equiv); VIX S21 bands 10-11/12-14/15-16/17+ (s24-equiv); VIX-vs-prev-close (keep); IV 6-strike averaging method (s24-equiv); IV 10-12 trend-play band (keep); per-strike IV-direction Desirable (keep); IV 7-10pt S22 band (s24-equiv); futures-basis read (bloat — out-of-scope here, FULL elsewhere); OIP-AI-matches-premarket + 3:20 (uncertain); **+ VIX falling+price-rising / VIX-stable+price-falling inferences (audit-added, keep)**. *(Phantom "VIX historical anchors" REMOVED — it's S24-only, not OLD.)*

**S24-only (9):** Dow=primary US30 + European-from-12:30; crude $60/$70-80/$80-100 + DXY <100/92-93 + USD-INR 88.5-88.8 bands; don't-act-on-Gift-Nifty + open-recheck; VIX positional deploy ladder; low-VIX 90%-bounce + vertical-climb test; FII-no-covering-during-rally caution; expiry-IV-crush 2nd-half; erosion-day 24,800 example + 50/50-60/60 ladder; **+ VIX historical anchors (moved from oldOnly — S24-only educational context)**.

**Key drifts (2):** VIX bands; IV floor 7/8/10.

**Verdict:** OLD richer (full FII matrix + breadth + S21 bands). Keep the participant matrix, A/D, IV bands; S24 only adds concrete 2025 levels. Reconcile the DXY drift (>105/<90 vs >100/92-93).

---

### Shared S5 — Strike / S&R / Time / Sensex-via-Nifty / Basis · simplify_partial · audit 88%

**OLD-only (12, bloat-safe 1):** Premium N 100-250/BN 250-400 (keep — engine band, but stale); S22-operative N 150-350/BN 250-550 (keep — corrected band, backlog PR-2 swaps engine); S&R marking method 1d-mark/15m-refine/zones/retrace-pullback/targets=next-S/R (keep — foundational); ATM±3 (keep); freshness >50% checks (keep); **AI-suggested OIP strike — keep (was bloat — disposition KEEP_MANUAL_NEW; FALSE-DROP)**; delta-by-expiry-phase (s24-equiv); Sensex strike examples 81500/82000 (bloat — illustrative); Sensex ~3x scaling exact figures (s24-equiv); Sensex futures-illiquid ~418 (s24-equiv); Sensex broader-sector study + named stocks (s24-equiv); **+ open=high-call/open=low-put per-strike confirm (audit-added, keep)**.

**S24-only (6):** S&R from volume-turns not OI + 2-3mo/6mo lines; max-call-OI=resistance/max-put-OI=support + spot-OI bars; time refinement hourly-high/1:30-book/2:30-stay-away; prefer-fewer-writers + avoid-120-130-prefer-150+; Sensex participation/volume gate (36/59L vs 10/18cr) + Thu/Tue pick + HFT-arb; *(+ avoid-OTM-for-momentum Day-20, audit-added)*.

**Key drifts (5):** delta 0.6-0.7 vs ≥0.7; premium band (S20 engine vs S22-operative vs S24-none); time rails (11-13 block+15:30 vs soft 2:30); Sensex 3x vs 3-4x.

**Verdict:** Drop only the Sensex illustrative strike numbers. Keep the S&R marking method, ATM±3, freshness gates, OIP-strike (manual). Reconcile premium band to S22-operative in engine.

---

### Shared S6 — Cross-cut: tooling / manual-checks / session-addition accretion · **simplify_safe** · audit 86%

**OLD-only (15, bloat-safe 8):** §4.12 OIP-AI-matches-premarket + 3:20 view-match (keep); §4.13 FII full participant model + 4×2 matrix (keep — owner judgment call); VIX absolute regime bands + 4-cell grid (s24-equiv); §4.14 "Session-21 Additions" wrapper — **SPLIT: drop wrapper + dead sub-rules (lot-sizes, expiry-phase-delta, account-size); keep VIX-bands/futures-basis/Q1-Q2-gate/IV-3-LTP as shared**; §4.15 "Session-22 Additions" wrapper — **SPLIT same way; keep VWMA20/IV-6-strike**; §4.16 Session-23 (s24-equiv → §4.4); §4.17 "Session-24 Additions" WRAPPER only (bloat — *rules are SHARED/S24-core, not oldOnly*); §5 Strategy-Evolution log (bloat); Changelog ledger (bloat); 7-item ScalperManualChecks card (keep — impl surface); FU1 16-check card (bloat — derived); FU2 4 promotions (bloat — derived); 246-gap backlog (bloat — derived); §4.14.6 OI-interval ladder + ATM±7 (bloat); §4.14.4 lot-sizes + expiry-day-of-week (bloat). *(+ audit-added shared: FII importance-order, leg-level seller polarity, §4.12 tool-inventory.)*

**S24-only (5):** VIX vertical-15min-climb test; low-VIX 90%-bounce 2:00-3:30; FII L/S ratio as primary (drops participant matrix); global cues sharpened with 2025 levels; S&R-from-volume-turns refinements.

**Key drifts (3):** VIX bands; DXY >105/<90 vs <100/92-93; (Trending-OI 15-strike = same value, no drift).

**Verdict (simplify_safe):** This is the heaviest debloat target. Drop the per-session "Additions" WRAPPERS, the §5 evolution log, the Changelog, FU1/FU2, and the 246-gap backlog wholesale — none are strategy rules. But SPLIT the §4.14/§4.15/§4.17 containers: keep the still-live sub-rules (VIX bands, futures basis, Q1/Q2 gate, VWMA20, all §4.17 rules) as shared, dropping only the wrappers and dead sub-rules.

---

## 6. Prioritised simplification recommendations

Ranked by value × safety. Each tagged risk + exact target.

| # | Action | Risk | Exact target |
|---|---|---|---|
| 1 | **Delete the entire derived automation corpus from the strategy doc** — FU1 16-check card, FU2 4 promotions, the 246-gap/12-stream backlog. None are scalper rules; they are engineering roadmaps reverse-engineered from A. | low | `docs/superpowers/plans/2026-06-27-followup1-*.md`, `-followup2-*.md`, `2026-06-27-backlog/` (keep as impl artifacts, exclude from the doc) |
| 2 | **Drop the §5 Strategy-Evolution log + the whole Changelog ledger.** A single-session S24 edition has nothing to evolve from; every threshold already lives in §3/§4. | low | OLD doc §5 (L1637-1750); `Options_Scalper_Siva_Changelog.md` (whole file) |
| 3 | **Strip the per-session "Session-N Additions" WRAPPERS** (§4.14/§4.15/§4.16/§4.17 headings + "S24 confirms the framework above" prose) — but SPLIT, keeping the live sub-rules flattened into §4.x. | low (wrapper) / med (don't drop kept sub-rules) | OLD doc §4.14.1-9, §4.15.1-5, §4.16.1-4, §4.17.1-6 |
| 4 | **Drop the terminology onboarding primers** — full Greeks glossary (Vega/Rho), derivative/CE-PE/spot, moneyness primer, Falling-Knife/Basket-Selling, OI-spurt 200/300% flourish. | low | OLD §1.2 instrument/moneyness/Greeks/volatility rows (L54-117); glossary L101 |
| 5 | **Drop the tool-UI primitives + dead feeds** — OSPL/OIP-AI signal feeds, OI direction-change arrows, Trending-OI+PA named feature, 3:20pm OIP-AI alignment (no feed exists). | low | §3.5 L706; §3.10 S22(h); §4.12 L1462-1467; §4.15.1 |
| 6 | **Drop the older illustrative figures** — crore-OI examples (S12), ~400pt targets (S12), R:R 1:2.5/1:1.6-1.7 (S4), 5-10%-overshoot (S3), Sensex strike examples 81500/82000 (S5). | low | §3.12 L1222/L1237; §3.4 L618; §3.3 S22(c); §4.16.4 L1599 |
| 7 | **Drop the averaging/scale-in numeric ladders** S24 deliberately dropped — 3%/+7%/≤20%, 25%/more/MAX (S1); scale-in/both-sides-qty expiry tactics (S7); next-day-edge + operator over-specification. | low-med | §3.1 risk; §3.7 S22(f-h); §2.4 numeric 5-account mechanics |
| 8 | **Drop the S20/S21/S22 discretionary skip sub-cases** — one-rejection-per-event, Golden-X combo (S1); strong-trend-day/100%-premium-move skip (S2); 2:30-3:00 SC/SB observation window + strike-S/R map (S8); 11-1 sideways prose (risk). | med | §3.1 S21(c)(d); §3.2 S21/S22; §3.8 setup4; §2.7-2.8 |
| 9 | **Drop the timeframe housekeeping** — 60-min broader-trend + 15m=5×3m equivalence + ATM±7 1%-recentre + OI-interval 6-TF ladder + lot-size/expiry-day-of-week tables. | med | §4.14.6 L1526-1528; §4.14.4 L1518-1520; §3.5 S21 |
| 10 | **Collapse the IV-pair/VIX-grid tables to S24 prose form** where S24 carries the identical intent (VIX 4-cell grid, IV 6-strike averaging) — but only after re-bucketing A/D and IV-pair to *shared*. | med | §4.5 grid L1362-1374; §4.6 averaging L1378-1390 |

### DO NOT DROP (rules absent from S24 that must stay — `keep_s24_silent`)

| Rule | Scope | Why it must stay |
|---|---|---|
| **VWAP-as-stop-loss / above-VWAP entry gate** | S2 | Most-implemented OLD rule — hard in all 3 YAMLs + the engine structural stop. S24 silently assumes it. |
| **Structural 1st-candle / pre-gap-candle / broken-swing SLs** | S1/S4/S10/S12 | The actual encoded stops (StructuralStop.FIRST_CANDLE / GAP_TREND). S24's point-SLs do not replace them. |
| **3:15pm Futures-OI + Option-OI confluence + OI four-quadrant** | S8 | AUTOMATE_PKG, muted-on-history not deleted; S24 reference-only never re-taught the EOD-OI clock. Judge on forward paper. |
| **Index-scaled point SL (BN~75/N~30) + 3:10 no-move exit** | S7 | S22-resolved risk-floors, disposition AUTOMATE_PKG. Dropping a risk-floor is the dangerous direction. |
| **Hard-SL-in-the-system (no mental stops) + decide-both-targets-before-first-trade** | Shared-S1 | r20 is the most-automated rule in all of §2 (ScalperRisk.hasBoundingExit); r11 is partly automated. |
| **0.5% all-accounts micro-stop + 5-account rotation + survive-a-quarter + backtest-≥1yr** | Shared-S1 | Owner-configurable rails, partly automated; the platform IS a backtester. |
| **FII participant-wise OI matrix (4×2 change-in-OI classifier, FII>Pro>DII>Client, leg-level seller read)** | Shared-S4/S6 | The most-automatable FII sub-rule; NOT deducible from the L/S ratio alone. Owner judgment call. |
| **A/D breadth >32 (CE) / >32 (PE)** | Shared-S2/S4 | A coded, threshold-bearing confluence dot (ScalperGates.breadth). S24 drops only the *number*, not the rule. |
| **ST(7,3) higher-TF 15m/1h broad-trend bias** | S6/Shared-S2 | Engine hard gate (bias60m, all YAMLs). S24 omits from per-strategy lists. |
| **≥50% OI gates (ΔOI imbalance, both OI+price>50%, Call-vs-Put gap)** | S5/S7/S10 | Hard pre-gates / realMove() / oi-cross-filter. The defining OI discipline. |
| **The reversal-trigger taxonomy + multi-TF S&R framework + named ST/VWMA/PSAR chart-indicator set** | S12 | The strategy's identity (S24 reference-only). Without it there is no Trend-Change strategy. |
| **Rejection-wick entry trigger + 2nd-candle-breaks-1st + EOD-formed view + RSI-secondary-exit + Open=High-hedge + add-around-prev-close** | S9 | The actual fire trigger and management rules; all dispositioned AUTOMATE_PKG/KEEP_MANUAL_NEW (false-drops in pass-1). |
| **Long-vs-short straddle construction + VWAP-anchored SL + breakeven sizing + LOW-IV gate + S23 risk gates** | S11 | S24 is reference-only; OLD is the canonical home. The S23 "don't short a low-premium expiry" + "Sensex thinner cushion" are load-bearing risk gates. |
| **Bearish-RSI ambiguity (card >25 vs matrix <25)** | S6 | An OPEN owner-blocker for PE-side automation; S24's 40-25 band does NOT resolve it. |
| **The S&R marking method (1d-mark/15m-refine/zones/retrace-pullback/targets=next-S/R) + OIP-AI-suggested-strike (manual)** | Shared-S5 | Foundational entry/target/stop method S24 builds on but never restates; OIP-strike is KEEP_MANUAL_NEW. |
| **The 7-item ScalperManualChecks card** | Shared-S6 | An impl operator surface mapping to live S24 rules — keep regardless of doc edition. |

---

## 7. Methodology + limitations

**Multi-pass pipeline.** (1) *Extract* — per scope, a rule-by-rule diff partitioned OLD vs S24 into `oldOnly` / `s24Only` / `shared`, each OLD-only rule classified `keep_s24_silent` / `bloat_safe_drop` / `s24_simplified_equivalent` / `uncertain`, with source citations (§/line) and a sessionTag. (2) *Adversarially audit* — each diff re-derived independently, surfacing `missedRules`, `misattributions`, `inventedFigures`, `classificationErrors`, and explicit `corrections`, with an `accuracyPct`. (3) *Reconcile (this doc)* — every audit correction folded into the final numbers: missed rules added, misattributed rules moved bucket, invented figures deleted, classification errors re-bucketed, count mismatches fixed; totals re-tabulated from the corrected state.

**Figures are quoted, never invented.** Across all 18 audits, `inventedFigures` was empty in every scope — every threshold (RSI bands, 50K/125K, ATM±3, deltas, VIX bands, point-SLs, 87-94%/50% FII, crore-OI) traced verbatim to a source line. The fabrications the audits *did* catch were **source-file citations**, not figures: a non-existent "changelog" and a "cheat-sheet §6" in S6 (Golden Crossover), a phantom "§3.3 S23 update" in S3, a wrong README:521 cite in Shared-S3 — all stripped/corrected here.

**Limitations.**
- The most consequential reconciliations were **bloat→keep re-classifications** (false-drops): S7 (×3 risk gaps), S9 (×3, bloatSafe→0), S11 (S23 risk gates), S8 (×3 OI-confluence), Shared-S5 (OIP-strike). These were caught because the dispositions/backlog contradicted the drop; an un-audited single pass would have over-trimmed live rules.
- The §2 rollup TOTALS are **exact column-sums** of the per-scope rows (OLD-only 283, bloat-safe-drop 72, S24-only 95, shared 194, drifted 50); a pass-4 completeness critic re-summed them against the rows and the figures here are the corrected ones. Residual ±1 ambiguities exist only at the per-rule grain (whether a split rule counts as 1 or 2) and do not move the totals. The §3 drift table tabulates 45 of the 50 drifts (the 5 minor ones live in the per-strategy "Key drifts" lines).
- The OLD↔S24 partition is a **doc-vs-doc** comparison; where a rule diverges from the live **engine** (RSI 40-60, breadth >32, delta 0.6-0.7, daily-loss 2%, premium 100-250/250-400), that doc-vs-CODE divergence is flagged in the drift table but is *not* itself an OLD-vs-S24 disagreement — reconcile in code separately.
- Several OI/macro factors (FII matrix, 3:15 OI confluence, derived-history OI) are **muted on backtests** and are forward-paper discriminators; their "keep" status reflects forward intent, not backtest-provable edge.
