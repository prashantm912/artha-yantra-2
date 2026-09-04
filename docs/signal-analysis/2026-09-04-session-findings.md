# Session findings — 2026-09-04 (data date)

Analysis date: 2026-09-04 (scheduled post-market agent, run ~15:50–16:45 IST — before the 18:45+
evening chain, §8). Analyst: Claude (scheduled `session-analysis post`). Data: `signal_rejections`
rows **1,341** (bounds `2026-09-04T09:15:00+05:30`…`15:40`; rows 09:19:42–14:58:01), signals fired
**0** (zero-fire session), paper trades **0 entries / 0 closes**, shadow champion **16 closes,
0 net wins, −₹18,524.73** (§5.2).

Session character: **Friday, no expiry on either exchange (post-BSE-weekly)** · gap-up open +37.45
(+0.16% vs 09-03's official close; o 23,910.90) → morning rally to 24,005.75 → afternoon fade to a
23,937.90 continuous close · regime **CHOP (continuous eff 0.246; official 0.120 — chop on BOTH
reads) — G11's 10th chop observation** (§8) · VIX 10.73–11.34 · signal contract
**`NFO:NIFTY26SEPFUT`** (canary-line-named + §3.18 range check; the 2 `morning`-family 09:19 rows
read the index series, close 23,985) · **CE-only composite day: 360 passes CE / 0 PE, max 0.9118 —
and the 60m-bias veto refused every would-be entry (746 veto fails)** (§2, §5.3) · 5th consecutive
fully-automatic Kite login (boot catch-up; first start attempt crashed on a DB-not-ready race —
§4b) · ✅ **the 09-01 log barrier PARTIALLY CLEARED: today's strategy-signal lines are readable
again via bounded `--tail` reads** (§6.1).

## 1 Funnel numbers

| metric | value |
|---|---|
| rejections | 1,341 — **35 of 37** enabled scalpers emitted rows; the missing pair (`scalp-open-high-low-nifty-pe` / `-sensex-niftyoi-pe`) evaluated **45× each**, every eval `chart-gate-failed`/`composite-below-threshold` (V053 denominator — outcomes that write no rejection row), so coverage is genuinely FULL |
| eval outcomes | chart-gate-failed 1,870 · confluence-blocked 1,341 · composite-below-threshold 254 · **fired 0** · discipline-paused 0 |
| fired reconciliation (§3.36) | 0 fired = 0 emitted = 0 filled; zero suppressions, zero governor refusals — trivially exact |
| coverage | **23 of 23** populated 15-min buckets 09:15–14:45 (thinnest: 09:15 at 8, 13:30 at 12); rejections end 14:58 |
| boot health | first start attempt 08:32:14 IST **crashed** (DB-not-ready race, §4b); healthy boot 08:32:29; reload 0/37-unresolved transient at 08:32:46 → **37/0/0 at 08:33:49** (~63 s, in-band) |
| §3.30 freeze telemetry | **explicit zero** — 0 OPENED events on all 5 subs (zero-fire day); `discipline-paused` 0; flag trivially not hit |

## 2 Rail findings

- **volume-floor first-block 762/1,341 (56.8%)** — banded and honest: **43 distinct thresholds**,
  5,752.5–41,827.5, zero flat; `relative-volume-floor` armed **37/37** (§3.14 registry check
  clean); all-fails avg operand 9,668 vs avg threshold 21,326.
- **`strike-pick`: 354 all-fails, ALL SENSEX-rooted (15 slugs), ZERO NIFTY** — the §3.27
  post-BSE-expiry-Friday cluster is BACK at saturation (Friday series: 07-24 550 · 07-31 374 ·
  08-07 350 · 08-14 14 · 08-28 65 · today **354** — 4 of 6 saturated). Fresh 09-10 SENSEX weekly's
  delta-band strikes pricing outside the static 300–800 band, per the amended chain-pricing claim.
- **confluence-composite all-fails 1,016 = 746 `60m bias opposes the side` (composite
  0.3191–0.9118) + 270 score-shortfall aggregates (22 distinct values 0.2128–0.5585)** (§3.39
  split). First-block share 48. **The veto is the day's story: all 360 composite passes were CE,
  fired = 0, and the sole-blocker veto set is the largest ever recorded — 18 rows / 10 deduped
  legs, ALL resolved losers** (§5.3).
- First-block tail: time-window 279 · pct-price-move 32 · divergence-vol-gate 32 · volume-pump 32 ·
  two-candle 32 · time-of-day-preference 30 · option-side-constraint 16 · open-high-low 14 ·
  oi-cross-required 14 · rsi-band 13 · directional-change-gate 12 · oi-slope-agree 10 ·
  oi-divergence-magnitude 6 · supertrend-15m 5 · morning-opening-formation 2 · hero-zero 2
  (18 distinct rails).

## 3 Composite + dots

- **OI bloc fully LIVE**: quadrants NEUTRAL **0/1,016** (SHORT_COVERING 386 · LONG_UNWINDING 226 ·
  SHORT_BUILDUP 224 · LONG_BUILDUP 180), spurt NULL 0, basis LIVE 1,016/1,016. futures_oi capture
  25,806 snaps / **374 of 375 minutes** (missing: 09:15 capture-start only).
- **Composite passes 360 of 1,016 scored (35.4%) — CE 360 (max 0.9118) / PE 0.** The highest
  pass share since the metric started being quoted, and none fired: the 60m-bias veto held the
  gate shut all day.
- Dot support (n=1,016 unless noted): `iv_pair` 0% (**34th** — T3, owner) · `iv_rank` 0%
  (withheld, standing) · oi_spurt 2.8% · vwap 17.3% · iv_slope 23.3% (n=146) · volume 25.0% ·
  trending_cross 34.5% · premium_skew 40% (n=10) · underlying_oi 50.9% · breadth 54.7%
  (side-split below) · futures_oi 55.5% · sentiment_slope 65.1% · rsi 66.2% · sentiment 70.4% ·
  basis 73.4% · vix 73.6% · psar 84.5% · drastic_oi 88.8% · vwma 97.4% · supertrend 99.6% ·
  **`iv_abs_band` 100% (n=146, 2nd consecutive day)**.
- **`iv_abs_band` stays 100% on a fresh stamp**: atmIv 1 distinct = **0.102835** (09-03's 16:00
  write — the daily mechanism is healthy), inside the 0.10–0.12 band again. T28's coin-flip face,
  second flip-side day.
- **§3.28 breadth (T30) — side-split, partial not saturated**: CE 556/746 (74.5%, advances 24–36
  crossing `>32` intermittently); PE 0/270 (declines 21–28, never). No exactly-on-the-line pin
  today (CE max 36).

## 4 Data health

| field | today | verdict |
|---|---|---|
| `ivRank` | NULL 1,341/1,341 | dead-data, standing (since 07-02) |
| `dowUp` | NULL 1,341/1,341 | by design (un-armed) |
| `fiiLongPct` | live on all 1,016 contextful rows (11.09 daily stamp) | healthy |
| `atmIv` | 1 distinct (**0.102835 — FRESH, 09-03's EOD write**) | frozen daily stamp, correct mechanism (G12/T28) |
| vix | 14 distinct, 10.73–11.34 | alive |
| misaligned 1m candles (§3.15) | **0 rows** | clean — 14th consecutive |
| §3.17 canary | **2 WARNs + 0 straddles — KNOWABLE again (§6.1)**: ±1,495 (23 NIFTY lots) on `NIFTY26SEPFUT` consecutive buckets 09:48/09:51, both UNPAIRED = the day's-first-non-benign-event lot-cache-miss pair, benign-by-shape (§3.17 amended 08-25) | clean; **no opening-bucket WARN — first observably clean opening since 08-31** (NEW-6) |
| signal-future capture | full live session; futures_oi 374/375 minutes | healthy |
| options chain capture | 1,162,592 snaps, last 15:33 | healthy, full session |
| per-contract tick/bar divergence | **13 `data canary RED` events, 11 contracts, on a NON-expiry day** | **NEW-15 recurrence — ESCALATED to ledger H49**, §6.2 |
| dot-health | consistent with the row-level reads above; no newly-dead dot | clean |

## 4b BOOT WINDOW (§3.41) — one benign crash-and-restart, then the 5th consecutive fully-automatic login

All IST. **Circuit-breaker transitions in the boot window: ZERO — and ZERO in market-data's whole
day. Capture minutes lost overlapping the window: ZERO** (futures_oi's one missing minute is the
09:15 capture-start, and the session was CONNECTED 42 min before the open).

- **08:32:14** — containers start (8th consecutive overnight host downtime). ⚠️ **strategy-signal's
  FIRST start attempt CRASHED**: `BeanCreationException: paperStrategyScopeGuard … Failed to obtain
  JDBC Connection` ← `PSQLException: FATAL: the database system is starting up` — the service raced
  Postgres's own recovery. Docker's restart policy relaunched it at **08:32:29**; second boot
  clean. `RestartCount=1` on BOTH services (same class as market-data's 09-01 bean race — the
  benign self-heal working, but the fail-fast `afterPropertiesSet` DB probe has no retry; noted,
  not proposed).
- **08:32:39** — `kite session restore: persisted token from 2026-09-03 … expired … NOT resumed`
  (#1520 refusing the dead token, 5th consecutive morning).
- **08:32:52** — boot catch-up arms ("inside the window — will attempt in 20s").
- **08:33:12** — attempt fires: authorize hop 1 → 302 same-origin, `request_token` at hop 2 →
  **CONNECTED 08:33:13** (~47 s boot-to-connected; 5th consecutive fully-automatic login).
- **08:32:46 → 08:33:49** — engine reload transient 0/37-unresolved heals to **37/0/0** (~63 s).

## 5 Shadow outcomes + counterfactuals

### 5.1 Funded book — zero fires, zero entries

Nothing fired, so §3.34 (heat grep) is not evaluable, §3.40 (settle reference) is moot, and the
§3.29 fired-vocabulary delta is zero. Never-fired set unchanged: `take_profit premium_pct` (35
armed, 0 closes since 07-01) · `signal_exit` (37, 0) · `square_off` (2, 0) · tag
`oi-confluence-exit`→CONFLUENCE_FLIP (8, 0). INDETERMINATE standing: the two `atr_multiple` rows;
`stop_loss` premium_pct/percent bases.

### 5.2 Shadow book — 16 closes, 0 net wins, −₹18,524.73

**Champion: 16 opened+closed (0 stranded) → 4 deduped `(bar, leg, entry)` clusters on 4 bar
times, EVERY cluster negative** — the CE-chase into the morning rally, held into the afternoon
fade: 09:54 `NIFTY2690823800CE` ×6 @226.40 SQUARE_OFF **−₹6,736.92** · 10:00 same leg @234.95
−₹1,678.83 · 10:27 STRUCTURAL_STOP −₹526.26 · 11:24 `SENSEX2691076300CE` ×8 @799.40
SQUARE_OFF/STRUCTURAL_STOP **−₹9,582.72**. All-time champion **−₹420,182.42** (917 closes, 299
net wins). Entry latency p50 **1:21.5** / p95 1:23.2 (n=32) — **2nd consecutive session above the
1:18–1:20 structural band** (T8/T26 watch hardening).

**Challenger-only class: 7 observations, 0 wins, −₹3,206.45** (50th–56th measured loosenings):
vol-off `NIFTY2690823850CE` 11:00/11:09/11:21 STRUCTURAL_STOPs −515.88/−476.77/−606.73 ·
vol-12k5 11:24 −77.28 · composite-055 11:24 −77.28, 12:36 `NIFTY2690824150PE` −961.89 +
`SENSEX2691077400PE` −490.62. **Loosening ledger 49/38/11 → 56 measured / 45 losses / 11 wins.**
All-time: composite-055 **−₹23,522.38** · vol-12k5 **−₹50,892.94** · vol-off **−₹82,645.19** —
all three books deeper negative; REJECTED statuses stand.

**Per-rail counterfactual P&L (owner directive 08-20), all-time champion NET:** volume-floor 511 /
**−₹277,279.06** · rsi-band 109 / −₹78,355.28 · two-candle 40 / −₹11,456.50 ·
call-put-delta-filter 7 / −₹11,149.59 · morning-opening-formation 4 / −₹9,629.13 ·
divergence-vol-gate 39 / −₹9,013.31 · max-oi-sr-gate 9 / −₹6,998.85 · volume-pump 39 / −₹5,767.44 ·
**`confluence-composite` 23 / +₹4,264.36 — the cushion gave back −₹2,884.40 today (3 new closes)**
· oi-cross-required 29 / +₹1,155.04 · pct-price-move 55 / +₹894.37. **Root split: SENSEX
−₹1,197.84/trade (8) vs NIFTY −₹1,117.75 (8) today — both deep negative; all-time SENSEX −₹207.75
(415) vs NIFTY −₹674.68 (495)** — yesterday's SENSEX-positive flip reversed again (6th flip);
still not actionable.

**T29/G11 note (chop day #10, decision already made — KEEP)**: today again stop-favouring. On the
shared `NIFTY2690823850CE` family the challenger STRUCTURAL_STOPs cut at −₹77…−₹607 per trade
while the champion's square-off holds on the adjacent legs averaged −₹1,122…−₹1,198 per trade.
Consistent with 07-31/08-06/08-07/09-02; no action (owner decided 07-31).

### 5.3 §4.2 counterfactuals — the 60m-bias-vetoed set (day 8, NEW-9): LARGEST SET YET, ALL LOSERS

The sole-blocker veto set returned **18 rows → 10 deduped `(bar, leg)` clusters, all CE** (09:54 +
10:03 `NIFTY2690823800CE` · 11:24 `SENSEX2691076300CE` · 11:24–11:42 `NIFTY2690823850CE` ×7 ·
composites 0.6383–0.9118). **Every priceable observation is a corroborated LOSS**: the champion
book itself opened the 09:54/10:00/10:27/11:24 clusters (−₹6,736.92 / −₹1,678.83 / −₹526.26 /
−₹9,582.72) and the challenger books opened the 23850CE family (structural stops −₹77…−₹607).
The veto refused every CE entry into the fading bounce on a chop day — the momentum-filter
behaviour exactly as characterised in the rollup watchlist. **Tally: 15 + 10 = 25 losers refused
(d1–3, d5, d6, d8) vs ~6 winners + 2 washes refused (d4).** Keep accumulating; still no proposal
(the one adverse day, 08-31, cost ~₹48.5k — the sign is regime-dependent).

## 6 New data points / anomalies

### 6.1 Log barrier PARTIALLY CLEARED — today's strategy-signal lines readable via bounded `--tail`; `--since`/bare reads still die at 09-01 12:41

The 08:32 container restart changed the barrier's shape. Measured today: bare `docker logs` and
every `--since` read still return only the old corrupt segment (4,858 lines ending 09-01 12:41
IST — `--since 2026-09-04…` returns **zero lines**, the silent-artifact trap of 09-02 §6.1
unchanged); but **`--tail N` with N ≲ 5,000 now reads the NEWEST json segment, which starts
2026-09-02 11:10 IST and runs to now** (`--tail 7000` collapses back to the old segment — the
reader dies crossing the corrupt file). Consequences: **every log-derived check for TODAY is
KNOWABLE again** (§3.17 canary, boot lines, suppressions — all done above from a
`--tail 5000` snapshot saved to the scratchpad); the permanently unreadable hole narrows to
**09-01 12:41 → 09-02 11:10 IST**; the §6.1 recreate proposal stands but loses urgency — a
recreate now buys `--since` convenience, not visibility. Workaround for future sessions until the
next recreate: `docker logs --tail 5000` + timestamp filter, never `--since`.

### 6.2 NEW-15 RECURRED on a NON-expiry day — ESCALATED to ledger row H49

13 `data canary RED` events of the tick-agg-not-closing-bars shape on **11 distinct contracts —
8 BFO SENSEX strikes (75500/75700/77700/77800 CE+PE, fresh 09-10 weekly) + 3 NFO NIFTY strikes
(23450PE/24450CE+PE)** — on a Friday with no expiry on either exchange and a clean network (0
breaker transitions, 0 ticker disconnects). 09-03's escalation rule fires: this is no longer
attributable to expiry-day pinning or the 09-01 network degradation. Same DB-heals-underneath
shape (KITE tail re-fetch backfills; rails read the corrected 3m rollup; no funded exposure —
zero fires today). Mechanism question unchanged: what tick shape leaves CandleBuilder unable to
close a bar while the canary counts 30–60 ticks? **Ledger row H49 added in this PR.**

### 6.3 A2-1 `kite_last_seen_at` — first real pass RAN

`marketdata.instruments.kite_last_seen_at` is now stamped on **135,348 of 317,841 rows** after
this morning's 08:30 instrument sync (59k-row class). DEPLOYED-NOT-EXERCISED → EXERCISED; the
dark H26 A2 identity plumbing is accumulating live data.

### 6.4 09-03 evening chain (inherited watches) — ALL CLEAN

EVENING_CHAIN canary DONE 18:59 · bhavcopy 09-03 **3,490 rows** · MARKET_CONTEXT_DAY 09-03 row
present · MINERVINI_PLANE_DIVERGENCE DONE 18:48 · NOTIFIER_HEALTH DONE (09-04 08:32) ·
INGEST_COVERAGE DONE 08:45 today. (The N2 partition-counter first-live-reading and NEW-14
closure were already recorded in 09-03 §9 — PR #1578, still open at analysis time.)

### 6.5 Mechanical pre-checks

- `tools/ledger-consistency-check.py`: **12 REVIEW lines — the identical standing set** of
  08-26…09-03 (7×[A] chip open/closed keyword pairs, 5×[B] pickup-vs-DONE keyword class). No
  edits made; ledger consistent modulo the standing set.
- `tools/published-config-drift.py`: **69 published — 69 matched (45 clean, 24 drifted = the
  standing #1075 disabled-scalper drafts), 0 DB-only, 0 YAML-only.** Nothing republished.

### 6.6 H31 day-context — 6th consecutive clean day, durations re-measurable

Refresh durations 316 ms–3.9 s across the session (log-measured again after two dark days), no
FAILED counter movement, phase margin intact.

## 7 Tuning candidates

Ledger §0 group G/H is the authoritative status; nothing applied by this run.

| # | knob | status | today's evidence |
|---|---|---|---|
| NEW-15 (09-03) | per-contract tick-agg bar-closing sparse | **ESCALATED → ledger H49** (recurred on a NON-expiry, clean-network day: 13 REDs / 11 contracts incl. 3 NFO) | §6.2 |
| log barrier (09-01) | strategy-signal docker log | **PARTIALLY CLEARED — today readable via `--tail ≤5000`; `--since` still dead; recreate proposal stands at reduced urgency** | §6.1 |
| NEW-9 (08-26) | 60m-bias veto | **OPEN — day 8: largest sole-blocker set yet (10 deduped legs), ALL corroborated losers; tally 25 losers vs ~6 winners** | §5.3 |
| NEW-6 (08-19, reopened 08-31) | unpaired opening-bucket canary WARN | **CLEAN today — first observably clean opening bucket since 08-31** (09-02 had one, 09-03 unknowable); only WARNs today are a benign ± pair | §4 |
| NEW-13 (09-01) | recurring host outbound-network death | **OBSERVATION (owner/ops) — carried**; today clean (8th consecutive overnight downtime, 0 breaker transitions all day) | §4b |
| NEW (09-04) | boot-race crash: `paperStrategyScopeGuard` fail-fast DB probe has no retry — first start attempt dies when Postgres is still recovering; docker restart self-heals | **OBSERVATION — noted, not proposed** (benign self-heal, but a `RestartCount=1` morning is now expected noise after host downtime) | §4b |
| NEW-10 (08-27) | risk-limit base = current equity | **OBSERVATION (owner) — carried**; no trip (zero-fire) | — |
| watch | `strike-pick` chain-proximity | **WATCH — post-BSE-expiry Friday saturated again: 354 fails / 15 SENSEX slugs / 0 NIFTY** (Friday series 550/374/350/14/65/**354** — 4 of 6) | §2 |
| NEW (08-04) | mid-session deploys | **PROPOSED — carried**; no deploy today | — |
| NEW-3 (08-12) | `daily_profit_target` 1.5% | **OBSERVATION (owner) — carried** | no trip |
| NEW-1 (08-05) | paper heat-cap margin timeout | **PROPOSED — carried**; not evaluable (zero-fire) | §5.1 |
| T30 | `breadth` dot `>32` | **OPEN** — side-split partial today (CE 74.5% crossing, PE 0/270); no boundary pin | §3 |
| T29 | exit model dominates entry gate | **OPEN (exit-band track)** — chop day #10 again stop-favouring: challenger stops −₹77…−₹607 vs champion holds −₹1,122…−₹1,198/trade on the same leg family | §5.2 |
| T27 | relative-floor window | **OPEN; arming rec unchanged (NO)** | binding 56.8%, banded 43 thresholds; loosening ledger **56/45/11** — today added 7 losses |
| T28 | `atmIv` frozen daily stamp | **OPEN** | fresh stamp 0.102835 inside the band → `iv_abs_band` 100% 2nd day — the coin-flip's other face persisting |
| T3 | `iv_pair` | **OPEN (owner)** | 0% (34th session) |
| T23 | partial-bucket tolerance | **OPEN** | 2 WARNs + 0 straddles, benign ± pair (23 lots), measurable again | §4 |
| T1 / T7 | `relativeVolumeMultiplier` / composite threshold | **REJECTED — carried** | challenger books all deeper negative today |
| NEW-8 (08-24) | swing governor watch | **STANDING — 10th measurement**: both catch-up batches DONE (session 09-03), 0 entries — books capacity-bound 5th session | §6.4 |
| T8/T26 | latency | **OPEN (data) — p50 1:21.5, 2nd consecutive reading above the 1:18–1:20 band** (n=32) | §5.2 |
| T2 | `iv_rank` | carried, not open | NULL 1,341/1,341 |

## 8 Honesty caveats

- **Regime: CHOP, from the CONTINUOUS session** (o 23,910.90 → continuous freeze 23,937.90 pinned
  15:15–15:27; range 23,895.85–24,005.75; net +0.11% on 0.46%, **eff 0.246 = chop**). Official CAS
  print 15:28 **−40.20** → close 23,897.70 (official read −0.06%/0.46%, eff 0.120 — chop on both
  reads, no doctrine tension). **G11's chop count 9 → 10** — the row is DECIDED (owner 07-31,
  KEEP); today's stop-favouring evidence is corroboration, not a gate.
- **This run executed ~15:50–16:45 IST, before the evening chain**: today's bhavcopy EOD, screens,
  settles, market-context write and canaries had not run — tomorrow's run inherits them.
  (Yesterday's chain is verified clean in §6.4.)
- Today's log-derived numbers come from a **`--tail 5000` snapshot** (§6.1) — the `--since` read
  path remains silently broken on strategy-signal; any future grep using `--since` on that
  container returns zero rows and must not be read as a quiet session.
- Shadow figures are fan-out counts (§3.24); deduped clusters in §5.2. The veto counterfactuals in
  §5.3 are corroborated by shadow rows (same legs, real chain exits), not hand-priced premium
  paths.
- Read-only run: SELECTs, log/tail reads, `docker inspect`, in-container health GETs. No restarts,
  deploys, writes, config changes, republishes. Docs-only PR: this file + rollup rows + ledger
  H49.
