# Session findings — 2026-08-13 (data date)

Analysis date: 2026-08-13 EOD (scheduled post-market agent). Analyst: Claude (scheduled
`session-analysis post`). Data: `signal_rejections` rows **1,015** (bounds
`2026-08-13T09:15:00+05:30`…`15:40`; last row 14:58), signals fired **2 ENTRY + 1 EXIT**,
paper trades **2 opened / 2 closed, net −₹3,073.00**, shadow closes **45** (champion 23 +
challengers 22).

Session character: **Thursday, BSE weekly expiry (SENSEX weeklies expiring today; no NSE
expiry)** · VIX 11.39–11.78 · steady down-drift then afternoon CE bounce (open 24,431.60 =
session high → low 24,311.40 → continuous close 24,353.35; CE 434 / PE 301 context rows —
the first CE-heavy high-composite tail since 08-05) · signal contract `NFO:NIFTY26AUGFUT`
(confirmed from the signal rows' `tradingsymbol`; the engine log is gone — see §6.1).

⚠️ **Session-log destruction caveat (§6.1, new README §3.37):** both strategy-signal and
market-data containers were RECREATED at **15:44 IST** (14 minutes after close, post-close
deploy of #1369/#1370 + the #1075 republish). Every log-dependent check for this session —
§3.17 canary WARN/straddle count, §3.34 heat-grep, §3.10 boot line, §3.18 log confirmation —
is **UNVERIFIABLE**; DB substitutes are used and labeled below.

## 1 Funnel numbers

| metric | value |
|---|---|
| rejections | 1,015 (**36 of 38** scalpers — 4 newly emitting: `trend-change-nifty`, `trend-change-sensex-niftyoi`, `open-high-low-nifty`, `open-high-low-sensex-niftyoi`; 2 silent vs 08-12: the `hero-zero` NIFTY-OI pair. Regime, not config: no publish landed between sessions until 15:45 today) |
| eval outcomes | chart-gate-failed 2,131 · confluence-blocked 1,015 · composite-below-threshold 370 · **fired 2** · discipline-paused 0 |
| fired reconciliation (§3.36) | 2 fired evals = **2 ENTRY signals emitted** (10:30, composite 0.8952 both) — no suppressed class today; `daily_profit_target` never tripped (red day) |
| coverage | 23 buckets populated 09:15–14:58; 15:00+ is the out-of-window class, **not** a stall — eval buckets kept writing (15:00–15:18 total 2/bucket, all `composite-below-threshold`; zero-rows to close), `subscriber_health_events` 0 rows |
| boot health | engine_reloads: boot 08:03 IST with a **6-min 0/38-unresolved transient**, resolved **38/0/0 at 08:09:55** (pre-open; longer than 08-12's 3-min transient — watch); post-deploy reload 15:45 installed 38/0/0 [computed from `strategy.engine_reloads`; log boot line destroyed] |
| first-block histogram | volume-floor 497 (49.0%) · time-window 192 · rsi-band 96 · option-side-constraint 44 · time-of-day 42 · confluence-composite 32 (avg margin −0.039) · divergence-vol-gate 24 · rest ≤22 |
| all-fails expansion | confluence-composite 705 · volume-floor 497 (avg operand 6,564 vs banded avg thr 12,971) · **strike-pick 336** · rsi-band 279 · time-window 192 |
| paper (funded) | pos 64 `SENSEX2681378000PE` 20 @ 324.95 (10:31) → **STOP_LOSS 10:51 −₹1,696.96** · pos 65 `NIFTY2681824550PE` 65 @ 227.20 (10:31) → **TIME_STOP 11:01 −₹1,376.04**. Emit latency 20.9–23.5 s (same structural class as G8/T26) |

## 2 Rail findings

- **§2.2 chain-proximity: BSE-Thursday cluster hit — `strike-pick` 336 fails, ALL
  SENSEX-rooted (16 of 16 slugs, 0 NIFTY)** on BSE weekly day-of. Series: 08-03 eve 235-NF /
  08-04 day-of 604-NF / 08-05 Wed 0 / 08-06 **BSE-weekly day-of 0** / 08-07 Fri 350-SX /
  08-11 NSE day-of 322-NF / 08-12 Wed 0 / **08-13 BSE-weekly day-of 336-SX**. The BSE weekly
  day-of is now **1 saturation vs 1 zero (n=2)** — 08-06's zero was the outlier reading, per
  the §3.27 2026-08-07 amendment. Saturation is PARTIAL: the picker still resolved SENSEX
  legs on other bars (funded pos 64 and the 10:30 shadow cluster are expiring-today SENSEX).
  Next windows: Fri 08-14 (post-expiry cluster day), Mon/Tue 08-17/18 (NSE side).
- **volume-floor banded and honest on a thin tape**: avg threshold 12,971 vs the day's
  aligned 3m AUGFUT volume p50 7,085 / p90 22,815 / max 63,310 — floor ~p70–75, zero
  flat-threshold rows. §3.14 check: `relative-volume-floor` armed **38/38** (36 republished
  15:45 today, 2 hero-zero at 07-28 — see §6.2).
- **Would-have-fired set (§3.5): exactly ONE row** — 10:24 `scalp-connect-the-dots-nifty-pe`
  (composite 0.6029, volume-floor sole blocker, `NIFTY2681824550PE` @ 216.80). Champion
  shadow traded that exact leg/bar (8 rows, −358.20 pts): **WOULD-LOSE**. No manual §4.2
  pricing needed; no rejection class escaped the shadow book today.

## 3 Composite + dots

- Distribution (scored n=735): pass mass **242/735 (32.9%)**; max 0.8137 in rejections
  (fired bar 0.8952). The high tail is ALL CE (0.7+: 134 CE, 0 PE) — afternoon bounce; the
  morning PE leg fired instead of queueing.
- Dot support (complete session, n=735 unless noted): `iv_rank` 0% (withheld, standing) ·
  `iv_pair` **0% (18th session — T3, owner)** · oi_spurt 3.8% · `iv_slope` 7.9% (n=101) ·
  breadth 21.2% · trending_cross 23.7% · volume 32.4% · vwap 41.4% · futures_oi 55.2% ·
  underlying_oi 55.6% · basis 59.0% · rsi 59.3% · sentiment_slope 61.8% · vix 71.6% · psar
  72.9% · sentiment 73.7% · drastic_oi 83.1% · vwma 90.3% · supertrend 97.3% · `iv_abs_band`
  **100% (n=101; frozen daily stamp, 14th session)**.
- **§3.28 breadth side-split, 3rd consecutive session with a dead CE side**: CE tests
  advances (session range 20–31 vs `>32` — max 31, never crosses → CE 0 supports); PE tests
  declines (30–35, straddling the line → partial support). Unlike 08-11/08-12 the PE side is
  NOT saturated-100 today — the operand actually crossed intra-session. T30 evidence row.
- OI bloc fully live: quadrants NEUTRAL **0/735** (LONG_BUILDUP 233 / SHORT_BUILDUP 214 /
  SHORT_COVERING 190 / LONG_UNWINDING 98); `futures_oi_snapshots` **372/375 minutes**.

## 4 Data health

| field | today | verdict |
|---|---|---|
| `ivRank` | NULL 1,015/1,015 | dead-data, standing (since 07-02) |
| `dowUp` | NULL 1,015/1,015 | by design (un-armed) |
| `fiiLongPct` | NULL 280 = exactly the 280 context-less pre-fetch rows; 1 distinct on the rest | daily EOD stamp, alive |
| `atmIv` | 1 distinct | frozen daily stamp — correct (G12/T28, 14th) |
| vix | 28 distinct, 11.39–11.78 | alive |
| ceIvAvg6 / skew / basis | 45 / 61 / 61 distinct | alive |
| misaligned 1m candles (§3.15) | **0 rows** | clean |
| §3.17 canary | **UNVERIFIABLE — session logs destroyed by the 15:44 recreate** (§6.1) | first session since 07-24 with no WARN/straddle count; not evidence of anything |
| Kite session | validated 15:55 IST | ✓ |
| market-data canary | GREEN, 0 problems, 73 ticked tokens (post-close read) | ✓ |

## 5 Shadow-book outcomes + counterfactuals

**Champion: 23 closes, 7W, −923.50 pts, −₹31,838.13 NET → 4 deduped (bar, leg) events;
all-time −₹128,548.02 → −₹160,386.15** (2nd-worst session on record). The day in four
events:
- 10:24 `NIFTY2681824550PE` ×8 **−358.20 pts / −₹23,837.21** (the funded pos 65's twin
  class) · 10:30 `SENSEX2681378000PE` ×7 **−660.70 pts / −₹13,610.26** (funded pos 64's
  leg) — both morning PE entries near the low, killed by the afternoon bounce.
- 11:39 `NIFTY2681824250CE` ×7 **+118.65 pts / +₹7,195.58** · 12:00 same leg ×1 −23.25 pts.
- **§3.24 multi-exit clusters — back to STOP-FAVOURING after 08-12's single pro-hold
  observation**: on BOTH morning clusters the 15:12 SQUARE_OFF hold was worst or near-worst
  (10:24: structural −10.10 avg / SL −57.75 / hold −49.25 · 10:30: structural −74.55 / SL
  −86.10 / **hold −167.20**). G11 series now 6 stop-favouring vs 1 pro-hold (08-12), the
  regime-dependence reading intact. The funded book's exits (SL 10:51 / TIME_STOP 11:01)
  beat every shadow model on the same legs today.

**Challengers: all three traded, all lost — 7th measured entry-gate loosening, 7th loss.**
`composite-055` 7 closes −₹2,246.84 (first accepted rows since 08-07; midday CE chase
12:12–14:03) · `vol-12k5` 7 closes −₹5,549.88 · `vol-off` 8 closes −₹4,307.58. The
challenger-only class (rows only a loosened config accepts) was uniformly negative again.

**§4.2 counterfactuals: none beyond §2's single would-have-fired row (WOULD-LOSE via its
shadow twin).** No suppressed-fire class (no risk-gate trip), no strike-pick-blocked leg
worth pricing (the saturated root's family was refused at the picker on a tape where its
priced twins lost).

## 6 New data points / anomalies

### 6.1 Post-close recreate destroyed the session's logs BEFORE forensics ran → README §3.37

The 15:44 IST recreate (deploy of #1369/#1370 + #1075 republish) happened 14 minutes after
close and before this run — the third log-loss this month class (07-17 boot line, 08-10
outage), but the first caused by a ROUTINE post-close deploy rather than an incident.
Lost for 2026-08-13, permanently: §3.17 canary WARN/straddle counts, §3.34 heat-grep,
§3.10 boot line, §3.18 log confirmation, any §3.36-class suppression line. DB substitutes
used: `engine_reloads` (boot health), signal rows (contract), `margin_snapshot` (§6.2).
**Promoted to README §3.37** (log-dependent checks and their DB fallbacks) with the process
proposal: snapshot `docker logs` of both services to a file BEFORE any post-close recreate
(extends the standing incident rule to routine deploys), or deploy after ~19:00 once the
post-market run has grepped them. Proposal row NEW-4 in §7.

### 6.2 NEW-2 SHIPPED: scalper `budget_inr` ₹15,000 → ₹25,000 (owner decision, on live data)

Commit `101dc847` (#1075) merged **08:54 IST today** (60 YAMLs: 36 enabled + 24 disabled;
the hero-zero trio untouched by design), republished **15:45 IST — post-close**, so **today's
session still ran ₹15,000 and the ₹25k budget is first live 2026-08-14**. Today's NIFTY leg
funded anyway (premium 227.20 < the old ₹230.77 ceiling; 6th funded NIFTY scalper position)
— and lost, a same-day reminder that fundability ≠ edge. Residue: the 24 disabled scalpers
now carry 08-13 drafts (see §6.4 drift); republish for them is moot while disabled but
becomes MANDATORY before any re-enable (stale-publish trap #1016). Tomorrow's run must
verify sizing under ₹25k: SENSEX legs can now take up to 3 lots at ~₹410 premium, NIFTY 1
lot to ₹384 / 2 lots to ₹192.30.

### 6.3 §3.34 heat-gate evaluability — WEAK PASS via DB proxy only

Log grep impossible (§6.1). Proxy: both funded positions carry `margin_snapshot = 0.00,
margin_pct = 0.00` (populated, not NULL) — consistent with a SUCCESSFUL margin call pricing
a long option at zero SPAN [computed; the unpriced-path column semantics were not verified
in code this run]. N23-A (zero-SPAN coverage question) stands.

### 6.4 Mechanical pre-checks

- `tools/ledger-consistency-check.py`: **11 REVIEW lines (was 10)** — the 10 standing
  false-positives (now 5→6×[A] open/closed snapshot chips + 5×[B] keyword refs); the +1 is
  an [A]-class chip from the 08-13 morning swing-ledger updates (#1363/#1365 wave), same
  self-referential snapshot class. No substantive contradiction; no edits required.
- `tools/published-config-drift.py`: **69 published — 69 matched (43 clean, 26 drifted:
  24 NEW STALE-PUBLISH = the #1075 drafts on disabled scalpers, expected residue of §6.2 +
  the same 2 minervini 1.0.2 drafts of 08-01, 9th session), 0 DB-only, 0 YAML-only.**
  Nothing republished by this run. The minervini republish proposal carries; the 24
  disabled-scalper drafts get a standing note: **diff GAINS/LOSES and republish before any
  re-enable**.

### 6.5 §3.29 unexercised-path audit (day delta)

Fired vocabulary since 07-01: TRAILING_STOP 13 · **TIME_STOP 9 → 10 (+1: pos 65)** ·
STRUCTURAL_STOP 6 · **STOP_LOSS 5 → 6 (+1: pos 64)** · MANUAL 2. Armed set unchanged (10
(type,basis) rows + `oi-confluence-exit` tag on 8). Never-fired stands: `take_profit
premium_pct` (36 — class (c) SHADOWED per 08-12's evidence) · `signal_exit` (38) ·
`square_off` (2) · `stop_loss percent` (4) · tag `oi-confluence-exit` (8). INDETERMINATE
pair (`trailing_stop atr_multiple` 2, `stop_loss atr_multiple` 2) stands.

### 6.6 §3.30 freeze telemetry

Entries: sub-1 ×1 (10:31), sub-2 ×1 (10:31); subs 3–5 zero (only 2 fired evals all day).
Day PnL: sub-1 −₹1,696.96 (first-loss frozen ~10:51) · sub-2 −₹1,376.04 (first-loss frozen
~11:01). **2 of 5 stopped before 14:30 — below the ≥3 flag.** Frozen-by trend: 08-11 1/5 ·
08-12 3/5 + global pause · 08-13 2/5. `risk_audit` wrote exactly 1 row today (08:35, the
swing pyramid-cap refusal — #1370's one-row semantics on its first live morning).

## 7 Tuning candidates

Ledger §0 group G is the authoritative status; nothing applied by this run.

| # | knob | status | today's evidence |
|---|---|---|---|
| watch | `strike-pick` chain-proximity | **WATCH — BSE-Thu day-of saturated (336-SX)**; BSE-weekly day-of now 1-of-2 | series §2; next: Fri 08-14 cluster day, then NSE Mon/Tue |
| NEW-2 (08-12) | NIFTY budget vs lot-65 premium | **SHIPPED (#1075, `101dc847`)** — live from 08-14 | republished 36/38 at 15:45; verify sizing tomorrow (§6.2); 24 disabled-scalper drafts = deliberate residue |
| **NEW-4 (08-13)** | post-close deploy log snapshot | **PROPOSED (process, owner)** | 15:44 recreate destroyed all log-dependent session evidence (§6.1); snapshot logs pre-recreate or deploy after ~19:00 |
| NEW-3 (08-12) | `daily_profit_target` 1.5% | **OBSERVATION (owner) — carried** | no trip (red day); no new evidence |
| NEW-1 (08-05) | paper heat-cap margin timeout | **PROPOSED — carried** | weak PASS via margin_snapshot proxy only (§6.3); log grep impossible |
| T29/G11 | scalper `time_stop` | **CLOSED — evidence row added** | back to stop-favouring: both morning clusters' 15:12 hold worst-or-near-worst; funded exits beat every shadow model (§5). Series 6 stop / 1 hold |
| T30 | `breadth` dot `>32` | **OPEN** | 3rd session dead-CE-side (adv max 31); PE side straddled the line today (not saturated) |
| T27 | relative-floor window | **OPEN; arming rec unchanged (NO)** | floor ~p70–75 on a thin tape, zero flat rows; challengers lost again — 7th loosening loss |
| T28 | `atmIv` frozen daily stamp | **OPEN** | 1 distinct (14th) |
| T3 | `iv_pair` | **OPEN (owner)** | 0% (18th session) |
| T23 | partial-bucket tolerance | **OPEN — no observation** | logs destroyed (§6.1); WARN/straddle count unknowable for 08-13 |
| T1 | `relativeVolumeMultiplier` | **REJECTED — carried** | vol-12k5: −₹5,549.88 (7th loss) |
| T7 | composite threshold | **REJECTED — carried** | composite-055 traded and lost −₹2,246.84 |
| NEW (08-04) | mid-session deploys | **PROPOSED — carried** | clean in-session; the 15:44 POST-close recreate is NEW-4's subject, not this row's |
| NEW (08-03) | minervini republish | **PROPOSED — carried** | 9th session (§6.4) |
| T10 | stale OPEN paper positions | **OWNER — chronic** | **18 OPEN unchanged** (6 manas + 12 minervini) |
| T8/T26 | latency | OPEN (data) | shadow p50 1:20.7 / p95 1:23.5 (n=45); emit 20.9–23.5 s — same structural class |
| T2 | `iv_rank` | carried, not open | NULL 1,015/1,015 |

## 8 Honesty caveats

- All log-dependent evidence for this session is gone (§6.1); every claim above that would
  normally cite `docker logs` is either DB-derived and labeled, or reported UNVERIFIABLE.
- Shadow P&L (brackets/structural/square-off, no time stop) and the funded book
  (per-strategy `max_bars`) are different exit models; today they agreed in direction
  (everything morning-PE lost) but not magnitude — the funded stops cut losses the shadow
  holds compounded.
- Regime is stamped from the CONTINUOUS session (o 24,431.60 → cc 24,353.35, eff **0.651 =
  trend-down**, −0.32%); the OFFICIAL bar (CAS close 24,395.85, +42.50 print at 15:29)
  reads eff 0.297 = mixed. Second straddle of a cut boundary, opposite direction from
  08-12; doctrine (§3.33a) keeps the continuous stamp. NOT a G11 chop day.
- Read-only run: SELECTs, in-container health GETs. No restarts, deploys, writes, config
  changes, or republishes. Docs-only PR: this file + rollup rows + README §3.37.
