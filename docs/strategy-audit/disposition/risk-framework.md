# Risk Framework — Global Risk Management (Section 2) — gap disposition

Every non-FULL row from `docs/strategy-audit/risk-framework.md` (the audit table, lines 7–51) is assigned
exactly one disposition so no gap is left unaccounted. Source non-FULL rows = **42** (PARTIAL / NONE /
MANUAL_COVERED; 45 table rows − 3 FULL at L21/L24/L31). Dispositions reference the two follow-up plans:
- **FU1** = `docs/superpowers/plans/2026-06-27-followup1-expand-manual-checks.md` (adds 9 manual checks).
- **FU2** = `docs/superpowers/plans/2026-06-27-followup2-soft-dots-to-hard-gates.md` (promotes 4 soft dots —
  indicator-alignment, futures-OI quadrant, breadth, basis — to hard gates; VIX + Dow OUT of scope there).

Note: Section 2 is the strategy-agnostic money-management layer. FU1 (manual checks) and FU2 (OI/indicator
gates) target the per-strategy signal seam, so they cover almost nothing here — the bulk are sizing/cap
rails (AUTOMATE_PKG) or trader-psychology/process rules (KEEP_MANUAL_NEW).

| Gap (rule) | Doc § | Audit status | Disposition | Work-package / note |
|------------|-------|--------------|-------------|---------------------|
| 1:2 RR = 0.5% risk : 1% reward per trade; set RR target before entry | 2.1 r1–3 | NONE | AUTOMATE_PKG | `sr-levels-targets-stops` — compute target = entry ± 2×stop-distance and stamp it (scalpers carry no `take_profit`) |
| Once entered, wait for SL or target — do not interfere | 2.1 r4 | PARTIAL | AUTOMATE_PKG | `trade-management-targets-trailing` — paper auto-holds to stops; the "wait for target" half needs a target leg |
| Risk = 0.5% of capital per trade vs 1% reward | 2.2 r5 | NONE | AUTOMATE_PKG | `probability-graded-sizing` — switch sizing to `atr_risk` with `risk_pct_equity:0.5` keyed off the structural stop |
| Never deploy >10–20% of capital in a single trade; never >20%/day | 2.2 r6 | NONE | AUTOMATE_PKG | `probability-graded-sizing` — cap premium_budget at a % of equity (per-trade + per-day deployment cap) |
| Never buy full qty in one go; deploy smallest first, add nearer VWAP/ST/S-R | 2.2 r7 / 2.11 r47 | NONE | AUTOMATE_PKG | `scale-in-ladder` — laddered/scale-in sizing (needs multi-leg entry support; engine emits one full qty today) |
| If candle–VWAP/Supertrend gap too wide, wait or skip (smallest-SL trade only) | 2.2 r8 | NONE | AUTOMATE_PKG | `vwap-distance-sizing` — reject when \|close−vwap\| exceeds a band |
| Winning-trade qty = losing-trade qty (no oversized losers) | 2.2 r9 / 2.11 r44 / 2.12 r49 | NONE | AUTOMATE_PKG | `probability-graded-sizing` — track per-day win vs loss notional and warn on asymmetry |
| Fix SL limit based on previous trade's profit (risk prior gains first) | 2.2 r10 / 2.14 r67 | NONE | AUTOMATE_PKG | `probability-graded-sizing` — read day P&L from `dayPnl` and cap risk to booked/prior profit |
| SL on DEPLOYED capital (~10%), target on OVERALL capital; size SL off delta | 2.11 r45 | NONE | AUTOMATE_PKG | `probability-graded-sizing` — deployed-vs-overall sizing frame + delta-scaled point stop (not built) |
| Decide daily profit & loss target before the first trade; stop when hit | 2.3 r11 / 2.6 r25 | PARTIAL | AUTOMATE_PKG | `daily-target-caps` — seed a default `daily_loss_limit` + add a daily profit-target stop (loss cap is OFF by default, no profit-target row) |
| 0.5% rule: stop all accounts once losses reach 0.5% of capital/day | 2.3 r12 | PARTIAL | AUTOMATE_PKG | `daily-target-caps` — seed a 0.5%-of-equity default (`daily_loss_limit pct` mode exists but unset) |
| Never lose >2–3%/capital on any day; per-account loss ≤2% | 2.3 r13 | PARTIAL | AUTOMATE_PKG | `daily-target-caps` — wire the dead YAML `max_daily_loss_pct:2.0` into the gate, or seed the DB row |
| Daily profit target 1–2% of capital (a %, not rupees) | 2.3 r14 / 2.14 r66 | NONE | AUTOMATE_PKG | `daily-target-caps` — add a daily profit-target stop on `dayPnl` (only a 5-WIN count cap exists) |
| Split capital across 5 accounts; 1% target per account, rotate | 2.4 r15–16 | PARTIAL | AUTOMATE_PKG | `five-account-ledgers` — true per-account capital split + per-account 1% target needs a schema change (today day-granularity counts only) |
| First trade should be a successful trade | 2.4 r18 | NONE | KEEP_MANUAL_NEW | Psychological/judgement — aim for a high-conviction first trade; not automatable; future manual-check candidate |
| Stop-account-after-loss: account's first trade loses ⇒ stop that account | 2.4 r19 | PARTIAL | AUTOMATE_PKG | `five-account-ledgers` — per-account first-loss freeze needs per-account ledgers (today an aggregate 5-loss freeze) |
| Cut losses quickly; exit and wait (don't marry trades) | 2.5 r21–22 / 2.6 r28 | PARTIAL | ACCEPT_BY_DESIGN | Time-stop + structural-stop fire automatically; "cut very very quickly" is a discretion the bar-count stop approximates — acceptable, trader still cuts fast on a clear failure |
| Caution (sellers near expiry): gamma-spike SLs skippable — 1 lot, limit count | 2.5 r23 | NONE | ACCEPT_BY_DESIGN | Scalpers are long-premium (buy-side); naked-seller gamma risk is out of scope until a selling path exists (deferred, SPAN-gated) |
| No averaging down; don't add after VWAP/ST broken; pyramid only toward target | 2.6 r26–27 / 2.11 r43 / 2.12 r50 / 2.13 r56 / 2.14 r62–63 | PARTIAL | ACCEPT_BY_DESIGN | Single active entry per (version,symbol) structurally blocks averaging in paper; upside-pyramiding is the separate `scale-in-ladder` pkg — no-averaging itself is by-design correct |
| Don't over-trade / revenge-trade; stop at loss target, return next day | 2.6 r24–25 / 2.14 r66 | PARTIAL | AUTOMATE_PKG | `daily-target-caps` — 5-loss freeze + loss-limit cap by count; add a time-of-day qty taper for afternoon size-creep |
| Trend is your friend — don't trade against the trend | 2.7 r29 | PARTIAL | ACCEPT_BY_DESIGN | Per-side indicator/OI/breadth/VIX + 1h Supertrend bias gates already enforce trend agreement; `regime_ok` covers the broader read — soft-by-design |
| Don't believe the trend 100%; trade only when the setup fits | 2.7 r30–31 / 2.8 r33 | MANUAL_COVERED | COVERED_EXISTING | `clean_setup` / `regime_ok` (ScalperManualChecks) — shipped 7-item checklist |
| Pre-market / post-market analysis; prepare for next session | 2.9 r34 | NONE | KEEP_MANUAL_NEW | Process routine — no data source; trader does pre/post-market prep; future manual-check candidate |
| Check prerequisites (internet, laptop/mobile, battery) | 2.9 r35 | NONE | KEEP_MANUAL_NEW | Environment check — not automatable; trader self-verifies connectivity/hardware |
| Trade in a calm place; not while travelling/stressed | 2.9 r36 | NONE | KEEP_MANUAL_NEW | Psychological readiness — not automatable; trader self-check |
| Maintain a trade journal | 2.9 r37 | PARTIAL | AUTOMATE_PKG | `auto-journal` — a Journal page exists but nothing requires/auto-populates it from the paper ledger |
| Trade only money you can afford to lose; no borrowed / no re-adding funds | 2.10 r38 | NONE | KEEP_MANUAL_NEW | Financial conduct — out-of-app; trader self-governs capital source |
| Preserve capital first; treat as a fixed deposit | 2.10 r39 | PARTIAL | KEEP_MANUAL_NEW | Principle — capital-preservation is the gates' design intent but no explicit untouched-base rail; trader keeps preservation primary |
| Risk/capital plan must survive a full quarter of losses | 2.10 r40 | NONE | AUTOMATE_PKG | `probability-graded-sizing` — a multi-month drawdown-survival sizing model (not built) |
| Never entertain the big loss (of the 5 outcomes) | 2.10 r41 | PARTIAL | AUTOMATE_PKG | `daily-target-caps` — hard SL + freezes bound it; seed the 10–12%/day cap so no single day blows out |
| Back-test any strategy ≥1 year (~70% standard) before deploy | 2.10 r42 | PARTIAL | AUTOMATE_PKG | `backtest-fidelity-rails` — a publish-gate on backtest coverage (≥1yr) before going live (infra exists, no gate) |
| Scale lot size slowly, raise only at 3–6-month intervals | 2.12 r51 / 2.14 r66 | NONE | KEEP_MANUAL_NEW | Manual sizing policy — no lot-ramp schedule; trader raises base lots every 3–6 months; trader discretion |
| Sell only hedged — never naked options | 2.11 r46 / 2.13 r61 | NONE | ACCEPT_BY_DESIGN | All scalpers are long-premium so naked selling never arises in automation; a hedge-pairing check is for a future selling path (out of scope, SPAN-gated) |
| Risk only where probability is higher; Hero-Zero/deep-OTM = slice of profits only | 2.12 r52 / 2.13 r58 / 2.14 r67 | PARTIAL | AUTOMATE_PKG | `probability-graded-sizing` — a low-delta sizing cap for Hero-Zero/OTM stakes (delta/premium bands bias to ITM but no profit-slice cap) |
| Index-scaled point stop-losses — Nifty ~30/50–60, BankNifty ~75, Sensex ~50–100/200–250 pt | 2.12 r53 / 2.13 r55 / 2.14 r64 | NONE | AUTOMATE_PKG | `sr-levels-targets-stops` — clamp the structural stop to an index point band (stops are structural extremes today, no per-index point constant) |
| Allocate only 5–10% of total capital to trading (diversify the rest) | 2.13 r54 / 2.14 r67 | NONE | KEEP_MANUAL_NEW | Out-of-app net-worth allocation — not automatable; trader keeps only 5–10% in the trading account |
| Single-day hard loss cap ~10–12% of trading capital; exit on VWAP break | 2.14 r62 | PARTIAL | AUTOMATE_PKG | `daily-target-caps` — seed/clamp the daily loss cap ≤10–12% (VWAP-break exit is already gated) |
| Size to VIX & to the Trending-OI gap; chart/OI divergence or balanced both-side OI ⇒ reduce qty | 2.14 r65 / 2.13 r57 | NONE | AUTOMATE_PKG | `probability-graded-sizing` — scale qty by VIX band + Trending-OI gap (VIX gates only direction today; `suggested_qty` is volatility-blind) |
| India VIX not abnormally spiking (gap/whipsaw risk) | 2.8 / 2.13 / 4.5 | MANUAL_COVERED | COVERED_EXISTING | `vix_normal` (ScalperManualChecks, doc_ref 4.5) — shipped 7-item checklist |
| Global cues not against the trade (DOW futures, Asian indices, crude, USD) | 2.9 / 4.7 | MANUAL_COVERED | COVERED_EXISTING | `global_cues_ok` (ScalperManualChecks, doc_ref 4.7) — shipped 7-item checklist |
| News overrides data on gap/event days | 2.13 r59 | MANUAL_COVERED | COVERED_EXISTING | `news_clear` (ScalperManualChecks, doc_ref 2.13) — shipped 7-item checklist |
| Skip morning prints / opening volatility on volatile days | 2.13 r59 | PARTIAL | ACCEPT_BY_DESIGN | The ≥09:45 floor gates fresh entries for the core set; the #9 Morning-Trade variant deliberately opens 09:15–09:30 — by-design, trader avoids chasing opening prints elsewhere |

### Disposition counts

- COVERED_EXISTING: 4
- COVERED_FU1: 0
- COVERED_FU2: 0
- AUTOMATE_PKG: 24
- KEEP_MANUAL_NEW: 8
- ACCEPT_BY_DESIGN: 6
- UNCERTAIN_OWNER: 0
- **Total non-FULL rows: 42** (matches the 42 non-FULL rows in `risk-framework.md` lines 7–51: 45 table rows − 3 FULL at L21/L24/L31)

### AUTOMATE_PKG themes (for the synthesizer)

- `probability-graded-sizing` — 0.5%-risk sizing, deployment caps, win=loss qty symmetry, recycle-profit risk, deployed-vs-overall frame, survive-a-quarter model, Hero-Zero low-delta cap, VIX/OI-gap qty scaling (9 rows)
- `sr-levels-targets-stops` — 1:2 RR target leg + index-scaled point-SL band
- `scale-in-ladder` — smallest-first laddered deployment (multi-leg entry)
- `vwap-distance-sizing` — skip when price too far from VWAP/Supertrend
- `daily-target-caps` — seed daily profit/loss targets, 0.5%/2–3%/10–12% caps, over-trade taper (6 rows)
- `five-account-ledgers` — true per-account capital split + per-account 1% target + first-loss freeze (2 rows)
- `auto-journal` — auto-populate the Journal from the paper ledger
- `backtest-fidelity-rails` — ≥1yr-backtest publish gate
- `trade-management-targets-trailing` — hold-to-target leg (no-interference discipline)
