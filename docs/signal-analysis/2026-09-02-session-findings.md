# Session findings — 2026-09-02 (data date)

Analysis date: 2026-09-02 (scheduled post-market agent, run ~15:54–16:40 IST — earlier slot than
usual; the 18:45+ evening chain had NOT yet run at analysis time, so evening-batch checks are
explicitly out of scope, §8). Analyst: Claude (scheduled `session-analysis post`). Data:
`signal_rejections` rows **1,298** (bounds `2026-09-02T09:15:00+05:30`…`15:40`; rows
09:19:07–14:58:00), signals fired **3** (2 filled + 1 governor-refused, §5.1), paper trades
**2 entries / 2 closes, net +₹190.74**, shadow champion **62 closes, 0 net wins, −₹92,225.57 —
the book's WORST net day on record** (§5.2).

Session character: **Wednesday, no expiry on either exchange** · **gap-down open** (−256 pts /
−1.06% vs 09-01's official close; o 23,858.00) then an all-day drift-up recovery inside a narrow
range · signal contract **`NFO:NIFTY26SEPFUT`** (log- and signal-row-confirmed) · regime **chop
(continuous eff 0.203)** — G11's 9th chop observation (§8) · **FIRST FUNDED FIRES since 08-28**,
both PE, both closed TIME_STOP · 3rd consecutive fully-automatic Kite login (boot catch-up path,
§4b) · full-session coverage 37/37 scalpers.

## 1 Funnel numbers

| metric | value |
|---|---|
| rejections | 1,298 — **37 of 37** enabled scalpers (FULL coverage; first full session at fleet size 37) |
| eval outcomes | chart-gate-failed 1,882 · confluence-blocked 1,298 · composite-below-threshold 262 · **fired 3** · discipline-paused 0 |
| fired reconciliation (§3.36) | 3 fired = 3 emitted + 0 risk-gate-suppressed; **of the 3 emitted, 2 FILLED and 1 was governor-refused at the paper-open step** (sub-account allocation, §5.1 — a class one step downstream of §3.36's) |
| coverage | 23 of 24 15-min buckets populated 09:15–14:45 (10:15 bucket = 1 row — chart-gate quiet stretch, `confluence-blocked` 0 while `chart-gate-failed` advanced 19/bucket, benign); rejections end 14:58, eval buckets write **explicit zeros 15:00→15:57** = every strategy out-of-window (the four-normal-states class), chart counter advanced through 14:57 |
| boot health | boot 08:35:53 IST; reload 0/37-unresolved transient at 08:36:15 → **37/0/0 at 08:37:15** (~60 s, second-fastest healed) |
| §3.30 freeze telemetry | **2 OPENED events (sub 1 and sub 2, both 11:37)**; no further entries; `discipline-paused` 0 all day (all-5-frozen never occurred) |

## 2 Rail findings

- **volume-floor first-block 787/1,298 (60.6%)** — banded and honest: **37 distinct thresholds**,
  15,941.25–72,003.75, zero flat; `relative-volume-floor` armed **37/37** (§3.14 registry check
  clean); all-fails avg operand 12,674 vs avg threshold 25,182.
- **`strike-pick`: ZERO fails on either root** — the §3.27 "Wednesdays are always clean" shape,
  4th consecutive clean Wednesday.
- **confluence-composite all-fails split (§3.39): 626 `60m bias opposes the side`
  (composite 0.2020–0.7447) + 251 score-shortfall aggregates (15 distinct values 0.3676–0.5851).**
  On the gap-down morning the 60m bias sat down while the tape drifted up, so the veto's refusals
  were CE-heavy all day. Sole-blocker veto set: §5.3 — day 6, 2 refused, both losers.
- First-block tail: time-window 276 · time-of-day-preference 36 · rsi-band 35 · two-candle 24 ·
  volume-pump 24 · pct-price-move 24 · divergence-vol-gate 22 · confluence-composite 19 ·
  option-side-constraint 19 · oi-cross-required 14 · supertrend-15m 8 · call-put-delta-filter 4 ·
  hero-zero 4 · directional-vix-gate 2 (15 distinct rails).

## 3 Composite + dots

- **OI bloc fully LIVE** (non-expiry Wednesday, correctly): quadrants NEUTRAL **0/967**, spurt
  NULL 0 on contextful rows, basis LIVE 967/967. futures_oi capture 25,806 snaps / **373 of 375
  minutes** (missing: 09:15 capture-start + one isolated 14:01 minute).
- **Composite passes 170 of 967 scored (17.6%) — two-sided: CE 80 (max 0.7447 in rejections),
  PE 90 (max 0.6649)**; the three FIRED evals scored 0.7901/0.7901/0.8288 (PE).
- Dot support (n=967 unless noted): `iv_abs_band` 0% (n=130, **10th day** — atmIv stamp
  0.099408 below the 10–12 band) · `iv_rank` 0% (withheld, standing) · `iv_pair` 0% (**32nd** —
  T3, owner) · oi_spurt 1.3% · volume 18.6% · vwap 18.8% · premium_skew 22.2% (n=18) ·
  trending_cross 26.6% · breadth 35.3% (side-saturated, below) · vix 35.3% · iv_slope 36.9%
  (n=130) · rsi 48.3% · futures_oi 56.7% · underlying_oi 59.9% · basis 64.7% · sentiment_slope
  69.6% · sentiment 75.2% · drastic_oi 80.1% · psar 87.0% · vwma 95.2% · supertrend 100% (free).
- **§3.28 breadth (T30) — the 08-11 side-saturation shape again: CE 0/626, PE 341/341 (100%).**
  Advances ranged 2–17 (never >32), declines 33–48 (always >32) — a free +1.0 for every PE row
  and dead weight for every CE row, all session. `vix` reads the identical 341/967 = exactly the
  PE row count — both dots were per-side step functions today.

## 4 Data health

| field | today | verdict |
|---|---|---|
| `ivRank` | NULL 1,298/1,298 | dead-data, standing (since 07-02) |
| `dowUp` | NULL 1,298/1,298 | by design (un-armed) |
| `fiiLongPct` | live on all 967 contextful rows | healthy |
| `atmIv` | 1 distinct (**0.099408 — the SAME stamp as 09-01**) | frozen daily stamp, correct mechanism (G12/T28) — but **2 days old today**: no `iv_daily_summary` row exists for 09-01 (its 16:00 write fell inside the 09-01 host outage), so today read the 08-31 stamp again |
| vix | 16 distinct | alive |
| misaligned 1m candles (§3.15) | **0 rows** | clean — 12th consecutive |
| §3.17 canary | **1 WARN + 1 straddle** (§6.2) | opening-bucket unpaired −1,430 (22 lots, 0.56%) + one textbook benign straddle ±1,300 at 11:45/11:48 |
| signal-future capture | full live session; futures_oi 373/375 minutes | healthy |
| options chain capture | 1,141,536 snaps, last 15:32 | healthy, full session |

## 4b BOOT WINDOW (§3.41) — CLEAN; 3rd consecutive fully-automatic login (boot catch-up path)

All IST. **Circuit-breaker transitions in the boot window: ZERO. Capture minutes lost overlapping
the window: ZERO** (session valid 38 min before the open; the two missing futures_oi minutes are
09:15 capture-start and 14:01, neither in the window).

- **08:35:53** — containers start (6th consecutive overnight host downtime; `RestartCount=0`).
  Boot is AFTER the 08:05 cron slot, so the cron never fired and the **boot catch-up** path
  carried the login — the complementary path to 09-01's cron-slot login.
- **08:36:08** — `kite session restore: persisted token from 2026-09-01 … expired … NOT resumed`
  (#1520 refusing a dead token, 3rd consecutive morning).
- **08:36:19** — boot catch-up arms: "inside the window — will attempt in 20s".
- **08:36:39** — attempt fires → `kite session status -> CONNECTED`; **08:36:40 session
  established** (~47 s boot-to-connected; ticker connected same second).
- **08:36:15 → 08:37:15** — engine reload transient 0/37-unresolved heals to **37/0/0** in ~60 s.
- Nothing else in the window. (One pre-open oddity for the record, NOT in the boot window:
  the breaker's failure ring later showed a single 08:41:24 `400 "invalid from date"` — one
  boot-time historical call, no state transition, self-cleared.)

## 5 Shadow outcomes + counterfactuals

### 5.1 Funded book — first fires since 08-28: 2 filled TIME_STOPs net +₹190.74; 1 governor-refused fill

Three fired evals, all PE (§3.36 reconciliation 3 = 3 emitted + 0 suppressed):

| time | slug | leg | outcome |
|---|---|---|---|
| 11:36 | scalp-connect-the-dots-nifty-pe | NIFTY2690824100PE | filled 11:37:19 sub 2, qty 65 → **TIME_STOP 12:07 +₹301.85** |
| 11:36 | scalp-connect-the-dots-sensex-niftyoi-pe | SENSEX2690376800PE | filled 11:37:18 sub 1, qty 20 → **TIME_STOP 12:07 −₹111.11** |
| 12:03 | scalp-golden-crossover-nifty-pe | NIFTY2690824100PE | emitted (composite 0.8288) but **paper open refused: `entry blocked by risk governor (sub_account_allocation) — projected 18931.25 would cross sub-account 2 allocation`**, then `taken signal 280 released TAKEN->EXPIRED` — the anchor-release fix working exactly as designed (no phantom TAKEN anchor left behind) |

- The refusal is the **G14 convergence class live**: golden-crossover converged on the SAME
  `NIFTY2690824100PE` key connect-the-dots already held on sub 2 (₹18.4k of the ₹30k allocation);
  the projected average-in would have crossed it. Governor correct; no defect.
- Both fills carry `margin_snapshot` (populated, `margin_pct 0.00` — the standing N23-A shape:
  long-option BUY carries no SPAN, so the heat-cap gate evaluated and bound nothing). §3.34 heat
  grep: **0 failures** on a 2-fire day = the margin call succeeded on both entries.
- §3.40 settle-reference check: both legs closed at market normally; `paper settle refused`
  grep **0**. **NEW-12/H44's "verify on first funded fire" item is now exercised: both fills
  settled cleanly under the re-centred band.** N26 veto-audit remains unexercised.
- TIME_STOP at exactly entry+30 min (connect-the-dots `max_bars 10` × 3m) — §3.29 fired
  vocabulary delta today: **TIME_STOP +2** (funded book's first closes since 08-28).
- Sub-account telemetry (§3.30): entries subs 1 & 2 only, last 11:37; `discipline-paused` 0 all
  day, and rejections kept flowing to 14:58 — no fleet-wide freeze. (Sub 2's +₹301.85 sits right
  at the ~1%-of-₹30k profit-lock line and sub 1 took its first loss; whether each froze is not
  observable from `risk_audit` — no discipline rows are written — and no later fire tested it.)

### 5.2 Shadow book — worst net day on record: 62 closes, 0 net wins, −₹92,225.57

**Champion: 62 opened+closed (0 stranded), 0 net wins** → **21 deduped `(bar, leg, entry)`
clusters on ~11 bar times, EVERY cluster negative, both sides**: morning PE stops (09:24/09:48
−₹39,255 across 4 clusters), midday CE stops (10:33 −₹13,593), the 11:30 PE re-entry family
(−₹22,153), afternoon CE bleed (13:00–14:30, 8 clusters −₹13,255). Worst single cluster: 11:30
`SENSEX2690376800PE` ×6 −₹16,331.06. The gap-down-then-drift-up chop whipsawed both directions;
close reasons are STOP_LOSS / STRUCTURAL_STOP / SQUARE_OFF only — not one TP.

Displaces 08-28's −₹83,062.45 as the worst net day. All-time champion **−₹388,767.00**
(871 closes — 802 + 62 today + **7 STALE closes of 09-01's stranded rows, which arrived at
09:15:11 today with NULL pnl exactly as predicted** (count moved, net did not; excluded from
all attribution) — 285 net wins, unchanged today). Entry latency p50 1:17.8 / p95 1:22 —
in line with the 5-session 1:18–1:20 structural band.

**Funded-vs-shadow contrast worth one line:** the funded book's governors + veto let exactly 2
trades through on the day the unconstrained shadow book lost ₹92k on 62 — the funded gate's
whole stack (veto, rails, sub-caps) was worth ≈ +₹92k of avoided loss vs the composite-passing
population, on this one chop day.

**Challenger-only class: 4 observations, 0 wins, −₹3,687.08** (43rd–46th measured loosenings):
vol-12k5 13:27 pair (NIFTY2690823700CE −₹740.48, SENSEX2690376100CE −₹382.15), composite-055
14:09 pair (−₹1,416.79, −₹1,147.66 — the same legs as the §5.3 veto set). **Loosening ledger
42/34/8 → 46 measured / 38 losses / 8 wins.** All-time: composite-055 **−₹19,214.26** ·
vol-12k5 **−₹50,785.51** · vol-off **−₹80,531.24**. REJECTED statuses stand.

**Per-rail counterfactual P&L (owner directive 08-20), all-time champion NET:** volume-floor
478 / **−₹262,717.37** · rsi-band 105 / −₹78,355.28 · call-put-delta-filter 7 / −₹11,149.59 ·
morning-opening-formation 4 / −₹9,629.13 · two-candle 38 / −₹8,818.79 · max-oi-sr-gate 9 /
−₹6,998.85 · divergence-vol-gate 37 / −₹6,375.60 · volume-pump 37 / −₹3,129.73 ·
**`confluence-composite` 20 / +₹7,148.76 (bucket unchanged — none of today's 62 carried that
first-rail)** · pct-price-move 52 / +₹2,797.97 · oi-cross-required 29 / +₹1,155.04. Today's
−₹92k spread across the volume-floor (−₹78k of it) and rsi-band buckets. **Root split: SENSEX
−₹200.72/trade (393) vs NIFTY −₹657.92 (471)** — same direction 4th consecutive measured day,
but SENSEX gave back the 09-01 improvement (−₹65 → −₹201); still not actionable.

### 5.3 §4.2 counterfactuals — the 60m-bias-vetoed set (day 6, NEW-9): 2 refused, both losers

Sole-blocker veto set: **2 rows** — 14:09 CE pair, composite 0.6863
(`scalp-connect-the-dots-nifty` NIFTY2690823700CE @295.55 / `-sensex-niftyoi`
SENSEX2690376200CE @484.20). **Challenger-corroborated LOSSES**: composite-055 took exactly
these legs and closed −₹1,416.79 / −₹1,147.66 (structural stops). The champion book's own 14:09
clusters on the same legs lost −₹1,898.90 / −₹2,127.56.

**Veto ledger after 6 days: 15 losers refused (d1–3, d5, d6) vs ~6 winners refused (d4,
≈+₹48.5k).** Still two-sided on money; keep accumulating, no proposal either way.

## 6 New data points / anomalies

### 6.1 Log-corruption barrier PERSISTS from 09-01 — every `--since` read of strategy-signal is a silent zero (process trap, 2nd day)

The 09-01 §6.4 hard-shutdown corruption is still in strategy-signal's json log: a forward read
(`docker logs`, any `--since`) stops at the 09-01 12:41 IST barrier and **returns ZERO lines for
all of today while exiting cleanly** — this run's first grep pass read "0 canary WARNs, 0
telegram lines" and both were artifacts; the tail-read redo found 1 WARN + 1 straddle + 2
telegram failures. market-data's log does NOT have the barrier (forward reads reach 15:45).
**Rule for every later reader: until ay-strategy-signal-service is next recreated, any
`docker logs --since` count from it must be taken via `--tail N` + timestamp filter instead**
(today: `--tail 2000` covered the day; 1,908 lines). A `wc -l` of the `--since` read is the
cheap self-check — 0 total lines = barrier, not quiet.

### 6.2 §3.17 canary — 2nd consecutive session with the same unpaired opening-bucket shape (NEW-6)

09:19:41 IST, bucket 09:15: 3m 253,500 vs Σ1m 252,070, shortfall **−1,430 = 22 NIFTY lots,
0.56%**, UNPAIRED, no partner. 09-01's was −1,365 = 21 lots / 1.8% at the same bucket. Two
consecutive sessions, same bucket, same ~21–22-lot magnitude, no partner either day — this now
looks like a systematic small opening-auction/boot-baseline residue rather than a random event;
still absorbed by tolerance-class magnitudes and rails-safe (broker-corrected side). Keep NEW-6
open with this sharpened shape. The day's other event was a **textbook benign straddle**
(11:45/11:48, ±1,300, residue 0, suppressed and logged at INFO — G9 working).

### 6.3 Post-close kite-rest breaker OPEN at 15:45:01, cause NAMED by #1512: `invalid segment for continuous data`

`CLOSED → OPEN` 15:45:01 IST (failure rate 50% over 10), the ring naming **5× `400 Bad Request
"invalid segment for continuous data"` at 15:44:58–15:45:01**, then OPEN→HALF_OPEN 15:58.
Post-close — zero capture impact (chain ran to 15:32, futures_oi to 15:30). This is #1512's
diagnostics doing their job: something scheduled around 15:45 is issuing Kite continuous-data
historical calls with a segment Kite rejects, in a burst of ≥5. First observation with a named
cause; NEW-14 (watch/identify the caller — likely a post-close candle warm touching a
continuous-series symbol). In-session breaker transitions today: **zero**.

✅ **CALLER IDENTIFIED AND FIXED, 2026-09-03 — and the guess above was close but not right.** It is
not a candle *warm*: it is **`EodBackfillJob`**, whose cron is `0 45 15 * * MON-FRI`
(`EodBackfillJob.java:49`) — matching the 15:44:58–15:45:01 burst to the second. It prefetches 1d
for **every subscribed key** (`:69`), BFO SENSEX option contracts included, and
`LiveHistoricalCandleGateway`'s `useContinuous` predicate gated on instrument TYPE (CE/PE) and
interval (day) **but not the exchange** — so those fetches carried `continuous=1`, which Kite
refuses for the BSE derivatives segment. Five subscribed BFO contracts, five permanent 400s, half
a `COUNT_BASED` window of 10.

⚠️ **The obvious discriminator is the wrong one, and it would have failed silently.** `TokenInfo`
carries a `segment` and `BFO-OPT` reads like the natural gate — but `computed` live 2026-09-03,
**175,766** NFO option rows and **6,423** BFO ones carry an **EMPTY** segment, so gating on it
would have sent `continuous=0` across most of the NFO path that works today, with no failing test
to reveal it. The fix gates on `exchange`, which is always populated, as an **NFO allow-list**
rather than a BFO deny-list: only NFO is measured to serve continuous data (**1,289**
`source='KITE'` option 1d bars against **ZERO** for BFO), and a deny-list would hand any future
exchange the failing default.

Two PRs, deliberately separate: [#1567](https://github.com/prashantm912/artha-yantra-2/pull/1567)
stopped a permanent 400 being counted as an unavailable upstream (containment — a 400 can never
mean "the upstream is down"), and [#1569](https://github.com/prashantm912/artha-yantra-2/pull/1569)
stops the request being made at all. ⚠️ **Still unverified: whether BFO option 1d returns anything
under `continuous=0`** — that needs a live Kite call. The failure direction is safe either way
(today it returns nothing *and* poisons a shared breaker), but judge it at the next 15:45 pass
rather than assuming.

### 6.4 09-01 watch items — resolutions

- **Telegram/ntfy channels (09-01 §6.3):** healed. Today's whole-day telegram failure count:
  **2** (`getUpdates failed: I/O error`, 09:09:55 + 09:10:27 IST, transient) vs 09-01's
  every-33-s flood; zero ntfy failures in market-data's log. Watch closed.
- **BFO two-strike canary RED (09-01 §6.6):** did NOT recur — `data canary RED` grep 0 today on
  a clean network day. Leaves the 09-01 instance most consistent with early network degradation.
- **MARKET_CONTEXT_DAY (09-01 §6.2):** the 09-01 row is **confirmed permanently missing**
  (`market_context_days` has 08-28, 08-31, then nothing — no catch-up path exists). Today's
  08:44:59 INGEST_COVERAGE canary ran DONE; whether it paged on the gap is not recorded in the
  table. One-day gap stands; H31 §6.5 trajectory unaffected.
- **09-01 EVENING_CHAIN canary row stuck `CLAIMED`** (claimed 18:58:59 IST, never completed) —
  benign residue of the chaotic 09-01 evening; noted so a later census doesn't read it as live.
- **Stranded shadow rows (09-01 §5.2):** all 7 closed STALE at 09:15:11 today, NULL pnl,
  excluded from attribution — exactly as predicted.

### 6.5 H31 day-context — 5th consecutive zero-failure session, first FULL one since the fix window

`insight trust read day-context FAILED` count (tail-read, §6.1 caveat handled): **0** on a
complete 375-minute session — trajectory 89% → 18% → 0% → 0% → 0% (truncated) → **0 (full)**.
Owner's closing bar is 10 clean sessions (#1552); this is the strongest single observation yet.

### 6.6 Swing book capacity-bound 3rd consecutive session (NEW-8, 8th measurement)

The 08:36 catch-up (settling 09-01's screens) took **0 entries: 3 `pyramid_risk_cap` blocks**
(COMSYN, EBGNG, DIVGIITTS — manas 6.0% open-risk cap) **then minervini
`max_open_paper_positions` 12/12**. Governors normal; both books full.

### 6.7 Mechanical pre-checks

- `tools/ledger-consistency-check.py`: **12 REVIEW lines — the identical standing set** of
  08-26…09-01 (7×[A] chip open/closed keyword pairs, 5×[B] pickup-vs-DONE keyword class). No
  edits made; ledger consistent modulo the standing set.
- `tools/published-config-drift.py`: **69 published — 69 matched (45 clean, 24 drifted = the
  standing #1075 disabled-scalper drafts), 0 DB-only, 0 YAML-only.** Nothing republished by
  this run.

## 7 Tuning candidates

Ledger §0 group G is the authoritative status; nothing applied by this run.

| # | knob | status | today's evidence |
|---|---|---|---|
| NEW-14 (09-02) | post-close continuous-data 400 burst opens kite-rest breaker | ✅ **CAUSE FOUND AND FIXED (09-03)** — the caller is `EodBackfillJob` (`0 45 15 * * MON-FRI`), which prefetched 1d for every subscribed key incl. BFO options while `useContinuous` gated on type+interval but **not exchange**. Containment [#1567](https://github.com/prashantm912/artha-yantra-2/pull/1567) (breaker ignores a permanent 400) + caller fix [#1569](https://github.com/prashantm912/artha-yantra-2/pull/1569) (NFO allow-list, gated on `exchange` because `segment` is EMPTY on 182k option rows). Residual watch: confirm BFO 1d actually returns bars under `continuous=0` at the next 15:45 pass | §6.3 |
| NEW-13 (09-01) | recurring host outbound-network death | **OBSERVATION (owner/ops) — carried**; today clean (6th consecutive overnight downtime, but in-session network clean; telegram flood healed, §6.4) | §6.4 |
| NEW-9 (08-26) | 60m-bias veto | **OPEN — day 6: 2 refused, both challenger-corroborated LOSERS**; tally 15 losers vs ~6 winners | §5.3 |
| NEW-6 (08-19, reopened 08-31) | unexplained unpaired canary WARNs | **OPEN, shape SHARPENED — 2nd consecutive unpaired opening-bucket WARN at ~21–22 lots** (−1,430 / 0.56% today vs −1,365 / 1.8% on 09-01); reads systematic, small, rails-safe | §6.2 |
| NEW-12/H44 residual | first funded fire under the re-centred band | **EXERCISED — 2 fills, both settled cleanly (settle-refused 0, margin snapshots populated); N26 veto-audit still unexercised** | §5.1 |
| NEW-10 (08-27) | risk-limit base = current equity | **OBSERVATION (owner) — carried**; no trip (day +₹190.74) | — |
| watch | `strike-pick` chain-proximity | **WATCH — Wednesday clean (0 fails both roots, 4th consecutive clean Wednesday)** | §2 |
| NEW (08-04) | mid-session deploys | **PROPOSED — carried** | none today |
| NEW-3 (08-12) | `daily_profit_target` 1.5% | **OBSERVATION (owner) — carried** | no trip |
| NEW-1 (08-05) | paper heat-cap margin timeout | **PROPOSED — carried** | 2 entries, 0 heat failures — timeout not hit today |
| T30 | `breadth` dot `>32` | **OPEN — side-saturation face again (08-11 shape): CE 0/626, PE 341/341**; `vix` mirrored it exactly | §3 |
| T27 | relative-floor window | **OPEN; arming rec unchanged (NO)** | binding 60.6%, banded 37 thresholds; loosening ledger **46/38/8** — today added 4 losses |
| T28 | `atmIv` frozen daily stamp | **OPEN** | stamp 2 DAYS old (09-01's 16:00 write lost to the outage); `iv_abs_band` 0% 10th day | 
| T3 | `iv_pair` | **OPEN (owner)** | 0% (32nd session) |
| T23 | partial-bucket tolerance | **OPEN** | 1 WARN + 1 straddle (§6.2) |
| T1 / T7 | `relativeVolumeMultiplier` / composite threshold | **REJECTED — carried** | challenger-only today: 0 wins / 4 losses −₹3,687.08 |
| NEW-8 (08-24) | swing governor watch | **STANDING — 8th measurement, capacity-bound 3rd session** | §6.6 |
| T8/T26 | latency | OPEN (data) — p50 1:17.8 structural band | §5.2 |
| T2 | `iv_rank` | carried, not open | NULL 1,298/1,298 |

## 8 Honesty caveats

- **Regime: chop, from the CONTINUOUS session** (o 23,858.00 → continuous freeze 23,882.85
  pinned 15:15–15:28; continuous range 23,788.05–23,910.25; net +0.10% on 0.51%, **eff 0.203 =
  chop**). Official CAS print 15:29 **+31.60** → 23,914.45 (1d bar KITE, fetched 15:58; official
  read +0.24%/0.54%, eff 0.442 "mixed" — the continuous read governs, §3.33a). **G11's chop
  count 8 → 9** — G11's blocking observation class accrued again, and today it came WITH live
  funded TIME_STOP exits on a chop day (+₹301.85/−₹111.11), the exact evidence class G11 wants.
- **This run executed ~15:54–16:40 IST, before the evening chain**: bhavcopy EOD, screens,
  settles, the 18:49 market-context write and the bhavcopy-close canary (09-01's YELLOW watch)
  had not run yet and are NOT covered here — tomorrow's run inherits them.
- Every strategy-signal log count in this file comes from the §6.1 tail-read workaround, not
  `--since` (which reads zero through the 09-01 corruption barrier). The tail window covered the
  full day (first line 09-01 18:52 IST).
- Shadow figures are fan-out counts (§3.24); deduped clusters given in §5.2. The
  funded-vs-shadow ₹92k contrast is a same-day observation, not a projection.
- §5.3's corroboration reuses the challenger books' engine-exit fills; chain-path granularity
  2–3 min, no slippage/fees.
- Read-only run: SELECTs, log reads, `docker inspect`. No restarts, deploys, writes, config
  changes, republishes. Docs-only PR: this file + rollup rows.
