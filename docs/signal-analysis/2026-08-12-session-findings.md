# Session findings — 2026-08-12

## Scheduled verification: first complete cycle of #1333 two-phase swing schedule

**VERDICT: PASS — the two-phase mechanism worked end-to-end on its first real cycle.** The
2026-08-11 session was settled exits-only at 16:00, seeded by the morning catch-up (because
`entries_enabled = false`), re-run WITH entries at 08:35 IST on 2026-08-12, and the monotone OR
flipped `entries_enabled` to `t`. Zero entries were opened, but every one of the 12 would-enter
candidates was refused by an ADMISSION rail (risk cap / book governor), not by the forfeiture
defect #1333 fixed. The 2026-08-11 "entries permanently broken" conclusion is disproven by
this cycle.

Read-only run (scheduled task `verify-swing-entry-leg-20260812`); nothing restarted, published,
or written to the live DB.

### 1. Entry leg ran for session 2026-08-11 — PASS [computed]

`strategy.swing_batch_runs` (rendered `Asia/Kolkata`):

| batch | run_date | ran_at IST | candidates | would_enter | entries | exits | open_at_start | entries_enabled |
|---|---|---|---|---|---|---|---|---|
| manas-arora | 2026-08-10 | 08-10 20:05:30 | 98 | 9 | 0 | 0 | 6 | NULL |
| minervini | 2026-08-10 | 08-10 20:00:54 | 0 | 21 | 0 | 0 | 15 | NULL |
| manas-arora | 2026-08-11 | **08-12 08:35:34** | 103 | 4 | 0 | 0 | 6 | **t** |
| minervini | 2026-08-11 | **08-12 08:36:15** | 109 | 8 | 0 | 0 | 15 | **t** |

Both 2026-08-11 rows now read `entries_enabled = t` with plausible candidate counts (103/109).
`ran_at` shows 08-12 08:35 because `SwingBatchRunRepository.record` upserts on `(batch, run_date)` —
the morning entry run overwrote the 16:00 settle row's `ran_at`; the monotone OR preserved the flip
[computed from the row; upsert semantics sourced from #1333 background]. The 2026-08-10 rows carry
NULL `entries_enabled` — they predate the column being populated (pre-#1333-deploy runs) [assumed
from timing; harmless either way, NULL satisfies neither seed-skip nor DONE semantics incorrectly
since COALESCE(entries_enabled,true) treats NULL as entries-ran].

### 2. Catch-up state — PASS [computed]

`strategy.swing_catchup_runs`: both `manas-arora 2026-08-11` and `minervini 2026-08-11` reached
**DONE, attempts = 1**, claimed 08:34:59 / 08:35:36 IST, no reason. (The two 2026-07-17 ABANDONED
`NO_SCHEDULE_INTENT` rows are historical.) The seeding predicate — `WHERE NOT EXISTS (...
AND COALESCE(entries_enabled,true))` — did exactly what the pre-mistake verification said it would:
the `entries_enabled = f` settle rows made the session eligible and it was seeded, claimed, and run.

### 3. Logs — entry pass ran; admission rails refused everything [sourced, decisive lines quoted]

`docker logs ay-strategy-signal-service`, thread `swing-catchup-sched-1`, 03:04:59–03:06:20 UTC:

- manas-arora scanned entries for real: `"manas-arora swing: fresh entry for PANACHE would breach
  the open-risk cap — skipped"` — same for SAKAR, SHAILY, ARVIND, each followed by
  `"risk pyramid-cap manas-arora tripped ... blocked by the 6.0% portfolio open-risk cap"`.
- Batch summary: `"manas-arora swing batch: 2 strategies, 103 candidates, 0 entries, 0 exits,
  0 exit-skipped (would-enter 4, admitted 0, cap-exceedance 4)"`.
- minervini: `"entry pass skipped — the minervini book gate blocks entry at run start; 109 funnel
  candidate(s) not scanned"` — the 12/12 governor, which the task brief pins as CORRECT behavior.
  Its batch summary still tallies `would-enter 8, admitted 0, cap-exceedance 8` across the batch's
  4 strategies.
- Completion: `"swing catch-up: manas-arora caught up 2026-08-11 — 103 candidates, 0 entries,
  0 exits, 0 exit-skipped, 0 refusal(s)"` and the same for minervini (109 candidates).

No arming-unknown, screen-date-mismatch, market-open-deadline, or fresh-marker-table skip fired.
Many `candle response STALE ... visibility only` warnings during the run — data used unchanged,
not a refusal path [sourced].

### 4. Did an entry actually fire? No — and that is consistent, not a failure [computed]

`strategy.signals` for minervini/manas strategies in the last 3 h: **0 rows**. New
`strategy.paper_positions` opened since 08:00 IST: **0 rows**. Consistent with `admitted 0`:
minervini refused by the book governor (correct at 12/12), manas-arora's 4 candidates all refused
by the 6.0% portfolio open-risk cap (book already carries 6 open positions, `open_at_start = 6`).
So the brief's "manas-arora has capacity" held only at the SLOT rail — the RISK rail was already
saturated [computed]. **A real admitted entry has therefore still never been observed under the
new schedule**; the mechanism is proven up to admission, and the first admission remains to be
seen on a morning where the risk cap has headroom. Not a defect — but worth knowing what has and
hasn't been demonstrated.

### 5. `open_at_start` — PASS [computed]

The morning entry runs recorded real values: manas-arora **6**, minervini **15** (vs the known
`AdmissionProbe.empty()` → 0 defect on entries-disabled settles). The upsert means the visible
2026-08-11 rows now carry the morning probe's values; whether the 16:00 settle wrote 0 first is
no longer observable in the table [computed; prior-value unobservability noted].

### Open doubts

- minervini `would_enter = 8` alongside "entry pass skipped ... not scanned" for the book-gated
  pass: the 8 presumably come from the batch's other strategies' passes; not traced to code
  [assumed].
- 2026-08-10 NULL `entries_enabled` interpretation is [assumed] from deploy timing, not verified
  against the deploy log.

---

# Post-market session analysis — 2026-08-12 (data date)

Analysis date: 2026-08-12 EOD (scheduled post-market agent). Analyst: Claude (scheduled
`session-analysis post`). Data: `signal_rejections` rows **1,342** (bounds
`2026-08-12T09:15:00+05:30`…`15:40`), signals fired **7 ENTRY + 6 EXIT**, paper trades **3
opened / 3 closed, net +₹3,116.14**, shadow closes **43** (champion 33 + challengers 10).

Session character: **Wednesday, no expiry on either exchange** · VIX 11.67–12.09 · morning
crash then afternoon basing (open 24,472.45 → low 24,265.95 at **12:13** → continuous close
24,361.70; heavily PE-sided: 26 CE / 1,022 PE context rows) · signal contract
`NFO:NIFTY26AUGFUT` (log-confirmed, 1,374 hits) · **`daily_profit_target` risk cap tripped
11:01 IST — scalper ENTRY emission paused for the rest of the day** (first observed live trip).

## 1 Funnel numbers

| metric | value |
|---|---|
| rejections | 1,342 (34 of 38 scalpers; boot 06:26 IST read 0 loaded / 38 unresolved, resolved to **38/0/0 at 06:29** — pre-market transient, `unresolved==0` from 06:29 on) |
| eval outcomes | chart-gate-failed 1,984 · confluence-blocked 1,342 · composite-below-threshold 144 · **fired 24** · discipline-paused 0 |
| fired reconciliation | 24 fired evals = **7 ENTRY signals emitted** (09:48–10:48) **+ 17 suppressed by the `daily_profit_target` pause** (11:01–11:46, log-line-per-slug verified) — every fired eval accounted for |
| coverage | **25 of 25** 15-min buckets populated 09:15–15:19; `subscriber_health_events` 0 rows; no stall, no outage |
| first-block histogram | volume-floor 786 (58.6%) · time-window 246 · rsi-band 89 · time-of-day 38 · pct-price-move 30 · two-candle 30 · rest ≤28 |
| all-fails expansion | volume-floor 786 (avg operand 13,133 vs banded avg thr 28,163) · rsi-band 521 · confluence-composite 492 · **strike-pick 0** |
| paper (funded) | pos 61 `SENSEX2681378400PE` 20 @ 563.35 → TIME_STOP 10:37 **−₹473.57** · pos 62 `SENSEX2681378300PE` 20 @ 504.85 → TIME_STOP 10:37 **−₹864.85** · pos 63 `SENSEX2681378300PE` 40 @ 545.05 (pyramid pair 10:49) → TIME_STOP 11:19 **+₹4,454.56** |

**THE HEADLINE — biggest fire day ever (24 fired evals vs prior max 3), the funded book banked
+₹3,116.14, and the #1086/risk-governor stack ended the day, not the market.** The 09:45–11:00
crash leg (−200 pts) put every PE composite in the 0.78–0.99 range; 7 signals emitted, 3 funded
SENSEX positions resulted, and at **11:01:20 IST** `RiskService` tripped
`scalper/daily_profit_target` (mode pct 1.5% ≈ ₹2,250 on the ₹150k book; `dayPnl` = realized
−₹1,338.42 + mark-to-market unrealized on open pos 63 — `PaperAccountService.dayPnl` includes
MTM [computed from code + arithmetic]) and paused ENTRY emission for the day. All 17 later
fired evals were suppressed with per-slug log lines.

## 2 Rail findings

- **§3.27/§2.2 chain-proximity watch: WEDNESDAY CONTROL CONFIRMED — `strike-pick` fails = 0 on
  both roots** (predicted by 08-11's file). Series: 08-03 eve 235 / 08-04 day-of 604 / 08-05
  Wed 0 / 08-07 Fri 350-SENSEX / 08-11 NSE-day-of 322 / **08-12 Wed 0**. Next cluster window:
  BSE Thursday 08-13.
- **volume-floor banded and honest**: avg threshold 28,163 vs the day's aligned 3m future
  volume p50 16,575 / p90 50,375 / max 129,155 — floor ~p70-80 on a thick crash tape, zero
  flat-threshold rows (§3.14 check: `relative-volume-floor` armed 38/38, pubdate 07-28).
- **NEW FINDING — the NIFTY book is structurally UNFUNDABLE at this premium regime.** All 3
  NIFTY ENTRY signals (09:48, 10:00, 10:48 — `NIFTY2681824650PE`) were **zero-sized**:
  `premium=250.95..289.55 lot=65 budget=15000 computedLots=0` (`PaperEmissionGuard` WARN ×3).
  ₹15,000 / 65 = **max fundable premium ₹230.77**; ATM NIFTY PE traded 250–290 all morning. The
  entire funded day was SENSEX (lot 20 → max premium ₹750). Counterfactual cost: the 09:48
  NIFTY leg's shadow twins closed **TAKE_PROFIT +89.85 pts each** (≈ +₹5,840/lot gross at lot
  65) — a winner the funded book could not size into. Proposed as NEW-2 (§7); owner decision
  (budget vs lot-size reality), not a knob this run may touch.

## 3 Composite + dots

- Distribution (scored rows n=1,048): 0.6 bucket 376 · 0.7 bucket 286 · 0.8+ 58; **562 of
  1,048 (53.6%) at/above threshold** — the fattest composite-pass mass ever logged (08-11:
  17.2%), consistent with the one-way PE tape. CE 26 / PE 1,022.
- Dot support (complete session, n=1,048 unless noted): `iv_rank` 0% (withheld, standing) ·
  `iv_pair` **0% (17th session — T3, owner)** · basis 2.5% · trending_cross 8.1% · oi_spurt
  9.1% · volume 25.0% · sentiment_slope 47.3% · futures_oi 54.2% · underlying_oi 57.4% · rsi
  57.5% · vix 71.5% · vwap 78.2% · sentiment 79.7% · psar 83.2% · vwma 88.9% · drastic_oi
  90.2% · breadth 97.5% · supertrend 99.2% · `iv_abs_band` 100% (n=130; frozen daily stamp,
  13th session). Strategy-scoped dots `premium_skew` 41.2% (n=34, hero-zero pair) and
  `iv_slope` 46.9% (n=130, connect-the-dots pair) — present since 07-02, first time surfaced in
  this table because their carrier slugs emitted enough scored rows; not a new mechanism.
- **§3.28 side-split again**: breadth CE tests advances (9–13 vs `>32` → 0/26), PE tests
  declines (37–44 → 1,022/1,022) — free +1.0 on every PE composite, dead on CE; 2nd
  consecutive session with the per-side saturation shape.
- OI bloc fully live: quadrants NEUTRAL **0/1,048** (SHORT_BUILDUP 508 / LONG_BUILDUP 400 /
  SHORT_COVERING 74 / LONG_UNWINDING 66); `futures_oi_snapshots` **375/375 minutes**.

## 4 Data health

| field | today | verdict |
|---|---|---|
| `ivRank` | NULL 1,342/1,342 | dead-data, standing (since 07-02) |
| `dowUp` | NULL 1,342/1,342 | by design (un-armed) |
| `atmIv` | 1 distinct | frozen daily stamp — correct (G12/T28, 13th) |
| `fiiLongPct` | 1 distinct | daily EOD stamp, alive |
| vix | 33 distinct, 11.67–12.09 | alive |
| ceIvAvg6 / skew / basis | 61 / 105 / 96 distinct | alive |
| misaligned 1m candles (§3.15) | **0 rows** | clean |
| §3.17 canary | **2 WARNs, 0 straddles** — but the two ARE an equal-and-opposite ±3,640 pair (56 × lot-65) on adjacent buckets 11:39/11:42 IST, each logged UNPAIRED | the benign ± fingerprint reported un-suppressed — consistent with G9's documented fresh-boot lot-cache behaviour (container boot 06:26 IST today); magnitudes are lot-quantised, not the frozen-partial shape. Watch: if pairs stay un-suppressed on a NON-boot day, that IS a G9 regression |
| Kite session | validated 15:56 IST | ✓ |
| market-data canary | GREEN, 0 problems, 91 ticked tokens | ✓ |
| engine reloads | boot 06:26 (transient 0/38-unresolved) → 06:29 **38/0/0 installed** → reconciles `installed=f` | healthy (`unresolved==0` from 06:29) |

## 5 Shadow-book outcomes + counterfactuals

**Champion: 33 closes, 12W, +391.00 pts, +₹4,799.52 NET — best session since 07-29; all-time
−₹128,548.02.** Deduped (§3.24): **15 (bar, leg) events**. The day splits cleanly at the low:
- **Morning crash entries TP'd**: 09:48 `NIFTY2681824650PE` ×5 **TAKE_PROFIT +449.25 pts /
  +₹28,772.35** · 09:48 `SENSEX2681378500PE` ×6 **TAKE_PROFIT +1,412.40 pts / +₹27,799.08** ·
  10:00 `SENSEX2681378400PE` **TAKE_PROFIT +201.15 pts / +₹3,949.15**. **These are the first
  TAKE_PROFIT closes outside the gap-theory/market-movers families in the book's history** —
  12 TP closes across 12 slugs including two-candle, trend-change, open-high-low, trending-oi,
  connect-the-dots — the T21 (#990) uniform +35% bracket paying for the first time at scale.
- **Post-low entries lost**: every event from 11:00 on is negative (11:00 −543.50 · 11:03
  −397.30 · 11:39 `NIFTY2681824550PE` ×5 −342.30 · six more smaller) — PE entries into the
  basing/bounce.
- Entry latency p50 1:19.6 / p95 1:20.5 (n=43) — structural (G8), unchanged.

**Challengers OPENED for the first time since 08-07: vol-off and vol-12k5, 5 closes each —
0 wins, −347.50 pts, −₹11,446.78 net EACH.** Every challenger entry (11:15–11:27, the
volume-floor-blocked class) was a post-low PE entry and lost. **Sixth measured loosening of
the entry gate, sixth loss** — the standing prior (T1/T7/G13/G10 + today's twin) holds on
live variant data: the floor was correctly refusing the chase entries while the same
composite class TP'd when volume actually confirmed (09:48–10:00).

**Funded-vs-shadow exit experiment (§3.24) — today is the COUNTER-observation to the 5-session
"early-exit dominance" series:** funded pos 61 (10:00 bar, `SENSEX2681378400PE` @563.35,
30-min TIME_STOP) closed **−₹473.57** while the shadow twin on the same bar/leg/entry rode to
**TAKE_PROFIT +201.15 pts**. The 30-minute stop truncated a +35% winner on a momentum crash
day. Prior 5 observations (chop + trend-down grind) all favoured early exits; today's
crash-momentum tape favoured the hold-to-bracket. G11's regime-dependence thesis gains its
first live pro-hold data point (evidence row, not a flip).

**§4.2 counterfactual — the 17 profit-target-suppressed fires** (the new rejection-free class:
no rejection row, no shadow row — reconstructed from suppression log lines + chain snapshots;
modelled 30-min horizon, picker-consistent strikes [the 11:27/11:39 shadow legs pin the picker
at `24600PE`/`24550PE`]; 2–3-min snap granularity, no slippage/fees):

| batch | evals | modelled leg + entry | +30 min | outcome |
|---|---|---|---|---|
| 11:01 (gap-theory ×2) | 2 | SENSEX 78100PE class @ ~512–536 (shadow proxy 11:00/11:03) | stops hit on the bounce | **WOULD-LOSE** (shadow proxies −543.50/−397.30 pts) |
| 11:07–11:13 (connect-dots-nifty) | 3 | 24600PE ~289–291 | ~299 | **WOULD-WIN small** (~+3%) |
| 11:31 ×4 | 4 | 24600PE @ ~290.15 | 299.35 (12:00) | WOULD-WIN +9.20 pts (+3.2%) |
| 11:43 ×4 | 4 | 24550PE @ ~261.20 | 264.40 (12:12) | WOULD-WIN +3.20 pts (+1.2%) |
| 11:46 ×4 | 4 | 24550PE @ ~253.40 | 263.40 (12:16) | WOULD-WIN +10.00 pts (+3.9%) |

Net: the pause avoided the two worst entries and forfeited a cluster of small would-wins
(single-digit % on deduped legs ≈ 3 distinct (bar, leg) events after §3.24 fan-out collapse).
**Verdict: roughly neutral-to-protective on this tape; the +₹3,116.14 banked stands.** The
design question (cap at 1.5% on a book that just proved it can print +3% before noon) goes to
§7 as an owner observation, not a proposal.

## 6 New data points / anomalies

### 6.1 First live `daily_profit_target` trip — and it works exit-side too

First observation of the profit-target pause end-to-end: trip at 11:01:20 on realized+MTM,
17 suppressions logged per-slug, `discipline-paused` 0 (the risk gate sits upstream of the
§12.7 discipline check, so the counter never incremented — the two gates are distinguishable
in telemetry [computed]). Open position 63 was NOT force-closed by the trip (it ran to its own
TIME_STOP at 11:19, +₹4,454.56) — the pause is entry-only, as designed.

### 6.2 §3.34 heat-gate evaluability — PASS (3 funded entries, grep 0)

`heat call failed|heat unassessable` over the session: **0** on a 3-funded-entry day — margin
calls succeeded (#1326 master warm-up held; 2nd consecutive funded-day pass). Evaluability
only; the long-option 0-SPAN coverage question (N23-A) stands.

### 6.3 Mechanical pre-checks

- `tools/ledger-consistency-check.py`: **10 REVIEW lines — the identical standing
  false-positive set** as 08-06…08-11 (5×[A] snapshot/self-referential chips, 5×[B] keyword
  refs). Ledger consistent in substance; no edits required.
- `tools/published-config-drift.py`: **69 published — 69 matched (67 clean, 2 STALE-PUBLISH),
  0 DB-only, 0 YAML-only.** Same 2 as 08-03…08-11: `minervini-cheat-3c` /
  `minervini-primary-base` (1.0.2 drafts of 08-01, name+description only). Republish proposal
  carried — nothing republished by this run.

### 6.4 §3.29 unexercised-path audit (day delta)

Fired vocabulary since 07-01: TRAILING_STOP 13 · **TIME_STOP 6 → 9 (+3 today)** ·
STRUCTURAL_STOP 6 · STOP_LOSS 5 · MANUAL 2. Armed set unchanged (10 (type,basis) rows + tag).
Never-fired stands: `take_profit premium_pct` (36) · `signal_exit` (38) · `square_off` (2) ·
`stop_loss percent` (4) · tag `oi-confluence-exit` (8). INDETERMINATE pair (`trailing_stop
atr_multiple` 2, `stop_loss atr_multiple` 2) stands. **Sharpest-ever classification evidence
for `take_profit`: it is class (c) SHADOWED, not unreachable** — funded pos 61's own leg hit
the +35% bracket in the shadow book after the funded 30-min TIME_STOP had already closed it at
a loss. The funded book cannot reach TAKE_PROFIT while `time_stop` wins the race at 10–12 bars.

### 6.5 §3.30 freeze telemetry

Entries: sub-1 ×1 (10:01), sub-2 ×1 (10:07), sub-3 ×2 (10:49 pyramid pair); subs 4–5 zero.
Day PnL: sub-1 −₹473.57 (first-loss frozen ~10:37) · sub-2 −₹864.85 (first-loss frozen
~10:37) · sub-3 +₹4,454.56 (profit-locked ~11:19). **3 of 5 sub-accounts stopped before 14:30
— the ≥3-by-14:30 flag fires** — but the binding constraint from 11:01 was the GLOBAL
`daily_profit_target` pause, not the per-sub freezes (subs 4–5 never saw an entry because
emission was paused upstream; `discipline-paused` 0 all day). Classification: profit-driven
shutdown on a green day — the design banking, with a measured small opportunity cost (§5
counterfactual). Frozen-by trend: 08-11 1-of-5 (profit-lock, bound nothing) · **08-12 3-of-5 +
global pause by 11:19**.

## 7 Tuning candidates

Ledger §0 group G is the authoritative status; nothing applied by this run.

| # | knob | status | today's evidence |
|---|---|---|---|
| watch | `strike-pick` chain-proximity | **WATCH — Wednesday control CONFIRMED (0 on both roots)** | series intact; next window BSE Thu 08-13 |
| **NEW-2 (08-12)** | NIFTY per-trade budget ₹15k vs lot-65 ATM premium | **PROPOSED (owner)** | 3 of 3 NIFTY fires zero-sized (max fundable premium ₹230.77 vs 250–290 traded); 09:48 shadow twin TP'd +89.85 pts/lot — the budget, not the gate, kept NIFTY out of the best fire day yet |
| **NEW-3 (08-12)** | `daily_profit_target` 1.5% | **OBSERVATION (owner)** | first live trip 11:01; neutral-to-protective today (§5); flagging cap-vs-capacity for an owner read after more green days |
| T29/G11 | scalper `time_stop` | **CLOSED — evidence row added** | first pro-hold observation: 30-min stop turned a +35%-TP leg into −₹473.57 (§5); prior 5 obs favoured early exit — regime-dependent, per the close verdict |
| NEW-1 (08-05) | paper heat-cap margin timeout | **PROPOSED — carried** | 2nd funded re-test PASS (§6.2); N23-A unchanged |
| T30 | `breadth` dot `>32` | **OPEN** | 2nd consecutive side-saturated session (CE 0/26, PE 1,022/1,022) |
| T27 | relative-floor window | **OPEN; arming rec unchanged (NO)** | floor ~p70-80 on thick tape, zero flat rows; challenger twins (vol-off/vol-12k5) lost −₹11,446.78 each on floor-blocked entries — 6th loosening loss |
| T28 | `atmIv` frozen daily stamp | **OPEN** | 1 distinct (13th) |
| T3 | `iv_pair` | **OPEN (owner)** | 0% (17th session) |
| T23 | partial-bucket tolerance | **OPEN — watch** | ±3,640 lot-quantised pair reported UNPAIRED ×2 (boot-fresh lot cache, G9 documented); regression only if it recurs on a non-boot day |
| T1 | `relativeVolumeMultiplier` | **REJECTED — carried** | vol-12k5: 0/5, −₹11,446.78 |
| T7 | composite threshold | **REJECTED — carried** | 562 composite-pass rows on a one-way tape — threshold is not the binding constraint |
| NEW (08-04) | mid-session deploys | **PROPOSED — carried** | clean (no mid-session recreate) |
| NEW (08-03) | minervini republish | **PROPOSED — carried** | 8th session (§6.3) |
| T10 | stale OPEN paper positions | **OWNER — chronic** | **18 OPEN unchanged** (6 manas + 12 minervini) |
| T8/T26 | latency | OPEN (data) | shadow p50 1:19.6 (n=43); signal `emit_latency_ms` 17.2–20.7 s — same structural class |
| T2 | `iv_rank` | carried, not open | NULL 1,342/1,342 |

## 8 Honesty caveats

- The §5 suppressed-fires counterfactual reconstructs legs from log lines + picker-consistent
  shadow strikes — no persisted `wouldBeLeg` exists for fired-then-suppressed evals; horizons
  are the 30-min harness modelling choice (§3.16), never "the armed fleet-wide stop";
  2–3-min snapshot granularity, no slippage/fees.
- Shadow P&L (brackets/structural/square-off, no time stop) and the funded book (per-strategy
  `max_bars`) are different exit models — today they disagreed in sign on the same leg (§5),
  in the OPPOSITE direction from 08-11; both stated.
- Regime is stamped from the CONTINUOUS session (o 24,472.45 → cc 24,361.70, eff 0.534 =
  mixed); the OFFICIAL bar (CAS close 24,435.95, +74.25 print at 15:28) reads eff 0.176 =
  chop. Doctrine (§3.33a) keeps the continuous read; both recorded in the rollup.
- Read-only run: SELECTs, `docker logs`, in-container health GETs. No restarts, deploys,
  writes, config changes, or republishes. Docs-only PR: this file + rollup rows.
