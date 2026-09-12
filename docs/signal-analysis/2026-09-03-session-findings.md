# Session findings — 2026-09-03 (data date)

Analysis date: 2026-09-03 (scheduled post-market agent, run ~15:45–16:40 IST — early slot again;
the 18:45+ evening chain had NOT yet run at analysis time, §8). Analyst: Claude (scheduled
`session-analysis post`). Data: `signal_rejections` rows **1,233** (bounds
`2026-09-03T09:15:00+05:30`…`15:40`; rows 09:19:13–14:58:01), signals fired **3** (all emitted,
all FILLED — no suppression, no governor refusal), paper trades **3 entries / 3 closes, net
−₹4,701.83**, shadow champion **30 closes, 14 net wins, −₹12,890.69** (§5.2).

Session character: **Thursday, BSE SENSEX weekly expiry day** (SENSEX26903 series expiring —
funded and shadow SENSEX legs were 0DTE) · gap-up open +83.5 pts (+0.35% vs 09-02's official
close; o 23,997.95) then a steady all-day slide · signal contract **`NFO:NIFTY26SEPFUT`**
(signal-row-confirmed) · regime **trend-down (continuous eff 0.646)** — G11's chop count stays 9
(§8) · 4th consecutive fully-automatic Kite login (boot catch-up path, §4b) · funded fires on
all 3 subs that entered — **all 3 took first losses → 3 of 5 sub-accounts frozen by ~12:52**
(§3.30 flag HIT, §5.1) · ⚠️ **the 09-01 log-corruption barrier ESCALATED: every `docker logs`
read of strategy-signal — `--since` AND `--tail` — now ends at 09-01 12:41 IST**, so every
log-derived check for today is UNKNOWABLE, not zero (§6.1).

## 1 Funnel numbers

| metric | value |
|---|---|
| rejections | 1,233 — **35 of 37** enabled scalpers emitted rejection rows; the missing pair (`scalp-hero-zero-nifty`/`-sensex-niftyoi`) **evaluated 17× each, every eval `composite-below-threshold`** (V053 denominator) — an outcome class that writes no rejection row, so coverage is genuinely FULL |
| eval outcomes | chart-gate-failed 1,998 · confluence-blocked 1,233 · composite-below-threshold 205 · **fired 3** · discipline-paused 0 |
| fired reconciliation (§3.36) | 3 fired = 3 emitted = **3 FILLED** (subs 1/2/3); zero risk-gate suppressions, zero governor refusals — cleanest reconciliation shape |
| coverage | **24 of 24** 15-min buckets populated 09:15–14:45 (thinnest: 11:30/11:45 at 20 rows each); rejections end 14:58 |
| boot health | boot 08:45:31 IST; reload 0/37-unresolved transient at 08:45:47 → **37/0/0 at 08:46:43** (~56 s); 15:37:01 reload (market-data recreate) clean 37/0/0 |
| §3.30 freeze telemetry | 3 OPENED events (sub 1 @11:13, sub 2 @12:19, sub 3 @12:22); day PnL sub 1 −₹3,246.68 · sub 2 −₹866.29 · sub 3 −₹588.86 — **all three first-loss → frozen for the day; ≥3-of-5-before-14:30 FLAG HIT (first time since 07-31)**. `discipline-paused` 0 all day (subs 4/5 never entered, all-5 never froze; rejections flowed to 14:58) |

## 2 Rail findings

- **volume-floor first-block 736/1,233 (59.7%)** — banded and honest: **36 distinct thresholds**,
  10,530–42,510, zero flat; `relative-volume-floor` armed **37/37** (§3.14 registry check clean);
  all-fails avg operand 13,036 vs avg threshold 24,580.
- **`strike-pick`: 193 fails, ALL SENSEX-rooted (7 slugs), ZERO NIFTY** — the §3.27
  chain-proximity shape on the BSE weekly **day-of** (series: 07-23 day-of 390 · 08-06 day-of 0 —
  the outlier · today 193). The fresh-roll/expiry premium-band mechanism, exactly per the
  amended claim.
- **confluence-composite all-fails split (§3.39): 192 `60m bias opposes the side`
  (composite 0.3723–0.7402) + 610 score-shortfall aggregates (33 distinct values
  0.2128–0.5931).** First-block share tiny (2 rows). **Sole-blocker veto set: EMPTY today** —
  every vetoed row also failed something else (§5.3, NEW-9 day 7: 0 refused).
- First-block tail: time-window 242 · rsi-band 90 · option-side-constraint 32 ·
  time-of-day-preference 28 · two-candle 18 · divergence-vol-gate 18 · volume-pump 18 ·
  pct-price-move 18 · oi-cross-required 14 · supertrend-15m 9 · call-put-delta-filter 4 ·
  max-oi-sr-gate 3 · confluence-composite 2 · strike-pick 1 (15 distinct rails).

## 3 Composite + dots

- **OI bloc fully LIVE**: quadrants NEUTRAL **0/931** (SHORT_BUILDUP 429 · LONG_BUILDUP 404 ·
  LONG_UNWINDING 74 · SHORT_COVERING 24), spurt NULL 0, basis LIVE 931/931. futures_oi capture
  25,668 snaps / **372 of 375 minutes** (missing: 09:15 capture-start + isolated 11:48 + 12:29).
- **Composite passes 153 of 931 scored (16.4%) — CE 24 (max 0.7402) / PE 129 (max 0.7647)**; the
  three FIRED evals scored 1.0000 / 0.9192 / 0.9556 (all PE).
- Dot support (n=931 unless noted): `iv_rank` 0% (withheld, standing) · `iv_pair` 0% (**33rd**
  — T3, owner) · oi_spurt 1.3% · breadth 18.5% (side-saturated, below) · vix 20.6% · basis
  20.6% · volume 20.9% · trending_cross 33.2% · iv_slope 46.4% (n=138) · underlying_oi 49.9% ·
  rsi 53.5% · sentiment_slope 53.9% · futures_oi 54.5% · vwap 61.5% · sentiment 71.9% · psar
  73.4% · drastic_oi 90.1% · vwma 90.3% · **`iv_abs_band` 100% (n=138)** · supertrend 100%
  (free). `premium_skew` absent today (no straddle-path rows).
- **`iv_abs_band` flipped 0% → 100% on a stamp change — the §3.22 coin-flip mechanism on
  display**: the fresh atmIv stamp 0.104924 (09-02's 16:00 write, mechanism recovered after the
  09-01 outage gap) sits INSIDE the 0.10–0.12 band, where the 2-day-old 0.099408 sat just
  outside. One number decides the dot for the whole session (T28 standing).
- **§3.28 breadth (T30): CE 0/192 with session advances max EXACTLY 32 against `>32` — the
  third session where the operand's max lands precisely ON the strictly-greater line.** PE
  172/739 (23.3%, declines 15–35 crossing intermittently) — partial, not the full 08-11
  saturation shape.

## 4 Data health

| field | today | verdict |
|---|---|---|
| `ivRank` | NULL 1,233/1,233 | dead-data, standing (since 07-02) |
| `dowUp` | NULL 1,233/1,233 | by design (un-armed) |
| `fiiLongPct` | live on all 931 contextful rows (9.97, daily stamp) | healthy |
| `atmIv` | 1 distinct (**0.104924 — FRESH, 09-02's EOD write**) | frozen daily stamp, correct mechanism (G12/T28); the 09-01 gap healed itself at the next 16:00 write |
| vix | 13 distinct | alive |
| misaligned 1m candles (§3.15) | **0 rows** | clean — 13th consecutive |
| §3.17 canary | **UNKNOWABLE** (§6.1 — strategy-signal log unreadable; no DB fallback per §3.37) | never report as "0 WARNs" |
| signal-future capture | full live session; futures_oi 372/375 minutes | healthy |
| options chain capture | 1,162,216 snaps, last 15:33 | healthy, full session |
| per-contract tick/bar divergence | **15 `data canary RED` events, 9 distinct option contracts** | NEW-15, §6.2 |
| dot-health endpoint | breadth/oi/vix/oi_spurt_price alive; fii + iv_abs_band `frozen BY DESIGN (EOD daily operand)`; iv_rank + dow dead (standing) | clean |

## 4b BOOT WINDOW (§3.41) — CLEAN; 4th consecutive fully-automatic login (boot catch-up path)

All IST. **Circuit-breaker transitions in the boot window: ZERO — and ZERO in market-data's
entire snapshot (to 15:31). Capture minutes lost overlapping the window: ZERO** (session
CONNECTED 29 min before the open; futures_oi's missing minutes 09:15/11:48/12:29 are all outside
the window).

- **08:45:31** — containers start (7th consecutive overnight host downtime; `RestartCount=0`).
  Boot AFTER the 08:05 cron slot → **boot catch-up** carried the login again.
- **08:45:43** — `kite session restore: persisted token from 2026-09-02 … expired … NOT
  resumed` (#1520 refusing the dead token, 4th consecutive morning).
- **08:45:49** — boot catch-up arms ("inside the window — will attempt in 20s").
- **08:46:09** — attempt fires: authorize hop 1 → 302 same-origin, `request_token` at hop 2 →
  **`kite session status -> CONNECTED` 08:46:09.77; session established 08:46:10; ticker
  connected the same second** (~39 s boot-to-connected — fastest yet).
- **08:45:47 → 08:46:43** — engine reload transient 0/37-unresolved heals to **37/0/0** (~56 s).
- Benign, for the record: a pre-login burst of `gap fetch failed … 1d — serving cached data
  stale: no live Kite session` (swing catch-up's equity reads racing the login by ~20 s) — all
  fail-soft, all served cached; the catch-up completed DONE at 08:45/08:46.

## 5 Shadow outcomes + counterfactuals

### 5.1 Funded book — 3 fires, 3 fills, all PE, net −₹4,701.83; first TRAILING_STOP close since 08-19

| time | leg | sub | outcome |
|---|---|---|---|
| 11:12 fired | SENSEX2690377000PE (0DTE) | 1, qty 40 | filled 11:13:19 → **TRAILING_STOP 11:31 −₹3,246.68** |
| 12:18 fired | SENSEX2690376900PE (0DTE) | 2, qty 40 | filled 12:19:21 → **TIME_STOP 12:49 −₹866.29** |
| 12:21 fired | NIFTY2690824150PE | 3, qty 65 | filled 12:22:19 → **TIME_STOP 12:52 −₹588.86** |

- §3.36 reconciliation exact: 3 = 3 = 3. The two TIME_STOPs are entry+30 min to the second.
- §3.29 fired-vocabulary delta: **TIME_STOP +2, TRAILING_STOP +1** (5th trailing-stop close
  since 07-01). Never-fired set unchanged: `take_profit premium_pct` (35 armed, 0 closes since
  07-01 — but see §5.2: the SHADOW book hit TP twice today on same-family legs, so the bracket
  is reachable; the funded book's trailing/time stops fire first = class (c) shadowed) ·
  `signal_exit` (37, 0) · `square_off` (2, 0) · tag `oi-confluence-exit`→CONFLUENCE_FLIP (8, 0).
  INDETERMINATE standing: the two `atr_multiple` rows; `stop_loss` premium_pct/percent bases
  (STOP_LOSS 1 fire not attributable).
- All 3 fills carry `margin_snapshot` (populated, `margin_pct 0.00` — standing N23-A shape).
  §3.34 heat grep UNKNOWABLE (§6.1); the populated snapshots are the weak DB evaluability proxy
  and they are present on 3/3.
- §3.40 settle-reference: all 3 closed at market normally — no stuck-position shape.
- **All three subs that entered took their first loss → per §12.7 each froze for the day
  (§3.30 FLAG: 3 of 5 stopped opening by ~12:52, well before 14:30 — first flag since
  07-31).** Subs 4/5 stayed available; no further composite-passing fire arrived (fired 0 after
  12:21), so the freeze was never the binding constraint on a would-be entry today.

### 5.2 Shadow book — 30 closes, 14 net wins, −₹12,890.69; two shadow TAKE_PROFITs

**Champion: 30 opened+closed (0 stranded) → 8 deduped `(bar, leg, entry)` clusters on 7 bar
times.** The day in three acts: the 09:45 CE pair (gap-up fade trapped the longs —
`NIFTY2690823850CE` ×6 −₹23,584.67 + `SENSEX2690376600CE` ×6 −₹12,687.87 = **−₹36,272.54**);
the 11:03–11:06 PE wave riding the slide (**+₹22,533.26** across 4 clusters, incl. two shadow
**TAKE_PROFIT** closes — `SENSEX2690377000PE` clusters +₹13,103.20 and +₹2,369.83); the 12:21
tail (−₹534.09). All-time champion **−₹401,657.69** (901 closes, 299 net wins). Entry latency
p50 **1:21.1** / p95 1:26.0 (n=33) — first reading above the 1:18–1:20 structural band; watch,
n small.

**Funded-vs-shadow exit contrast (§3.24 asset, sharpest instance yet):** the funded book
entered `SENSEX2690377000PE` at 11:13 and its trailing stop cut it at 11:31 for **−₹3,246.68**;
the shadow book's 11:06 clusters on the SAME strike rode the bracket to **TAKE_PROFIT
+₹13,103.20**. Same tape, same instrument — the exit model, not the entry gate, was the day's
dominant P&L term for the funded book (T29's class). Single observation, 0DTE expiry premium;
logged for the exit-band track, not a proposal.

**Challenger-only class: 3 observations, 3 WINS, +₹5,330.15** (47th–49th measured loosenings —
first all-win challenger day): vol-off 11:03 `SENSEX2690377000PE` @310.00 **TAKE_PROFIT
+₹2,369.83** · vol-12k5 11:06 same strike @333.85 **TAKE_PROFIT +₹2,854.25** · composite-055
13:27 `NIFTY2690824150PE` SQUARE_OFF +₹106.07. **Loosening ledger 46/38/8 → 49 measured / 38
losses / 11 wins.** All-time: composite-055 **−₹19,108.19** · vol-12k5 **−₹47,931.26** ·
vol-off **−₹78,161.41** — one green expiry-slide day does not move the REJECTED statuses.

**Per-rail counterfactual P&L (owner directive 08-20), all-time champion NET:** volume-floor
509 / **−₹275,073.97** (took ~−₹12.4k of today) · rsi-band 109 / −₹78,355.28 (count +4, net
unchanged — NULL-pnl STALE-class rows) · call-put-delta-filter 7 / −₹11,149.59 ·
morning-opening-formation 4 / −₹9,629.13 · two-candle 38 / −₹8,818.79 · max-oi-sr-gate 9 /
−₹6,998.85 · divergence-vol-gate 37 / −₹6,375.60 · hero-zero 20 / −₹3,153.53 · volume-pump 37 /
−₹3,129.73 · **`confluence-composite` 20 / +₹7,148.76 (unchanged)** · pct-price-move 53 /
+₹2,263.88 · oi-cross-required 29 / +₹1,155.04. **Root split FLIPPED today: SENSEX
+₹160.79/trade (14) vs NIFTY −₹946.36 (16)** — first positive SENSEX day after 4 consecutive
same-direction days; all-time SENSEX −₹188.29 (407) vs NIFTY −₹667.40 (487). Still not
actionable.

### 5.3 §4.2 counterfactuals — the 60m-bias-vetoed set (day 7, NEW-9): EMPTY

The sole-blocker veto set returned **0 rows** — all 192 veto fails today co-occurred with at
least one other failing rail, so no leg is attributable to the veto alone. Tally unchanged:
**15 losers refused (d1–3, d5, d6) vs ~6 winners refused (d4)**; keep accumulating.

## 6 New data points / anomalies

### 6.1 Log-corruption barrier ESCALATED — strategy-signal's docker log now ends at 09-01 12:41 IST for EVERY read mode (3rd day; tail workaround DEAD)

On 09-02 the barrier killed `--since` but `--tail N` still read the day (1,908 lines). Today
**`docker logs` returns 4,858 lines total and the LAST line is 09-01 07:11:57Z (12:41 IST) —
`--tail 4000`, `--tail 100000` and the bare read all end there.** The 15:36 IST deploy's
snapshot (`log-snapshots/2026-09-03/strategy-signal.log`) is byte-identical to that read (4,858
lines, same last line) — the snapshot process worked; the source itself is unreadable past the
barrier. Consequences per §3.37, for THIS file: §3.17 canary WARNs/straddles **UNKNOWABLE**
(not 0) · §3.34 heat grep UNKNOWABLE (margin snapshots 3/3 populated = weak proxy OK) · §3.36
suppression lines unneeded (fired reconciles 3=3=3) · H31 day-context FAILED count UNKNOWABLE
(trajectory holds at 5 consecutive clean, today unmeasured) · telegram/notifier state
UNKNOWABLE from strategy-signal (market-data side: 0 ntfy failures) · swing catch-up refusal
detail UNKNOWABLE (DB: both batches DONE, 0 entries/0 exits — books still capacity-bound,
NEW-8) · today's boot line UNKNOWABLE from logs (DB fallback used: `engine_reloads` 37/0/0).
**PROPOSAL (owner/architect, post-close): recreate `ay-strategy-signal-service` to clear the
barrier** — every further session compounds the blind spot; this run is read-only and did not
touch it. market-data's log does NOT have the barrier.

### 6.2 NEW-15: 15 per-contract `data canary RED` events — tick-agg bar-closing went sparse on 9 option contracts; REST re-fetch healed the DB underneath

Between 09:30 and ~10:45 IST (plus stragglers), market-data's DataHealthCanary fired 15 REDs of
the shape `ticks flowing but no 1m bar closed for Ns (30–62 ticks since the last close)` on
**9 distinct contracts: 6 BFO SENSEX 0DTE strikes (75600/75800/77700/77800 CE+PE) + 3 NFO
NIFTY strikes (23450PE/24450CE/24500CE+PE)**, gaps 279 s to 4,247 s, **zero recovery lines**.
The DB tells the other half: spot-check `SENSEX2690375800CE` 09:40–10:20 holds 40/40 minutes —
**34 `source='KITE'` + 6 `TICK_AGG`** — i.e. the live tick-agg builder closed almost no bars on
those strikes while the cache-first 10-min-tail REST re-fetch back-filled them broker-official.
So: capture INTEGRITY held (bars present, broker-corrected, rails read the corrected 3m
rollup), but the LIVE tick→bar path was genuinely not closing bars on those contracts —
distinct from the 09-01 §6.6 two-strike instance, which was attributed to network degradation.
**Today's network was clean (0 breaker transitions, ticker never disconnected), which weakens
the 09-01 network attribution and makes this look structural on pinned/illiquid expiry-day
strikes (ticks arriving without trades?).** Funded legs were unaffected (all 3 settled at
market). Watch as NEW-15; if it recurs on a NON-expiry day, escalate to a ledger row —
mechanism question: what tick shape leaves the CandleBuilder unable to close a bar while the
canary counts 30–60 ticks?

### 6.3 NEW-14 CLOSED — #1567/#1569 verified live at the first 15:45 pass

market-data was recreated 15:36 IST (the #1569 deploy; snapshot taken to the conventional path
first — process followed). At **15:49:54** `EodBackfillJob` logged `EOD backfill pass covered
109 instruments` with **ZERO `invalid segment` lines and ZERO breaker transitions** (vs 09-02's
5× 400s opening the breaker at 15:45:01). The residual question — does BFO option 1d return
bars under `continuous=0` — is answered YES: **441 BFO 1d bars across 47 symbols,
`source='KITE'`, fetched after 15:40 today.** Watch closed.

### 6.4 09-02 evening chain (inherited watches) — ALL CLEAN

Last night's chain ran in full: BHAVCOPY 8,484 rows (18:44) · MANAS_SCREEN 2,294 ·
MINERVINI_SCREEN 1,807 · **MARKET_CONTEXT_DAY SUCCESS 18:48 — the 09-02 row exists** (only the
09-01 row remains permanently missing) · DATA_QUALITY 16 · EQUITY_BREADTH · insights ·
**BHAVCOPY_CLOSE 217 rows SUCCESS 18:57** (09-01's YELLOW watch — clean) · EVENING_CHAIN canary
DONE 18:58 · MINERVINI_PLANE_DIVERGENCE DONE. This morning: INGEST_COVERAGE DONE 08:45,
INSTRUMENT_SYNC 59,450 rows 09:04, OPTIONS_SNAPSHOT_CAPTURE canary DONE 09:18.

### 6.5 Mechanical pre-checks

- `tools/ledger-consistency-check.py`: **12 REVIEW lines — the identical standing set** of
  08-26…09-02 (7×[A] chip open/closed keyword pairs, 5×[B] pickup-vs-DONE keyword class). No
  edits made; ledger consistent modulo the standing set.
- `tools/published-config-drift.py`: **69 published — 69 matched (45 clean, 24 drifted = the
  standing #1075 disabled-scalper drafts), 0 DB-only, 0 YAML-only.** Nothing republished by
  this run.

## 7 Tuning candidates

Ledger §0 group G is the authoritative status; nothing applied by this run.

| # | knob | status | today's evidence |
|---|---|---|---|
| NEW-15 (09-03) | per-contract tick-agg bar-closing sparse on expiry strikes (15 canary REDs, 9 contracts; DB healed by REST tail re-fetch) | **NEW — WATCH**; recurrence on a non-expiry day escalates to a ledger row; weakens 09-01's network attribution of the same shape | §6.2 |
| log barrier (09-01, escalated) | strategy-signal docker log unreadable past 09-01 12:41 IST — ALL read modes | **PROPOSAL: post-close container recreate (owner/architect)**; every log-derived check is dark until then | §6.1 |
| NEW-14 (09-02) | post-close continuous-data 400 burst | ✅ **CLOSED — verified live**: 15:45 pass clean, 441 BFO 1d bars landed under `continuous=0` | §6.3 |
| NEW-13 (09-01) | recurring host outbound-network death | **OBSERVATION (owner/ops) — carried**; today clean (7th consecutive overnight downtime, in-session network clean, 0 breaker transitions) | §4b |
| NEW-9 (08-26) | 60m-bias veto | **OPEN — day 7: sole-blocker set EMPTY (0 refused)**; tally stands 15 losers vs ~6 winners | §5.3 |
| NEW-6 (08-19, reopened 08-31) | unpaired opening-bucket canary WARN ~21–22 lots | **UNKNOWABLE today (§6.1)** — neither confirmed nor cleared; carried | §6.1 |
| NEW-10 (08-27) | risk-limit base = current equity | **OBSERVATION (owner) — carried**; no trip (day −₹4,701.83 vs ~3% limit) | — |
| watch | `strike-pick` chain-proximity | **WATCH — BSE weekly day-of saturated: 193 fails, all 7 SENSEX slugs, 0 NIFTY** (day-of series now 390 / 0 / 193) | §2 |
| NEW (08-04) | mid-session deploys | **PROPOSED — carried**; today's deploy correctly waited for 15:36 | none |
| NEW-3 (08-12) | `daily_profit_target` 1.5% | **OBSERVATION (owner) — carried** | no trip |
| NEW-1 (08-05) | paper heat-cap margin timeout | **PROPOSED — carried**; heat grep unknowable, margin snapshots 3/3 populated | §5.1 |
| T30 | `breadth` dot `>32` | **OPEN — 3rd session with advances max EXACTLY 32 vs strictly-greater**; CE 0/192, PE 172/739 partial | §3 |
| T29 | exit model dominates entry gate | **OPEN (exit-band track)** — sharpest instance yet: funded trailing stop −₹3,247 vs shadow TP +₹13,103 on the SAME strike | §5.2 |
| T27 | relative-floor window | **OPEN; arming rec unchanged (NO)** | binding 59.7%, banded 36 thresholds; loosening ledger **49/38/11** — today added 3 wins (expiry slide) |
| T28 | `atmIv` frozen daily stamp | **OPEN** | stamp FRESH again (0.104924); `iv_abs_band` flipped 0%→100% on the one number — the coin-flip face |
| T3 | `iv_pair` | **OPEN (owner)** | 0% (33rd session) |
| T23 | partial-bucket tolerance | **OPEN** | unknowable today (§6.1) |
| T1 / T7 | `relativeVolumeMultiplier` / composite threshold | **REJECTED — carried** | challenger-only 3/3 wins today changes no all-time sign (all three books deep negative) |
| NEW-8 (08-24) | swing governor watch | **STANDING — 9th measurement**: both catch-up batches DONE, 0 entries (books capacity-bound 4th session; refusal detail unknowable §6.1) | §6.1 |
| T8/T26 | latency | OPEN (data) — p50 1:21.1 first reading above the 1:18–1:20 band (n=33, watch) | §5.2 |
| T2 | `iv_rank` | carried, not open | NULL 1,233/1,233 |

## 8 Honesty caveats

- **Regime: trend-down, from the CONTINUOUS session** (o 23,997.95 → continuous freeze
  23,904.10 pinned 15:15–15:27; continuous range 23,877.55–24,022.85; net −0.39% on 0.61%,
  **eff 0.646 = trend**). Official CAS print 15:28 **−30.65** → close 23,873.45 (official read
  −0.52%/0.63%, eff 0.819 — same label). **G11's chop count stays 9.**
- **This run executed ~15:45–16:40 IST, before the evening chain**: today's bhavcopy EOD,
  screens, settles, market-context write and canaries had not run yet — tomorrow's run inherits
  them. (Yesterday's chain is verified clean in §6.4.)
- **Every strategy-signal log-derived number for today is UNKNOWABLE (§6.1)** — absence of log
  evidence is not evidence of a quiet session. DB fallbacks used where they exist
  (`engine_reloads`, eval buckets, margin snapshots, `swing_catchup_runs`).
- Shadow figures are fan-out counts (§3.24); deduped clusters in §5.2. The funded-vs-shadow
  TP contrast is one observation on a 0DTE leg, not a proposal.
- SENSEX funded/shadow legs were 0DTE (BSE weekly expiry) — premium behaviour is expiry-day
  regime; per §3.19 the session is REGIME-class for SENSEX-family tuning evidence.
- Read-only run: SELECTs, log/snapshot reads, `docker inspect`, in-container health GETs. No
  restarts, deploys, writes, config changes, republishes. Docs-only PR: this file + rollup rows.
