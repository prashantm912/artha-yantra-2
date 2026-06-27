# RATIFICATION PACK — owner-fillable, derived from S24-COMPARISON.md

> Source of truth: `docs/strategy-audit/S24-COMPARISON.md` (the audit-reconciled OLD-vs-S24 comparison). Every row below traces to that doc; nothing is invented. This pack exists to be **filled in by the owner** to drive W1 (new debloated operative strategy doc), W2 (backlog prune), W3 (parity-safe code drift fixes).

## How to use (6 lines)
1. **Mark only the bold column on each row.** Part 1 → write **DROP** or **KEEP**. Part 2 → write **OLD**, **S24**, or **RECONCILE**. Part 3 → write a free-text **ruling**.
2. **Blanks default to "My rec".** If you leave a row blank, the "My rec" value stands — so you only need to touch rows where you disagree.
3. **Part 1 default = DROP.** Every Part-1 rule is already classified `bloat_safe_drop`; it is dropped from the new W1 doc **unless you write KEEP**. Notes flag any SPLIT (keep one sub-rule, drop the rest).
4. **Part 2 = drift reconciliation.** Pick the value the new doc adopts. Every P2 row resolved to **S24 where the comparison flags the engine differs** becomes a **parity-safe code task in W3** (the engine currently holds OLD/another value).
5. **The 5 dangerous engine-vs-doc drifts sit at the top of Part 2** (RSI 40-60, breadth >32, delta 0.6-0.7, daily-loss 2% vs 10-12%, premium 100-250 vs 150-350). Rule on these first — they are live engine values.
6. **Part 3 = open owner-blockers** the comparison could not resolve (it left them `uncertain` / `UNCERTAIN_OWNER`). Your ruling unblocks them; until then they stay ambiguous.

---

## PART 1 — Bloat-safe-drop ratification (72 rules)

Default action = **DROP** (these are all classified `bloat_safe_drop` in the comparison). Write **KEEP** in the bold column to retain a rule in the W1 doc. SPLIT notes mean part of the rule is kept elsewhere.

| # | Scope | Rule (quoted) | Source (per comparison) | My rec | **Your call (DROP / KEEP)** | Notes |
|---:|---|---|---|---|---|---|
| 1 | S1 Two Candle | "Averaging ladder 3%/+7%/≤20% + 25%/more/MAX@VWAP" | §3.1/§5.1/§6.1 (S20/S22) | DROP |  | Rec #7 target: averaging/scale-in numeric ladders S24 deliberately dropped |
| 2 | S1 Two Candle | "One ST/VWAP-rejection trade per event" | §3.1 S21(c) | DROP |  | Rec #8 discretionary skip sub-case |
| 3 | S1 Two Candle | "Golden-Crossover-combo confluence" | §3.1 S21(d) | DROP |  | Rec #8 |
| 4 | S1 Two Candle | "RSI-40 bear trigger + 47K near-miss anecdote" | §3.1 S22 | DROP |  | Classified s24_simplified_equivalent in row, listed in bloat-safe count; illustrative anecdote |
| 5 | S1 Two Candle | "IV Desirable (rising bull / falling bear)" | §3.1 filters | DROP |  | — |
| 6 | S2 Open=High/Low | "S22 premium bands 250-550/150-350" | §3.2 S22 | DROP |  | Premium-band debate (numeric); but premium-FLOOR "avoid <130 / prefer 150+" is SHARED, retained |
| 7 | S2 Open=High/Low | "VIX direction" | §3.2 filters | DROP |  | VIX direction filter |
| 8 | S2 Open=High/Low | "IV per-strike" | §3.2 filters | DROP |  | — |
| 9 | S2 Open=High/Low | "two-sided OH mechanics" | §3.2 | DROP |  | — |
| 10 | S2 Open=High/Low | "S21/S22 skip sub-cases" | §3.2 S21/S22 | DROP |  | Rec #8 discretionary skip sub-cases |
| 11 | S2 Open=High/Low | "Trending-OI 5-15min tooling" | §3.2 | DROP |  | Tooling list |
| 12 | S2 Open=High/Low | "beginners 1-5%" | §3.2 | DROP |  | — |
| 13 | S2 Open=High/Low | "ATM±3" *(s24-equiv label, in bloat count)* | §3.2 | DROP |  | NOTE: across other scopes ATM±3 (strikes.width:3) is live-armed FULL — do not strip the live window; this row is the S2 restatement only |
| 14 | S3 Market Movers | "next-day continuation" | §3.3 / §5.3 | DROP |  | Rec #7 next-day-edge |
| 15 | S3 Market Movers | "5-10%+ overshoot" | §3.3 | DROP |  | Rec #6 older illustrative figures |
| 16 | S3 Market Movers | "top-constituent weightage HDFC 29.46%" | §3.3 | DROP |  | Rec #6 |
| 17 | S3 Market Movers | "Sensex-via-Nifty heavyweights" | §4.16/§4.17 (corrected cite) | DROP |  | Cite corrected from phantom §3.3 |
| 18 | S3 Market Movers | "New-High/Low panel" | §3.3 | DROP |  | — |
| 19 | S4 Gap Theory | "high/low gap variant" | §3.4 | DROP |  | — |
| 20 | S4 Gap Theory | "counter-trend toward-gap scalp" | §3.4 | DROP |  | — |
| 21 | S4 Gap Theory | "R:R 1:2.5 / 1:1.6-1.7 targets" | §3.4 | DROP |  | Rec #6 illustrative R:R ratios |
| 22 | S4 Gap Theory | "S21 lot-size scaling" | §3.4 S21 | DROP |  | Rec #7 |
| 23 | S4 Gap Theory | "gap-up seek-support" | §3.4 | DROP |  | — |
| 24 | S4 Gap Theory | "positional/EOD-OI night-risk note" *(audit-added)* | §3.4 | DROP |  | Audit-added, classified bloat |
| 25 | S5 Trending OI | "strike housekeeping ATM±7" | §3.5 | DROP |  | Rec #9 timeframe/strike housekeeping |
| 26 | S5 Trending OI | "direction-change arrows" | §3.5 | DROP |  | Rec #5 tool-UI primitive |
| 27 | S5 Trending OI | "EOD >50% next-day bias" | §3.5 | DROP |  | NOTE: in Shared-S3 the EOD >50% next-day is KEPT; this S5 restatement is the bloat copy |
| 28 | S5 Trending OI | "pairs-with-Two-Candle cadence" | §3.5 | DROP |  | — |
| 29 | S5 Trending OI | "end-of-series ambiguity" | §3.5 | DROP |  | — |
| 30 | S6 Golden Crossover | "'stronger in Bank Nifty'" | §3.6 | DROP |  | Rec #6 BankNifty colour |
| 31 | S6 Golden Crossover | "RSI-exhaustion caveat S21(e)" *(audit-added)* | §3.6 S21(e) | DROP |  | Audit-added anecdote |
| 32 | S6 Golden Crossover | "first-candle-high resistance edge" *(audit-added)* | §3.6 | DROP |  | Audit-added anecdote |
| 33 | S6 Golden Crossover | "two-crossovers-in-a-day rule" *(audit-added)* | §3.6 | DROP |  | Audit-added |
| 34 | S6 Golden Crossover | "no-VOLUME crossover edge" *(audit-added)* | §3.6 | DROP |  | Audit-added |
| 35 | S7 Hero-Zero | "round-strike double-zero-pin SPLIT — drop scale-in/both-sides tactics" | §3.7 S22(f-h) | DROP |  | **SPLIT — KEEP pin-warning (KEEP_MANUAL_NEW); DROP only the scale-in/both-sides expiry tactics** |
| 36 | S7 Hero-Zero | "BTST-borrowed RSI *number* — the RSI gate itself stays; only the borrowed numeric value drops" | §3.7 / Verdict L217 | DROP |  | 2nd of S7's two clean bloat-safe drops (1st = scale-in/both-sides expiry tactics, row 35). Verdict L217: "the only clean drops are the BTST-borrowed RSI *number*… and the scale-in/both-sides expiry tactics". Gate stays — only the borrowed number drops |
| 37 | S8 BTST/STBT | "3:20pm OIP-AI alignment" | §3.8 | DROP |  | Rec #5 dead feed (no feed) |
| 38 | S8 BTST/STBT | "'320 Strategy' wide overnight SL" | §3.8 | DROP |  | — |
| 39 | S8 BTST/STBT | "RSI >60/<40 example bands" *(split from directional gate)* | §3.8 | DROP |  | **SPLIT — DROP the >60/<40 example bands; KEEP the directional not-overbought>75/oversold gate (AUTOMATE_PKG rsiBand)** |
| 40 | S8 BTST/STBT | "stock n-day-low variant" | §3.8 | DROP |  | — |
| 41 | S8 BTST/STBT | "2:30-3:00 SC/SB window" | §3.8 setup4 | DROP |  | Rec #8 |
| 42 | S8 BTST/STBT | "strike S/R Put-support/Call-resistance" | §3.8 | DROP |  | Rec #8 strike-S/R map |
| 43 | S10 Connect-the-Dots | "OSPL/AI signals" | §3.10 / §4.x | DROP |  | Rec #5 dead feed |
| 44 | S10 Connect-the-Dots | "Trending-OI+PA feature §4.15" | §4.15 | DROP |  | Rec #5 named feature |
| 45 | S10 Connect-the-Dots | "1-night/avoid-Friday" | §3.10 | DROP |  | — |
| 46 | S10 Connect-the-Dots | "VIX four-scenario raw-deck label" *(in bloat count; verdict 'keep' label conflict)* | §3.10 | DROP |  | NOTE: row text marks this "(keep)" but it is inside the bloat-safe-4 tally; the 4 clean drops per Verdict = OSPL, Trending-OI+PA feature ref, 1-night/Friday (+ one of the s24-equiv re-routes). Owner: confirm the 4th |
| 47 | S12 Trend Change | "crore-OI shift example 1.72→2.5-3cr" | §3.12 L1222 | DROP |  | **SPLIT — DROP the crore example; RETAIN the ≥50% abstraction in shared** (Rec #6) |
| 48 | S12 Trend Change | "~400pt target" | §3.12 L1237 | DROP |  | Rec #6 |
| 49 | S12 Trend Change | "don't-chase-high-premium + regime-scale" | §3.12 | DROP |  | — |
| 50 | S12 Trend Change | "strong-trend late-entry don't-chase" | §3.12 | DROP |  | — |
| 51 | S12 Trend Change | "edge-case bundle SPLIT: failed-attempt+held-pivot" | §3.12 | DROP |  | **SPLIT — DROP failed-attempt+held-pivot; KEEP consolidation-both-sides+post-vertical-RSI (AUTOMATE_PKG)** |
| 52 | S12 Trend Change | "status/versioning provenance" | §3.12 | DROP |  | Rec #6 status metadata |
| 53 | Shared S1-risk | "sell-only-hedged + gamma-skip" | §2.x | DROP |  | "out-of-scope buy-side" |
| 54 | Shared S1-risk | "process routine SPLIT: pre/post-market+hardware+calm" | §2.x | DROP |  | **SPLIT — DROP pre/post-market+hardware+calm process routine; KEEP journal r37 (AUTOMATE_PKG)** |
| 55 | Shared S1-risk | "no-trade-is-good + 11-1 sideways" | §2.x | DROP |  | Rec #8 11-1 sideways prose |
| 56 | Shared S2 Terms | "Full Greeks glossary (Vega/Rho gone; Gamma/Theta relocated)" | §1.2 glossary L101 | DROP |  | Rec #4 terminology primer |
| 57 | Shared S2 Terms | "derivative/CE-PE/spot primer" | §1.2 L54-117 | DROP |  | Rec #4 |
| 58 | Shared S2 Terms | "moneyness primer" | §1.2 | DROP |  | Rec #4 |
| 59 | Shared S2 Terms | "Falling-Knife/Basket-Selling" | §1.2 | DROP |  | Rec #4 |
| 60 | Shared S2 Terms | "OI-spurt 200/300%" | §1.2 / §4.13 | DROP |  | Rec #4 OI-spurt flourish (keep 50/50 gate) |
| 61 | Shared S3 OI | "200/300% extreme" | §4.13 | DROP |  | **Keep 50/50 gate in shared; DROP only the 200/300% extreme** |
| 62 | Shared S3 OI | "60-min + 15m=5×3m + ATM±7" | §4.14.6 | DROP |  | **S24 KEEPS 5-15min; DROP only 60m/equivalence/ATM±7** (Rec #9) |
| 63 | Shared S3 OI | "direction-change arrows" | §4.x | DROP |  | Rec #5 |
| 64 | Shared S3 OI | "end-of-series ambiguity" | §4.x | DROP |  | — |
| 65 | Shared S3 OI | "≈34-gate automation backlog" | backlog (derived) | DROP |  | Rec #1 derived automation corpus |
| 66 | Shared S4 IV/VIX | "futures-basis read (out-of-scope here)" | §4.x | DROP |  | **DROP here only — futures-basis is FULL/load-bearing elsewhere** |
| 67 | Shared S5 Strike | "Sensex strike examples 81500/82000" | §4.x | DROP |  | Rec #6 illustrative Sensex strike numbers |
| 68 | Shared S6 Cross-cut | "§4.17 'Session-24 Additions' WRAPPER only" | §4.17 | DROP |  | **DROP wrapper only — the §4.17 RULES are SHARED/S24-core, keep them** (Rec #3) |
| 69 | Shared S6 Cross-cut | "§5 Strategy-Evolution log" | §5 L1637-1750 | DROP |  | Rec #2 |
| 70 | Shared S6 Cross-cut | "Changelog ledger" | `Options_Scalper_Siva_Changelog.md` whole file | DROP |  | Rec #2 |
| 71 | Shared S6 Cross-cut | "FU1 16-check card" | `...followup1-*.md` (derived) | DROP |  | Rec #1 — keep as impl artifact, exclude from doc |
| 72 | Shared S6 Cross-cut | "FU2 4 promotions" | `...followup2-*.md` (derived) | DROP |  | Rec #1 |
| 73 | Shared S6 Cross-cut | "246-gap backlog" | `...backlog/` (derived) | DROP |  | Rec #1 |
| 74 | Shared S6 Cross-cut | "§4.14.6 OI-interval ladder + ATM±7" | §4.14.6 L1526-1528 | DROP |  | Rec #9 |
| 75 | Shared S6 Cross-cut | "§4.14.4 lot-sizes + expiry-day-of-week" | §4.14.4 L1518-1520 | DROP |  | Rec #9 dead sub-rules |

> **Count note:** the comparison's §2 TOTALS says **72** bloat-safe-drop. This table lists **75 candidate rows** because the comparison's per-scope prose tags MORE "(bloat)" items than its §2 column sums (S4 6 tags/5 col, S8 6/5, S12 6/4, Shared-S2 5/4) — listing every prose tag legitimately exceeds 72. The **3 overshoot rows** are the borderline / label-conflicted ones already flagged: **row 13** (S2 ATM±3, tagged `s24_simplified_equivalent` not bloat), **row 24** (S4 positional/EOD-OI night-risk, the 6th S4 "(bloat)" tag beyond §2's 5), and **row 46** (S10 VIX four-scenario, marked "(keep)" inside a bloat tally). For a clean **72-row ratified set, demote rows 13, 24, 46 to the "Borderline / label-conflicted annex" below** — they are NOT counted in the ratified 72. The remaining 72 rows are the clean DROPs; rows 35/36/39/47/51/54/61/62/66/68 are SPLITs where only a sub-rule drops (the rule itself stays partially) but each still resolves to exactly one drop, so they count toward the 72.
>
> ### Borderline / label-conflicted annex (NOT in the ratified 72)
> These 3 rows are excluded from the clean 72 because their comparison tag conflicts with a strict bloat-drop classification. Owner may still rule on them, but they do not affect the 72-count.
>
> | # | Scope | Rule (quoted) | Why annexed |
> |---:|---|---|---|
> | 13 | S2 Open=High/Low | "ATM±3" | Tagged `s24_simplified_equivalent` (s24-equiv label), NOT bloat — and ATM±3 (`strikes.width:3`) is live-armed FULL elsewhere; this is the S2 restatement only |
> | 24 | S4 Gap Theory | "positional/EOD-OI night-risk note" *(audit-added)* | 6th S4 "(bloat)" prose tag beyond §2's 5-column count for S4 — surplus tag, not in the §2 sum |
> | 46 | S10 Connect-the-Dots | "VIX four-scenario raw-deck label" | Row text marks this "(keep)" yet it sits inside the bloat-safe-4 tally — label conflict; Verdict's 4 clean S10 drops are OSPL, Trending-OI+PA feature ref, 1-night/Friday, + one s24-equiv re-route |

---

## PART 2 — Drift reconciliation (48 concrete drift rows + 1 under-spec flag; comparison claims 50)

Pick the value the W1 doc adopts. "My rec" copies the comparison's "Which to trust". **Every row resolved to S24 where the engine differs becomes a parity-safe W3 code task.** Signal-affecting = yes means it changes which trades fire.

### THE 5 DANGEROUS ENGINE-VS-DOC DRIFTS (rule on these first — live engine values diverge from S24 doc)

| # | Scope | Rule | OLD value | S24 value | Engine value | My rec | **Your pick (OLD / S24 / RECONCILE)** | Signal-affecting? |
|---:|---|---|---|---|---|---|---|---|
| D1 | Shared-S2 | RSI no-trade zone | 40-60 | 40-50 | **40-60 (engine = OLD)** | Engine = OLD (40-60) |  | yes |
| D2 | Shared-S2 | Advance/Decline breadth | explicit >32 | breadth row, drops >32 number | **>32 (engine FULL = OLD)** | Engine = OLD (>32 live) |  | yes |
| D3 | Shared-S2 / S5 / S9 / S10 | Buyer/strike delta floor (UMBRELLA — = D26/D29/D41) | 0.6-0.7 | ≥0.7 (+ phase conditioning) | **0.6-0.7 (engine = OLD)** | S24 raises floor; engine on OLD (RECONCILE in code). ONE ruling here propagates to D26 (S9), D29 (S10), D41 (Shared-S5) — same physical drift, not 4 independent ones (see L170 count-note) |  | yes |
| D4 | Shared-S1-risk | Daily loss cap | 2-3% (S20 rule13; YAML 2.0) | 10-12% single-day | **2-3% (engine = OLD)** | Reconcile (doc 10-12 vs engine 2) |  | yes |
| D5 | Shared-S5 | Premium band | N 100-250/BN 250-400 (engine) vs N 150-350/BN 250-550 (S22 doc) | no band; qualitative "avoid 120-130, 150+" | **N 100-250/BN 250-400 (engine, stale)** | S22-operative band (backlog PR-2 swaps engine) |  | yes |

### The 45 tabulated cross-scope drifts (§3 of the comparison)

| # | Scope | Rule | OLD value | S24 value | Engine value | My rec (Which to trust) | **Your pick (OLD / S24 / RECONCILE)** | Signal-affecting? |
|---:|---|---|---|---|---|---|---|---|
| D6 | S3 Market Movers | Strong-short variant qualifier | 15-day low + "completely bearish data" (S22) | >15% fall / 15-day low + Open=High | — | S24 (latest; explicit magnitude) |  | yes |
| D7 | S3 | Time-of-day floor (coverage) | explicit "after 09:45" + YAML 09:45 | no explicit floor (move "by 9:45" framing) | 09:45 enforced | OLD/engine (09:45 enforced) |  | yes |
| D8 | S3 | Daily-RSI cool-off cap | bull cap 75 / bear floor 40 (S22) | bull >70 no-long / 67-68 buy / ~80 book / 25-30 short-caution | — | S24 (tightens 75→70) |  | yes |
| D9 | S3 | Instrument vehicle | stock futures OR cash | futures only (drops cash) | — | S24 (futures-only) |  | yes |
| D10 | S4 Gap Theory | Operating window | after 9:45, ideal 9:15-10:00, avoid 11-1 | suite window 9:40-2:30 | — | Reconcile (9:45 vs 9:40 anchor) |  | yes |
| D11 | S5 Trending OI | RSI confirm band | §3.5 card bull <75 / bear >25 | long 50-75 / short 40-25 | — | S24 (adopts grid/golden bands) |  | yes |
| D12 | S5 | Gap-quality gate measured quantity | ≥50% on CHANGE-in-OI imbalance (+ 20-30% sub) | ≥50% (50-100%) call-vs-put OI GAP | engine measures ΔOI | OLD/engine measures ΔOI; S24 prose loose |  | yes |
| D13 | S5 | Operating window bounds | after 9:45 / best 10-11:30 / avoid ~1:30-2 | hard 9:40-2:30 | — | Reconcile (end-time 1:30-2 vs 2:30) |  | yes |
| D14 | S6 Golden Crossover | Bullish RSI card | §3.6 card RSI <75 (no lower bound) | band 50-75 | — | S24 (promotes 50 floor) |  | yes |
| D15 | S6 | Bearish RSI card | RSI >25 (+ UNCERTAIN matrix <25) | band 40-25 | engine PE 20-40 (UNCERTAIN open) | S24 doc; engine PE 20-40 (UNCERTAIN open — see P3) |  | yes |
| D16 | S6 | Rarity / frequency | ~3-4 times per month | ~3-5 times per month | — | S24 (minor) |  | no |
| D17 | S6 | Time window framing | after 9:45 / avoid 11-1 / best 10-11 | general 9:45-2:30 | — | S24 (house window) |  | yes |
| D18 | S6 | Engine RSI band override | CE 60-80 / 40-60 dead (code) | doc 50-75 / 40-25 | CE 60-80 / 40-60 dead | UNCERTAIN_OWNER (doc-vs-code — see P3) |  | yes |
| D19 | S7 Hero-Zero | Execution window | after 2pm, observe 2:30-3, hard close 3:20 | ~2:45-3:15 (deck ~2:30-3) | engine 14:30-15:20 | S24 doc; engine matches neither exactly |  | yes |
| D20 | S8 BTST | Overnight sizing | S23 10-20% of capital | 5-10% of capital | — | S24 (latest, tightened) |  | yes |
| D21 | S8 | Overnight stop-loss | YAML/engine fixed 50% premium | NO explicit overnight SL (validity gate + cap) | 50% premium (YAML/engine) | Reconcile (50% vs none) |  | yes |
| D22 | S8 | Carry-validity condition | close-at-day-high/low + OI quadrant | no-VWAP/ST-breach + hold into close | — | S24 (price-action gate) |  | yes |
| D23 | S8 | Instrument vehicle | index F&O + stock/cash carry leg | index options only | — | S24 (index-only) |  | yes |
| D24 | S9 Morning Trade | Scalp worked example | 9:16→9:18 / 250pts/2min | 9:15:02→9:16:13 / >1%/~1min | — | Either (illustrative) |  | no |
| D25 | S9 | SL reference level | first-candle low/high (engine) | strike's prior-day VWAP / bounce bottom | first-candle low/high | Engine uses OLD; reconcile |  | yes |
| D26 | S9 | Strike/delta selection | delta 0.6-0.7 + N 100-250/BN 250-400 + ATM±3 | 2-3 strikes from settle, slight ITM, delta ~0.80 (estimation) | delta 0.6-0.7 / premium band | Reconcile (purpose differs) |  | yes |
| D27 | S9 | Morning RSI band | morning-specific CE 60+/not>75/40-60 no-trade | inherits framework 40-50 no-trade / buy 50-75 | CE 60+/40-60 (per shared engine) | Reconcile (S24 defers to §3.10) |  | yes |
| D28 | S10 Connect-the-Dots | RSI entry band (doc vs engine) | doc 50-75 / no-trade 40-50 | engine CE 60-80 / no-trade 40-60 | CE 60-80 / 40-60 | UNCERTAIN_OWNER (see P3) |  | yes |
| D29 | S10 | Buy-side delta | 0.6-0.7 (engine) | ≥0.7 + VIX/expiry conditioning | 0.6-0.7 | S24 (raises floor); engine on OLD |  | yes |
| D30 | S10 | Stop-loss philosophy | structural 1st-candle + 5pt gap-trail | wide point SLs (N 50-60/BN 100/Sensex 200-250) | structural 1st-candle | Both (different SL modes) |  | yes |
| D31 | S10 | Intraday window close | after 9:45, avoid after 3:30 | 9:45-2:30 (post-2:30 = next-day) | — | S24 (tightens close) |  | yes |
| D32 | Shared-S2 | Parabolic SAR notation | 0.02, 0.2 (two-value; engine) | 0.02, 0.02, 0.2 (three-value) | 0.02, 0.2 (two-value) | Engine = OLD two-value |  | no |
| D33 | Shared-S2 | RSI buy floor | >60 (§4.2/engine) | buy 50-75 | >60 | UNCERTAIN_OWNER (60 vs 50 — see P3) |  | yes |
| D34 | Shared-S2 | IV 6-strike trending gap | ≥10pt (S20) / 7-10pt (S22) | no figure (glossary) / ~8-10pt (cheat) | 0.10 | S24 8-10 sits between; engine 10 |  | yes |
| D35 | Shared-S2 | India VIX upper bands | 15-16 / 17+ (S21) | 15-18 / 18-20+ / 20-25+ | — | S24 (finer, latest) |  | yes |
| D36 | Shared-S1-risk | Bank Nifty point-SL | ~75 pts (S22) | ~100 pts (S24) | ~75 pts | S24 (latest) |  | yes |
| D37 | Shared-S1-risk | Nifty point-SL | ~30 pts (S22) | ~50-60 pts (S24) | ~30 pts | S24 (latest) |  | yes |
| D38 | Shared-S3-oi | Monthly-expiry OI handling | confirm both positional+intraday (S21) | IGNORE the OI data | engine suppresses | S24/engine (suppress) |  | yes |
| D39 | Shared-S4 | IV directional gap floor | 10pt (S20) / 7-10pt (S22) | 8-10pt | 0.10 | S24 8-10 between OLD anchors |  | yes |
| D40 | Shared-S4 | India VIX upper bands | 15-16 / 17+ (S21) | 15-18 / 18-20+ / 20-25+ | — | S24 (latest) |  | yes |
| D41 | Shared-S5 | Buy-strike delta | 0.6-0.7 (engine, picks 0.65) | ≥0.7 floor + scaling | 0.6-0.7 (picks 0.65) | S24 raises floor; engine on OLD |  | yes |
| D42 | Shared-S5 | Time rails | after 9:45 / midday 11-13 block / 15:30 cap (engine) | 9:45-2:30 soft 2:30 cutoff | midday 11-13 block / 15:30 cap | Reconcile (engine keeps midday block) |  | yes |
| D43 | Shared-S5 | Sensex point-scaling | ~3x (exact % figures) | ~3-4x (+ single-candle ~5000pt note) | — | S24 (wider/latest) |  | yes |
| D44 | Shared-S6 | India VIX bands | 10-11/12-14/15-16/17+ | 10-11/12-14/15-18/18-20+/20-25+ | — | S24 (latest) |  | yes |
| D45 | Shared-S6 | Dollar Index cutoff | >105 negative / <90 ideal (§3.10 primer) | >100 = FII selling / ideal 92-93 | — | S24 (latest live read) |  | yes |

### The finer per-scope "Key drifts" not promoted to §3 (comparison says ~5; only 3 crisply enumerable)

| # | Scope | Rule | OLD value | S24 value | Engine value | My rec (Which to trust) | **Your pick (OLD / S24 / RECONCILE)** | Signal-affecting? |
|---:|---|---|---|---|---|---|---|---|
| D46 | S7 Hero-Zero | Point-SL set vs deep-SL set | OLD point-SL set BN~75 / N~30 | S24 deep-SL set N~50-60 / Sensex~200-250 / BN~100 | OLD point-SL set | Keep both, do not conflate (per S7 Key drifts) |  | yes |
| D47 | S7 Hero-Zero | Budget / sizing basis | flat-2000 budget | ~10% of profits (never capital) | — | S24 (latest framing) |  | no |
| D48 | S7 Hero-Zero | Overbought RSI number | RSI >75 (carry) | encoded/FULL at 80 | RSI 80 (ENCODED/FULL) | Carry 80-vs-75 drift — gate stays, number reconcile |  | yes |

> **Comparison under-specifies the last ~2 finer drifts.** The comparison states **5** finer per-scope "Key drifts" not promoted to §3, but only **3** are crisply enumerable in the doc — the S7 trio above: **D46** (point-SL-set vs deep-SL-set), **D47** (budget flat-2000 vs ~10%-profits), **D48** (RSI 80-vs-75). The remaining ~2 are NOT crisply enumerated anywhere in the comparison, so they are left as this flag rather than fabricated. (The earlier draft filled these slots with two Shared-S2 audit-ADDED SHARED rules — "wider-gap=stronger-trend" and "VWAP-timing yesterday-until-10:30", both L305 — but those are shared-rule ADDITIONS, not value drifts: neither carries an OLD-vs-S24 value change and "My rec" for the VWAP one literally reads "Either (audit-added shared, same intent)". They have been removed from Part 2; they belong in a keep/shared-additions annex, not the drift list.)
>
> **Audit-added SHARED rules (NOT drifts — moved out of Part 2):** for completeness, the two L305 audit-added shared rules are: (a) Shared-S2 "wider-gap = stronger-trend, crossover-not-required"; (b) Shared-S2 "VWAP-timing yesterday-until-10:30 (<10:30 suppression / prior-day-VWAP-as-level)". These are shared-rule additions with the same intent as existing rules — rule on them as KEEP candidates in W1, not as parity-safe drift tasks.

> **Count note:** the comparison's §2 `drifted` column sums to **50**; §3 tabulates **45** (D6-D45). D1-D5 are the 5 dangerous engine-vs-doc drifts pulled to the top (they are a subset/emphasis of §3 rows #28/#33, #31/#40, #36, #41 — listed first per instruction). D46-D48 are the **3 crisply-enumerable** finer intra-scope "Key drifts" lines (the S7 trio); the comparison claims ~5 finer drifts but the last ~2 are not crisply enumerated (see flag above), so Part 2 holds **48** concrete drift rows + that flag rather than 50 fabricated rows. **D3 is an umbrella delta-floor row** whose scope (Shared-S2/S5/S9/S10) DUPLICATES D26 (S9), D29 (S10) and D41 (Shared-S5); per the §2 count-note these are NOT four independent drifts — one delta-floor ruling propagates across D3/D26/D29/D41 (and the 5-dangerous D3 is the same physical drift as engine-on-OLD 0.6-0.7). Owner: where the same physical drift appears twice (e.g. delta 0.6-0.7 in D3 and D26/D29/D41), one ruling covers all instances.

---

## PART 3 — UNCERTAIN_OWNER rulings (open owner-blockers)

Items the comparison explicitly left `uncertain` / `UNCERTAIN_OWNER` / flagged as an open owner-blocker. Your ruling resolves them.

| # | Scope | Question | The ambiguity | **Your ruling**                                                                                                                                                       |
|---:|---|---|---|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| U1 | S6 Golden Crossover | Bearish-RSI threshold for PE-side automation: card >25 or matrix <25? | OLD §3.6 card says RSI **>25**; the OI matrix says **<25**. S24's 40-25 band does **NOT** resolve which one governs the PE-side trigger. Open owner-blocker for PE-side automation. | RSI is between 40 to 25 for PE side trigger                                                                                                                           |
| U2 | S6 / S10 | Engine RSI band override: doc 50-75 / 40-25 vs code CE 60-80 / 40-60 (dead) | The live engine uses CE **60-80** with **40-60** as the dead/no-trade zone; both docs prescribe **50-75 / 40-25**. Doc-vs-code disagreement (drift #13/#18/#23/#28). Which governs? | S24 Doc governs                                                                                                                                                       |
| U3 | Shared-S2 | RSI buy floor: 60 or 50? | §4.2/engine buy floor is **>60**; S24 cheat says buy band **50-75** (floor 50). Drift #29/#33. Which floor governs entries? | s24 Doc governs                                                                                                                                                       |
| U4 | S4 Gap Theory | SuperTrend-level SL ambiguity | OLD prescribes a **SuperTrend-level SL**; S24 prescribes a competing **structural + points SL**; the engine **defers** (no single encoded gap SL). Was "keep", re-bucketed uncertain. Which SL mode is canonical for Gap? | S24 Doc governs                                                                                                                                                       |
| U5 | S4 Gap Theory | Deep-ITM gap-day note | A deep-ITM gap-day note flagged `uncertain` — unclear whether it is a live rule or an illustrative aside. Keep or drop? | S24 Document governs                                                                                                                                                  |
| U6 | S5 Trending OI | S21 "best 10-11:30 window" | Flagged `uncertain` — competes with the hard 9:40-2:30 / soft-2:30 window. Is 10-11:30 a preferred sub-window to keep, or superseded? | Superseded                                                                                                                                                            |
| U7 | S9 Morning Trade | OIP-AI 9:11 signal + 9:18 exit timing | Flagged `uncertain`, "lean keep on timing" — OIP-AI feed may not exist, but the 9:11/9:18 timing read may be load-bearing. Keep the timing, drop the feed? | loosely keep the timing and instead of OIP-AI we have our on OI confluent gate replacing it already implemented instead of percentage it give low, mid, high response |
| U8 | Shared-S4 | OIP-AI-matches-premarket + 3:20 | Flagged `uncertain` — depends on whether the OIP-AI premarket-match feed is available; the 3:20 view-match read may stand independently. | instead of OIP-AI we have our on OI confluent gate replacing it already implemented instead of percentage it give low, mid, high response |

> **Count note:** Part 3 captures **8** uncertain/open-blocker items. U1 (bearish-RSI card >25 vs matrix <25) and U2 (engine RSI 60-80 vs doc 50-75) are the two the prompt named explicitly; U4 (SuperTrend-level SL) and U5 (deep-ITM gap-day note) are the other two named. U3/U6/U7/U8 are the remaining `uncertain` flags found in the per-scope tables.
