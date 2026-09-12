# Session findings — 2026-09-11 (data date)

⚠️ **BACKFILL, written 2026-09-12.** The scheduled post-market routine did not run on **09-09,
09-10 or 09-11** — all three attempts died on a Claude weekly usage limit (`You've hit your weekly
limit`; the limit reset 09-12 10:30 IST). **The platform never stopped** — 19 SUCCESS `ingest_runs`
and 4 DONE canaries on each of the three days, zero failures — so only the ANALYSIS is missing,
not the data.

This file reconstructs **09-11 in full** and recovers the **cumulative tallies across all three
days** (NEW-9, the loosening ledger, H49, the funded book). **09-09 and 09-10 get session-log rows
in `rollup.md` and the tallies below, but no narrative file of their own** — a deliberate scope
call, not an oversight: the daily files' durable value is the running counters, and those are
recoverable from the DB in one pass rather than three retold narratives.

**What made the reconstruction possible, and what it could NOT recover:** market-data's
`docker logs` still reach back to **09-03** (the containers restarted 09-12 05:48 UTC, and a
restart PRESERVES `docker logs` — only a recreate destroys them), with 1,884 / 1,633 / 1,857 lines
on the three days. So the boot windows, canary events and ticker blips below are read from real log
lines, not inferred. **No `/c/Trading/ArthaYantra/log-snapshots/` directory exists for any of the
three days** — the snapshot is written BY the routine, so a routine that never ran wrote none. Had
a deploy recreated a container in that window, this file could not have been written at all.

Analyst: Claude (Architect session, manual backfill). Data: `signal_rejections` **1,324**
(bounds `2026-09-11T09:15:00+05:30`…`15:40`; rows 09:19:07–15:04:16), signals **0 scalper fires**
(one minervini swing EXIT), paper trades **0 opened / 0 closed**, shadow champion **25 closes, 12
net wins, +₹22,371.81**.

Session character: **Friday, no expiry** · gap-DOWN open 23,270.30 (prior official close
23,477.80 — a −0.88% gap) then an all-day recovery to a 23,435.10 continuous close · regime
**TREND-UP on the continuous read (eff 0.764), MIXED on the official (0.590) — divergent, see
§8** · signal contract `NIFTY26SEPFUT` · **CE-dominated day: 344 composite passes, 306 CE** ·
**ZERO fires despite those 344 passes** — the day's headline.

## 1 Funnel numbers

| metric | value |
|---|---|
| rejections | 1,324 — **36 of 37** enabled scalpers emitted rows |
| eval outcomes | chart-gate-failed 1,893 · confluence-blocked 1,324 · composite-below-threshold 248 · **fired 0** · discipline-paused 0 |
| fired reconciliation (§3.36) | **0 fired = 0 emitted = 0 filled** — a true zero-fire session, nothing suppressed |
| coverage | **24 of 25** populated 15-min buckets 09:15–15:00 (thinnest 15:00 at 4, and 09:15 / 10:00 / 13:30 at 8); rejections end 15:04, so the 15:15 bucket is legitimately empty |
| boot health | boot **08:49:54 IST** (11th consecutive overnight host downtime), `RestartCount=0` both services; auto-login boot catch-up CONNECTED **08:50:37**, ticker 08:50:38 — ~44 s after boot, **8th consecutive fully-automatic login** |
| §3.30 freeze telemetry | **0 of 5 subs — NO flag.** No sub took an entry, so no first-loss freeze was reachable. `discipline-paused` 0 |

## 2 Rail findings

- **volume-floor first-block 682/1,324 (51.5%)** — the lowest share in the recent series (59.9% on
  09-08, 57.1% on 09-07), and banded, not flat (mean blocking margin −16,987.9, min −49,692.5).
- **`time-window` 294 is unusually high** (09-08: 184) and is the day's #2 first-blocker — a
  gap-down open pushes the early bars outside several strategies' entry windows.
- **`confluence-composite` 34 rows, split TWO ways, both `60m bias opposes the side`**: 17
  `scalp-connect-the-dots-nifty` + 17 `scalp-connect-the-dots-sensex-niftyoi`. No
  `stand-aside both-IV-high` rows today (§3.39's third class, present 09-08, absent here) — read the
  split by full reason string, never by position.
- First-block tail: rsi-band 54 · time-of-day-preference 40 · supertrend-15m 34 ·
  divergence-vol-gate 32 · pct-price-move 30 · two-candle 30 · volume-pump 24 ·
  option-side-constraint 22 · open-high-low 12 · vwap-distance 12 · oi-slope-agree 6 ·
  oi-cross-required 6 · oi-divergence-magnitude 6 · psar-durability 2 · call-put-delta-filter 2 ·
  directional-change-gate 2 (19 distinct).

## 3 Composite + dots

- **Composite passes 344 of 966 scored (35.6%) — 306 CE (max 0.8627) / 38 PE (max 0.6649).** That
  is among the highest pass rates in the series and it produced **zero fires**. The 344 passes were
  spread across 14 different blocking rails: volume-floor 162, divergence-vol-gate 26,
  supertrend-15m 26, confluence-composite 26, pct-price-move 24, two-candle 24, volume-pump 18,
  open-high-low 12, vwap-distance 8, oi-divergence-magnitude 6, oi-slope-agree 6, rsi-band 2,
  oi-cross-required 2, directional-change-gate 2. **No single rail owns the zero** — this is a
  broad rail-fan, not one gate misfiring, which is why it is a §6 observation and not a tune.
- **OI bloc fully LIVE**: LONG_BUILDUP 360 / SHORT_BUILDUP 322 / LONG_UNWINDING 158 /
  SHORT_COVERING 126 — quadrants NEUTRAL **0**, and the mix is long-dominant, matching the
  intraday recovery. futures_oi capture **25,806 snaps / 374 of 375 minutes**.
- Dot support (n=966 unless noted): `iv_rank` 0% (withheld, standing) · **`iv_pair` 0% — back to
  zero the session after its first-ever support (T3: 4 rows on 09-08; the "alive but ~once-a-month
  rare" reading holds)** · breadth 6.6% · vix 6.6% · oi_spurt 7.5% · premium_skew 25.0% (n=24) ·
  volume 28.8% · rsi 39.3% · trending_cross 39.5% · futures_oi 51.6% · underlying_oi 57.8% ·
  sentiment_slope 58.2% · iv_slope 61.6% (n=146) · sentiment 68.7% · vwap 70.2% · psar 72.0% ·
  vwma 90.1% · drastic_oi 92.1% · basis 93.4% · **`iv_abs_band` 100% (146/146)** ·
  **`supertrend` 100% (966/966)**.
- **§3.28 breadth (T30) — up-day split, and it is the mirror of 09-08**: breadth supported only
  6.6% on a day that closed up, i.e. it withheld from the CE side that was right. Same family as
  the T30 observation, opposite polarity.

## 4 Data health

| field | today | verdict |
|---|---|---|
| `ivRank` | NULL 1,324/1,324 | dead-data, standing (since 07-02) |
| `dowUp` | NULL 1,324/1,324 | by design (un-armed) |
| `fiiLongPct` | NULL on **358 of 1,324** rows | ⚠️ **NOT a regression — see §6.4.** The same shape is present every recent day. The 09-08 file's "live on all 897 contextful rows" used the OI-CONTEXTFUL denominator, not the row count |
| `atmIv` | 1 distinct | frozen daily stamp, correct mechanism (G12/T28) |
| vix | 14 distinct | alive |
| misaligned 1m candles (§3.15) | **0 rows** | clean — 17th consecutive |
| §3.17 PartialBucketCanary | **0 WARNs + 0 straddles** across all three days | clean; **NEW-6: 4th/5th/6th consecutive clean opening bucket** |
| signal-future capture | 374/375 1m minutes | healthy |
| per-contract tick/bar divergence | **ZERO `data canary RED` events on 09-09, 09-10 AND 09-11** | **H49 — first break in the shape. See §6.1** |
| market-data ERRORs | 09-09 **0**, 09-10 **0**, 09-11 **2** (both benign — §6.5) | clean |

## 4b BOOT WINDOW (§3.41) — clean, stated as an explicit zero

All IST. **Circuit-breaker transitions: ZERO. Capture minutes lost overlapping the window: ZERO**
(login complete 24 minutes before the open).

- **08:49:54** — containers start (11th consecutive overnight host downtime). `RestartCount=0` on
  both services — the 09-04/09-07 DB-not-ready bean-race crash did NOT recur.
- **08:50:06** — `kite session restore: persisted token from 2026-09-10T02:33:30Z expired at
  2026-09-11T06:00+05:30 — NOT resumed` (#1520's 8th refusal), followed by the expected
  `kite ticker start failed (breaker CLOSED): no live Kite session yet`.
- **08:50:17** — auto-login boot catch-up armed, inside window. **08:50:37 authorize hop 1 → 302
  same-origin; request_token at hop 2; CONNECTED 08:50:37; ticker connected 08:50:38** — the 8th
  consecutive fully-automatic login, ~44 s after boot.
- Missed-cron catch-ups, all clean: `ingest canary catch-up: today's 0 45 8 * * MON-FRI fire was
  missed (stack down?) — swept 2026-09-10 on boot` · instrument-sync catch-up `OK` at 09:05:06 ·
  NSE/BSE bhavcopy catch-up `0 days, 0 rows` (nothing owed) · **two `RECOVERY_EXITS` swing passes**
  at 08:50:33 / 08:51:51 (§6.3).
- Benign pre-login noise, named rather than omitted: ~10 `gap fetch failed … no live Kite session
  for historical fetch` WARNs and 2 `option ATM pin resolution failed` — all between boot and
  08:50:37, all resolved by the login. These are the cost of booting after the 08:05 slot, not a
  defect.

## 5 Shadow outcomes + counterfactuals

### 5.1 Funded book — ZERO fills on 09-11, two GREEN days before it

Nothing opened or closed on 09-11. The three-day funded picture, recovered:

| day | fills | W/L | net |
|---|---|---|---|
| 09-09 | 3 | **2W/1L** | **+₹1,068.60** |
| 09-10 | 2 | **1W/1L** | **+₹631.06** |
| 09-11 | 0 | — | — |

| pos | leg | entry (IST) | exit | net |
|---|---|---|---|---|
| 117 | SENSEX2691075600PE ×20 @561.10 | 09-09 09:52 | TRAILING_STOP 10:22 | −₹525.41 |
| 118 | SENSEX2691075500PE ×20 @486.90 | 09-09 11:55 | TIME_STOP 12:25 | **+₹709.61** |
| 119 | NIFTY2691523750PE ×65 @240.15 | 09-09 11:55 | TIME_STOP 12:25 | **+₹884.40** |
| 121 | NIFTY2691523650PE ×130 @229.65 | 09-10 14:10 | STRUCTURAL_STOP 14:25 | −₹500.42 |
| 122 | SENSEX2691075000PE ×40 @327.40 | 09-10 14:10 | TIME_STOP 14:40 | **+₹1,131.48** |

⚠️ **Two consecutive green funded days is the first such run measured** — and it is the T29 exit
model's FAVOURABLE face: three of the five closes are TIME_STOPs and all three were winners. The
same mechanism that cut 09-07's and 09-08's right-direction PE trades short banked these.
**n=5. Do not read a trend into it.**

§3.36 reconciliation across the two fired days, and it needs care: 09-09 shows **9 fired evals →
6 ENTRY signals + 3 EXIT signals → 3 fills**, which looks like 3 missing opens. It is not. Four of
the six entries are repeat fires on `NIFTY2691523750PE` (11:54, 12:00, 12:03, 12:15) against
position 119, already open from 11:55 — the idempotency path correctly declining to pyramid.
09-10 is the same shape (3 entries at 14:09, two of them the same NIFTY leg → 2 fills).
**Both days reconcile cleanly once repeat fires on an open leg are accounted for.**

Emit latency across the two days: **16.1–19.6 s**, with **19.6 s a new high-water** (prior 19.5 s,
09-07) — plus one 680 ms outlier on 09-10's final exit. §3.29 delta: TIME_STOP +3, TRAILING_STOP
+1, STRUCTURAL_STOP +1; **never-fired set unchanged** (take_profit premium_pct 35 armed / 0 closes
since 07-01 · signal_exit 37 / 0 · square_off 2 / 0 · oi-confluence-exit tag 8 / 0).

### 5.2 Shadow book — 09-11 was a strong green day

| day | champion closes | net wins | net ₹ |
|---|---|---|---|
| 09-09 | 33 | 11 | **−₹47,454.99** |
| 09-10 | 26 | 6 | **−₹22,963.49** |
| 09-11 | 25 | 12 | **+₹22,371.81** |

**09-11 champion deduped to 8 `(bar, leg)` clusters**, and the green is concentrated in the CE
recovery: 10:45 `NIFTY2691523100CE` ×8 **+₹31,831.86** (STRUCTURAL_STOP + TAKE_PROFIT) · 11:12
`23150CE` ×2 **+₹13,903.84** (TAKE_PROFIT) · 14:00 `23200CE` ×4 **+₹9,259.20** · 14:03 `23250CE`
−₹1,512.30 · 14:30 `23450CE` −₹303.52 · 14:30 `SENSEX2691775900CE` −₹91.98 · against the morning
PE side that fought the recovery: 09:24 `23450PE` −₹4,098.51 · 09:48 `23450PE` ×6
**−₹26,616.78**. All-time champion **−₹461,140.69** (1,036 closes, 347 net wins).

**Loosening ledger across the three days: 18 challenger-only observations, 4 wins, 14 losses —
59/45/14 → 77/59/18.** Counted the strict way (rows a variant took where champion has no row on the
same `rejection_id`), not by per-variant close counts: 09-09 composite-055 0/5 −₹9,833.92 · 09-10
composite-055 **3/5 +₹239.58** and vol-12k5 0/2 −₹6,411.03 · 09-11 composite-055 **1/2
+₹6,195.77**, vol-12k5 0/2 −₹4,491.71 and vol-off 0/2 −₹4,491.71. All-time: composite-055
**−₹28,564.77** (100 closes) · vol-12k5 **−₹62,616.41** (139) · vol-off **−₹93,387.34** (177).
**REJECTED statuses stand** — every challenger book remains deeply negative, and the three-day
win rate (4 of 18) does not move them.

### 5.3 §4.2 counterfactuals — the veto refused the day's winners

Sole-blocker set on 09-11: **15 rows — 13 `confluence-composite` + 2 `volume-floor`.** All 13 veto
rows are **CE, all `scalp-connect-the-dots-nifty`**, spanning 11:12–14:09 with composites
0.6275–0.8627 on legs `NIFTY2691523150CE` / `23200CE` / `23250CE`.

Three of those 13 have shadow rows joined **directly on `rejection_id`** — not matched by hand on
`(bar, leg)`, so the attribution is exact:

| bar | leg | variant | close | net |
|---|---|---|---|---|
| 11:12 | NIFTY2691523150CE | composite-055 | TAKE_PROFIT | **+₹6,951.92** |
| 14:00 | NIFTY2691523200CE | **champion** | SQUARE_OFF | **+₹2,314.80** |
| 14:00 | NIFTY2691523200CE | vol-12k5 / vol-off | SQUARE_OFF | +₹2,314.80 each (fan-out — do not sum) |
| 14:03 | NIFTY2691523250CE | composite-055 | SQUARE_OFF | −₹756.15 |

**NEW-9 day 11 — the strongest single day AGAINST the veto so far: 2 corroborated winners refused
(+₹6,951.92; +₹2,314.80 champion-reference) against 1 small loser (−₹756.15).** The mechanism is
legible: the index gapped DOWN 0.88% overnight, so the 60m bias pointed down at the open and kept
pointing down while the intraday reversed and closed up 0.71% off its own open. **The veto's blind
spot is a gap-down-then-reverse day** — the 60m window is still reading the gap while price has
already turned.

**Running tally: ~28 losers refused vs ~8 winners** (was 27 vs ~6 on 09-08; 09-11 adds 2 winners
and 1 loser). The veto is still net-favourable on the accumulated count, and **this single day does
not overturn it** — but it is the first day whose refused set is decisively positive, and it names
a specific, testable condition rather than a general doubt.

## 6 New data points / anomalies

### 6.1 H49 — ZERO canary REDs on all three days, the first break since escalation

`data canary RED` events, counted from the surviving market-data logs: **09-04 → 13 · 09-07 → 8 ·
09-08 → 7 · 09-09 → 0 · 09-10 → 0 · 09-11 → 0.**

⚠️ **This is a real zero, not a coverage artifact**, and that distinction was checked before it was
written: the three silent days carry **1,884 / 1,633 / 1,857** log lines — MORE than 09-07 (1,389)
and 09-08 (1,646), which did produce REDs — and the grep ran on the RAW stream before any JSON
parsing, so the 833 unparsed lines cannot be concealing any (13 + 8 + 7 accounts for all 28 RED
lines in the window).

⚠️ **It also weakens 09-08's mechanism correlate rather than supporting it.** That correlate
proposed that a silent ticker re-establish precedes the clustered REDs. **09-11 had TWO ticker
disconnects** — `kite ticker error: Failed to connect to 'ws.kite.trade:443'` ×2 then
`disconnected` at 11:11:20–26, and a second `disconnected` at 12:02:41 — **and produced zero
REDs.** Combined with 09-04 (zero disconnects, 13 REDs) and 09-07 (a blip at 14:26 matching no RED
window), a re-establish is now measured as neither necessary NOR sufficient. Ledger H49 already
records the correlate with those limits attached; this is the third disconfirming case.

### 6.2 The strategy-signal LOG BARRIER closed itself, mid-process, after nine days

The barrier (open item, PR #1578) has a measured lifetime now. Stdout went silent at
**2026-09-01T07:11:57Z** and resumed at **2026-09-10T09:10:01Z** — **a nine-day hole** — and it
resumed **mid-stream on an ordinary `scalper confluence blocked entry` line, not at a boot line.**
The preceding `Starting StrategySignalServiceApplication` is 09-01T02:20:14Z and the next is
09-11T03:19:54Z, so **the same process that went silent is the one that resumed**: no restart, no
deploy, no intervention.

That rules out the simplest explanations (a dead process, a lost container handle) and points at a
stalled log CONSUMER with the writer either blocking or discarding — the service was demonstrably
alive throughout, writing 1,200–1,300 `signal_rejections` rows a day. **It does not settle the
mechanism**, and it is one observation of a self-clearing fault, which is the hardest kind to
attribute. Recorded because #1578 is open and had no duration, no boundary timestamps and no
"clears without a restart" datapoint until now.

### 6.3 A `RECOVERY_EXITS` swing pass appeared for the first time in this series

`swing_batch_runs` carries two passes on 09-11 morning that no prior findings file names:
`manas-arora` 08:50:33 and `minervini` 08:51:51, both `pass = RECOVERY_EXITS`, both for
**run_date 2026-09-08** — three sessions earlier — with 0 candidates and 0 exits. 09-08's own
SETTLE ran normally at 18:52. Benign on the numbers; noted because the pass TYPE is new to this
record, and a reader matching passes against the documented two-phase ENTRIES/SETTLE model will not
find it there.

All settles in the window ran and all report **`exit_skipped` 0** — the H27 gate, judged the H27
way. 09-11's minervini settle produced **1 exit** (a TRAILING_STOP, the day's only signal row).
Swing entries: 09-10 manas 1 entry / 5 cap-exceedance, minervini 0 of 14 would-enter; 09-11 both
zero admitted (manas 5 cap-exceedance, minervini 8). **NEW-8 13th/14th measurement.**

### 6.4 A near-miss worth recording: `fiiLongPct` nulls are NOT a regression

358 of 1,324 rows carry a null `fiiLongPct` on 09-11, and against the 09-08 file's "live on all
897 contextful rows" that reads like a fresh data-health failure. It is not — the denominators
differ (OI-contextful rows vs all rows), and the same ratio is present on **every** recent day:
09-07 331/1,213 · 09-08 239/1,136 · 09-09 268/1,275 · 09-10 298/1,216 · 09-11 358/1,324.
**The distinguishing check is one query across days**, and it is recorded here because the
alarming-direction misread was one step away.

### 6.5 Minor anomalies, named so "benign" and "absent" do not read the same

- **Two market-data ERRORs on 09-11, both benign.** 08:50:48 `AsyncRequestNotUsableException:
  ServletOutputStream failed to write: Broken pipe` — a client hung up mid-response during boot.
  19:27:56 `RejectedExecutionException: event executor terminated` / `Failed to submit a listener
  notification task. Event loop shut down?` — Netty shutting down ~29 min after the evening chain
  finished, consistent with the routine overnight host shutdown. **09-09 and 09-10: zero ERRORs.**
- **`bhavcopy-close canary YELLOW` on all three days** — 1 of 214 symbols on 09-09 (AUROPHARMA,
  1.27%), 1 of 214 on 09-10 (LTM, 1.30%), **3 of 214 on 09-11** (AVALON 1.07%, PHOENIXLTD 1.05%,
  GMRAIRPORT). Single-symbol divergences at this magnitude are the canary working; the step to
  three is worth watching, not acting on.
- **`minervini screen already running — scheduled trigger skipped (H13: two doors overlapped)`** on
  09-09 and 09-10 at 18:46:58 — the H13 guard doing its job, recorded so its firing rate is visible.
- **Two `Cannot reconnect to [redis/<unresolved>:6379]: CancellationException` WARNs** on 09-10 at
  01:33 IST, during host shutdown. No session impact.

### 6.6 Mechanical pre-checks

`tools/ledger-consistency-check.py`: **12 REVIEW lines — the identical standing set**, unchanged.

## 7 Tuning candidates

Ledger §0 group G/H is the authoritative status; nothing applied by this run.

| # | knob | status | evidence |
|---|---|---|---|
| NEW-9 (08-26) | 60m-bias veto | **OPEN — day 11, and the first decisively NEGATIVE day: 2 corroborated winners refused (+₹6,951.92 / +₹2,314.80 champion-ref) vs 1 loser (−₹756.15). Names a testable condition — a gap-down-then-reverse day leaves the 60m window reading the gap. Tally ~28 losers vs ~8 winners** | §5.3 |
| H49 (ledger) | per-contract tick-agg bar-closing sparse | **OPEN — ZERO REDs on 09-09/10/11, the first break since escalation; 09-11's two ticker disconnects with zero REDs make the 09-08 re-establish correlate neither necessary nor sufficient** | §6.1 |
| log barrier (09-01) | strategy-signal docker log | **OPEN (#1578) — now BOUNDED: silent 09-01 07:11:57Z → 09-10 09:10:01Z, nine days, cleared MID-PROCESS with no restart** | §6.2 |
| T3 | `iv_pair` | **OPEN (owner)** — back to 0% the session after its first-ever support; the "alive but ~monthly-rare" reading holds | §3 |
| T29 | exit model dominates entry gate | **OPEN (exit-band track)** — the FAVOURABLE face for once: 3 TIME_STOPs across 09-09/09-10, all three winners, two green funded days | §5.1 |
| T30 | `breadth` dot `>32` | **OPEN** — up-day mirror: 6.6% support on the day the CE side was right | §3 |
| T28 | `atmIv` frozen daily stamp | **OPEN** — `iv_abs_band` 100% (146/146), stamp inside the band | §3 |
| T27 | relative-floor window | **OPEN; arming rec unchanged (NO)** | binding 51.5%, the lowest in the series; loosening ledger still deeply negative |
| NEW-8 (08-24) | swing governor watch | **STANDING — 13th/14th measurement**: manas 1 entry (09-10) then 0, minervini 0 both; cap-exceedance 5 and 8 | §6.3 |
| NEW-6 (08-19) | unpaired opening-bucket canary WARN | **CLEAN — 0 WARNs + 0 straddles all three days (4th/5th/6th consecutive clean opening)** | §4 |
| NEW-16 (09-07) | weekend-stale Kite token 403 storm | **carried — not observable on any of these days** (all three booted after the token had already died); next observation Monday 09-14, watch scheduled | §4b |
| NEW-13 (09-01) | recurring host outbound-network death | **OBSERVATION (owner/ops) — carried**; 2 silent ticker re-establishes on 09-11, zero capture impact | §6.1 |
| NEW (09-04) | DB-not-ready boot-race crash | **carried — did NOT recur on any of the three days** (`RestartCount=0` throughout) | §4b |
| T8/T26 | latency | **OPEN (data)** — funded emit **19.6 s, a new high-water** | §5.1 |
| NEW-10 / NEW-3 / NEW-1 / NEW (08-04) | risk-limit base · daily_profit_target · heat-cap timeout · mid-session deploys | **carried, unchanged** — no trips, no deploys | — |
| T1 / T7 | `relativeVolumeMultiplier` / composite threshold | **REJECTED — carried** | challenger books remain deep negative |
| T2 | `iv_rank` | carried, not open | NULL 1,324/1,324 |

## 8 Honesty caveats

- **This is a backfill written a day late, and two of the three days are covered only by counters.**
  09-09 and 09-10 have session-log rows and tallies, not narratives. Anything that would only have
  been visible in a full §3 pass on those two days is **not** recovered and never will be.
- **Regime is DIVERGENT on two of the three days — both reads are given, neither is suppressed.**
  09-09: continuous eff **0.647** / official **0.647** — trend-down, aligned. 09-10: continuous
  **0.543 (mixed)** vs official **0.272 (chop)** — **divergent**, CAS delta **+88.55**, the largest
  in the recent series. 09-11: continuous **0.764 (trend-up)** vs official **0.590 (mixed)** —
  divergent, CAS delta −37.00. Under the continuous read (the primary), **no day here is chop, so
  the G11 chop count stays 10** — but 09-10 IS chop on the official read, and that is exactly the
  doctrine tension §3.33 exists to surface. A reader taking the official stamp alone would
  increment G11.
- 09-09's continuous freeze broke a minute early — the pin held 23,462.45 through 15:27 and the
  15:28 bar already carried 23,431.50 — so its CAS delta is not cleanly separable and the day is
  reported as aligned rather than given a number.
- **Log-derived numbers come from `docker logs --tail`, with no snapshot to cross-check.** The
  window is contiguous back to 09-03 and the per-day line counts confirm coverage, but a second
  source does not exist for these three days.
- The §5.3 counterfactual is joined on `rejection_id`, so the leg attribution is exact — but the
  P&L is the SHADOW book's fill model, not a funded fill, and the 14:00 row fans out across three
  variants at the identical value (champion-reference used; summing them would triple-count).
- **n=5 on the funded book across two days.** The "two consecutive green days" and the favourable
  TIME_STOP reading are observations, not a measured change in the exit model.
- Read-only run: SELECTs, `docker logs --tail`, `docker inspect`. No restarts, deploys, writes,
  config changes or republishes. Docs-only PR: this file + rollup rows + the H49 ledger cell.
