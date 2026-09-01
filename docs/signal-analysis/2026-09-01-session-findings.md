# Session findings — 2026-09-01 (data date)

Analysis date: 2026-09-01 EOD (scheduled post-market agent, run ~18:52–19:20 IST). Analyst: Claude
(scheduled `session-analysis post`). Data: `signal_rejections` rows **678** (bounds
`2026-09-01T09:15:00+05:30`…`15:40`; rows 09:19:07–11:55:27), signals fired **0** (fired evals 0,
no suppressions), paper trades **0 entries / 0 closes**, shadow champion **8 closes +₹25,027.16 —
and 7 rows STRANDED OPEN by the outage** (§5.2).

Session character: **Tuesday, NSE-weekly expiry DAY-OF (Sep-03 BSE weekly Thursday)** ·
**TRUNCATED SESSION — the host's outbound network died ~11:54 IST and the box was then shut down
12:42–18:45** (§6.1): live coverage is 09:15–11:55 only (~160 of 375 session minutes, ~43%);
everything after 11:55 — capture, evaluation, shadow exits, the 15:12 square-off — never ran ·
signal contract **`NFO:NIFTY26SEPFUT`** (log-confirmed) · regime **chop on the OFFICIAL read
(eff 0.276), continuous close UNMEASURABLE — PROXY stamp, not a G11 observation** (§8) ·
**2nd fully-automatic Kite login, first ever via the 08:05 cron slot itself** (§4b) · owner
disabled `scalp-golden-crossover-sensex-niftyoi-pe` on 08-31 18:48 IST → fleet is now **37**
enabled published scalpers (boot line 37/0/0).

## 1 Funnel numbers

| metric | value |
|---|---|
| rejections | 678 — **35 of 37** enabled scalpers (the 2 absent are `scalp-hero-zero-nifty` / `scalp-hero-zero-sensex-niftyoi`, whose windows sit in the lost afternoon; `premium_skew`-bearing rows n=0 for the same reason) |
| eval outcomes | chart-gate-failed 814 · confluence-blocked 678 · composite-below-threshold 84 · **fired 0** · discipline-paused 0 |
| fired reconciliation (§3.36) | 0 fired = 0 emitted + 0 suppressed |
| coverage | **all 11 populated 15-min buckets 09:15–11:45 in the live window; rejections end 11:55:27** — the last confluence pass before the chain fetch started failing (`chain-unavailable` ×16, all stamped 11:55). Eval buckets then wrote **zeros 11:57→12:39** (rollup thread alive, feed dead) and stopped at the 12:42 shutdown. `subscriber_health_events` 0 rows (absence proves nothing — §4.3) |
| boot health | morning boot 07:50 IST; reload 0/37-unresolved transient at 07:50:48 → **37/0/0 at 08:05:35** (healed at the moment of login; ~15 min transient, pre-open, harmless) |
| §3.30 freeze telemetry | **explicit zero: `paper_events` OPENED count 0** — 0/5 sub-accounts entered, zero-fire session |

## 2 Rail findings

- **volume-floor first-block 465/678 (68.6%)** — banded and honest (22 distinct thresholds,
  23,595–85,263.75, zero flat); `relative-volume-floor` armed 37/37 (drift script clean).
- **`strike-pick` NSE-weekly DAY-OF: 280 all-fails, 17 NIFTY-rooted slugs, 0 SENSEX** — the
  Mon/Tue NSE cluster window delivered after Monday came in mild: series now
  235/604/322/452/531/…/14 (Mon eve)/**280 (day-of, truncated session — the true full-day count
  would be higher)**. Consistent with the §3.27 chain-proximity read.
- **`chain-unavailable` 16 first-blocks, ALL at 11:55** — the network death seen from inside the
  gate; the last diagnostic before the feed went dark, not a chain defect.
- First-block tail: time-window 86 · rsi-band 14 · volume-pump 10 · pct-price-move 10 ·
  time-of-day-preference 10 · divergence-vol-gate 10 · open-high-low 10 · two-candle 10 ·
  option-side-constraint 7 · supertrend-15m 6 · max-oi-sr-gate 6 · confluence-composite 6 ·
  oi-slope-agree 4 · oi-divergence-magnitude 4 · oi-cross-required 2 · directional-change-gate 2
  (18 distinct rails).
- **confluence-composite all-fails split (§3.39): 386 `60m bias opposes the side`
  (composite 0.3431–0.9043) + 171 score-shortfall aggregates.** Sole-blocker veto set: §5.3 —
  day 5, and the veto is back to refusing a loser.

## 3 Composite + dots

- **OI bloc fully LIVE** (weekly expiry — no S24 suppression, correctly): quadrants NEUTRAL
  **0/557**, spurt NULL 0, basis LIVE 557/557. futures_oi capture 10,971 snaps / **159 of 159
  live-window minutes** (09:15–11:54 — full until the outage).
- **Composite passes 204 of 557 scored (36.6%) — ALL CE, 0 PE**; max 0.9043. An all-CE pass day
  (up-drift morning), the mirror of 08-20.
- Live-dot support (LIVE WINDOW ONLY, n=557 unless noted — §3.21 partial-session caveat applies
  to every rate here): `breadth` 0% (both sides — see below) · `iv_abs_band` 0% (n=76, **9th
  day** — atmIv stamp 0.099408 below the 10–12 band) · `iv_rank` 0% (withheld, standing) ·
  `iv_pair` 0% (**31st** — T3, owner) · oi_spurt 10.8% · volume 16.9% · vwap 43.1% ·
  underlying_oi 45.1% · futures_oi 50.6% · trending_cross 54.2% · iv_slope 55.3% (n=76) ·
  sentiment_slope 56.4% · rsi 67.0% · basis 69.3% · sentiment 73.6% · psar 84.6% · vix 85.3% ·
  drastic_oi 92.8% · vwma 98.7% · supertrend 100% (free) · premium_skew absent (n=0, its
  carrier rows live in the lost afternoon).
- **§3.28 breadth (T30) — BOTH sides at 0% on a MID-RANGE operand:** CE 0/386 (advances 20–26)
  and PE 0/171 (declines 24–28... 30) — neither side's count ever cleared `> 32`. Not the
  saturation shape of 08-31 (one side free, one dead); today the whole market sat mid-range and
  the dot was dead weight for EVERY row — the per-session step function's third face.

## 4 Data health

| field | today | verdict |
|---|---|---|
| `ivRank` | NULL 678/678 | dead-data, standing (since 07-02) |
| `dowUp` | NULL 678/678 | by design (un-armed) |
| `fiiLongPct` | live on all 557 contextful rows | healthy |
| `atmIv` | 1 distinct (0.099408) | frozen daily stamp — correct (G12/T28, 27th) |
| vix | 9 distinct | alive |
| misaligned 1m candles (§3.15) | **0 rows** | clean — 11th consecutive |
| §3.17 canary | **1 WARN + 0 straddles** — 09:19:15 IST, opening bucket 09:15, shortfall **−1,365 = 21 NIFTY lots, 1.8%** of 75,270, UNPAIRED | boot-fresh opening-bucket/first-event territory but NO partner ever appeared — logged under the NEW-6 watch (§6.5), small |
| signal-future capture | live KITE bars to **11:54** + 1 TICK_AGG 11:55; the afternoon's 169 bars are a **post-boot REST backfill fetched 18:49:21** (§3.35 provenance trap — the 375/375 count is NOT live coverage) | outage-truncated |
| options chain capture | 475,044 snaps, last **11:54** | outage-truncated |

## 4b BOOT WINDOW (§3.41) — CLEAN; second fully-automatic login, first via the cron slot

All IST. **Circuit-breaker transitions in the boot window: ZERO. Capture minutes lost overlapping
the window: ZERO** (session valid 70 min before the open).

- **07:48:25** — host boots (overnight power-off 01:29–07:48, 5th consecutive overnight
  downtime).
- **07:50:14** — market-data's FIRST start attempt **crashed** (`Error creating bean
  'bhavcopyStartupCatchup'` — DB-not-ready race against the still-booting timescaledb; the
  container restart policy relaunched it and **07:50:34's second attempt came up clean**).
  Benign, self-healed in 20 s, but it is the first observed instance of this boot race.
- **07:50:43** — `kite session restore: persisted token from 2026-08-31 … expired … NOT
  resumed` — #1520 refusing a dead token, 2nd consecutive morning.
- **07:50:48** — boot catch-up correctly declines (`07:50 … outside the 08:00-15:30 window`) —
  the FIRST morning the box booted AHEAD of the 08:05 cron slot, so for the first time the CRON
  path itself was exercised.
- **08:05:00** — cron auto-login fires: authorize hop 1 = 302 to `connect/finish`, hop 2 carries
  the request_token (#1518), `kite session status -> CONNECTED`, ticker connected. **Login
  latency inside the slot: <1 s.**
- **08:05:35** — engine reload heals the boot transient: 0/37 unresolved → **37/0/0**.
- Nothing else in the window; first live tick/bar flow normal from the 09:15 open.

## 5 Shadow outcomes + counterfactuals

### 5.1 Funded book

Zero entries, zero closes (composite passed on 204 rows, all CE, but nothing survived the
rails/veto before the session died). §3.34 heat-gate not evaluable (no funded fire). N23-A
outlay-shadow and N26 veto-audit remain unexercised — still on the "verify on first funded
fire" list.

### 5.2 Shadow book — one TP leg carries the day; 7 positions stranded OPEN by the outage

**Champion: 15 opened, 8 closed, +₹25,027.16 net (6 net wins)** → 4 deduped
`(bar, leg, entry)` clusters:

| bar | leg | entry | outcome |
|---|---|---|---|
| 10:51 | SENSEX2690376500CE | 629.95 | **TAKE_PROFIT ×6, +₹26,909.52** — a rare shadow TP cluster; the whole day's net is this one leg (fan-out ×6) |
| 11:09 | SENSEX2690376600CE | 618.35 | **2 rows OPEN — stranded** |
| 11:30 | SENSEX2690376800CE | 627.40 | 1 STRUCTURAL_STOP −₹1,608.36 + **5 rows OPEN — stranded** |
| 11:45 | SENSEX2690376700CE | 647.85 | STRUCTURAL_STOP −₹274.00 |

⚠️ **The 7 OPEN rows are outage-stranded**: `ShadowExitMonitor`'s brackets, structural stops and
the 15:12 square-off all stopped when the feed died at 11:54 and the box went down at 12:42.
They were still OPEN at this run's close (~19:15 IST); expect them to resolve as STALE
prior-day leftovers on the next session and **exclude their eventual P&L from any per-session
attribution** — their exits were never managed. Effective independent sample today: ~4 bar
times, sign carried entirely by one leg. All-time champion **−₹296,541.43** (802 closes, 285 net
wins). Entry latency p50/p95 unchanged (structural class).

**Challenger-only class: 5 observations, 0 wins, −₹2,520.10** — all the SAME deduped leg family
(`SENSEX2690376700CE` 11:33/11:36/11:45: composite-055 −₹559.18, vol-12k5 −₹559.18, vol-off
−₹559.18/−₹568.56/−₹274.00). **Loosening ledger 37/29/8 → 42 measured / 34 losses / 8 wins.**
All-time: composite-055 **−₹16,649.81** · vol-12k5 **−₹43,849.37** · vol-off **−₹69,678.79**.
REJECTED statuses stand.

**Per-rail counterfactual P&L (owner directive 08-20), all-time champion NET:** volume-floor
424 / **−₹184,733.32** · rsi-band 103 / −₹70,254.44 · call-put-delta-filter 7 / −₹11,149.59 ·
morning-opening-formation 4 / −₹9,629.13 · two-candle 38 / −₹8,818.79 · max-oi-sr-gate 9 /
−₹6,998.85 · divergence-vol-gate 37 / −₹6,375.60 · **`confluence-composite` 20 / +₹7,148.76
(no new closes with that first-rail today)** · pct-price-move 48 / +₹6,925.42 · oi-cross-required
29 / +₹1,155.04 · supertrend-15m 11 / +₹1,278.11. Today's movement is the single 10:51 TP leg,
whose +₹4,484.92 per-row P&L lands once in each of SIX different first-rail buckets
(directional-change / divergence-vol / oi-cross / pct-price-move / two-candle / volume-pump) —
the §3.13/§3.24 fan-out caveat in its purest form. **Root split: SENSEX −₹65.33/trade (359) vs
NIFTY −₹616.45 (443)** — same direction 3rd time running; SENSEX improved −₹138 → −₹65.

### 5.3 §4.2 counterfactuals — the 60m-bias-vetoed set (day 5, NEW-9): back to refusing a loser

Sole-blocker veto set: **1 row** — 11:33 `SENSEX2690376700CE` @690.75, composite 0.6649, CE.
**Challenger-corroborated LOSS**: all three challenger books took exactly this leg and closed it
**−₹559.18 each** (structural stop, pre-outage); vol-off's two later entries on the same strike
lost −₹568.56 / −₹274.00. The chain path corroborates: LTP 690.75 (11:33) → 634.60 by 11:52
(−8.1% at data truncation).

**Veto ledger after 5 days: 13 losers refused (d1–3, d5) vs ~6 winners refused (d4, ≈+₹48.5k).**
Still two-sided; keep accumulating, no proposal either way.

## 6 New data points / anomalies

### 6.1 TRUNCATED SESSION — host network death 11:54, shutdown 12:42, down till 18:45 (environmental)

The full chain, `computed` from the Windows System log (events 6005/6006/6008) + service logs:

- **~11:54:30 IST** — the host's OUTBOUND NETWORK died: `ws.kite.trade` connect failures +
  `api.kite.trade` I/O errors + `liveindexsa.niftyindices.com` I/O errors within the same minute
  — three independent destinations, the same multi-destination fingerprint as the 08-19/08-20
  outages (host up, network dead). Last live artifacts: futures_oi 11:54, chain 11:54, 1m bar
  11:54/11:55, rejection 11:55:27. `kite-rest` breaker CLOSED→OPEN 11:55:24 naming
  `ResourceAccessException` (#1512 working), then OPEN↔HALF_OPEN every ~31 s until shutdown.
- **12:42:17** — clean host shutdown (event 6006). The stack was up but blind for the last
  ~48 min.
- **18:45:33** — host boots; **crashes almost immediately** (event 6008 "previous system
  shutdown … was unexpected"); **18:47:43** second boot sticks; containers up 18:48:59.
- Net loss: live capture + evaluation 11:55–15:30 (~215 session minutes), the 15:12 square-off,
  the 16:05 prefetch, the 18:20 upstox-canary, and the 18:45–18:48 evening-chain slots.

**Classification: environmental outage (stack-outage register class), not a platform defect.**
Every in-stack component behaved correctly around it. This is the 5th host-network event since
08-19; the pattern (recurring outbound-network death on this box) is an ops problem no in-stack
canary can page about while alerting itself rides the same network (§6.4).

### 6.2 Evening chain: boot catch-up RESCUED the batch — one job lost (MARKET_CONTEXT_DAY)

The 18:47 boot landed 2 minutes into the evening chain window, and the catch-up design carried
it: **BHAVCOPY boot catch-up at 18:49:21 ingested NSE 3,495 + BSE 5,005 rows — 2.5 minutes
BEFORE the 18:52 settle**, so the settles ran on-time with FRESH bars (the H27 ordering
accident going the right way). Job-by-job vs the 08-31 baseline:

| job | outcome |
|---|---|
| BHAVCOPY (18:45 slot missed) | **recovered via boot catch-up 18:49:21** — NSE 3,495r/2,864c + BSE 5,005 |
| NSE_FII_DII / PARTICIPANT_OI / FII_DERIVATIVE | recovered 18:49:21–22 (60/5/120 rows) |
| MANAS_SCREEN / MINERVINI_SCREEN | ran 18:49:58 / 18:50:33, both `[bhavcopy-complete]` (2,291 / 1,804 rows; minervini 300 pass, plane-divergence 134/300, 40 served) |
| minervini settle 18:52 | **on time: 0 candidates, 0 entries, 0 exits, 0 exit-skipped** + 14 sell-decisions (H27: judge on exit-skipped=0, not exit count) |
| manas settle 18:53 | on time: 0/0/0 + 7 sell-decisions |
| heartbeat-swing 18:54 / graduation 18:55 (43 eval, 0 grad) / insights 18:56–18:57 / BHAVCOPY_CLOSE 18:58 (217) | all ran |
| **MARKET_CONTEXT_DAY (18:48 slot)** | **MISSED — no catch-up path exists; today's market-context row is absent.** One-day gap; tomorrow's 08:45 ingest-coverage canary should flag it |
| OPTIONS_SNAPSHOT_PRUNE (18:04) | missed (benign housekeeping) |
| bhavcopy-close canary | **YELLOW: 6 of 217 symbols diverge >1%** (SOTL 1.47%, SAIL 1.26%, …) — noted, likely CAS-vs-quote timing; watch tomorrow |

### 6.3 Post-boot network still PARTIALLY dead — alerting channels DOWN this evening

As of ~19:05 IST the reboot has NOT fully restored the network: Kite REST + NSE endpoints work
(bhavcopy, screens, historical all fine), but **`api.telegram.org` fails every ~33 s ("Request
cancelled"), `ntfy.sh` times out, `liveindexsa.niftyindices.com` fails, and the Kite WS ticker
is flapping** (5+ disconnects 18:55–18:57). **Both paging channels (ntfy + Telegram) are down
right now** — if anything breaks overnight or at tomorrow's open before this heals, no page will
arrive. The 18:54 hc-ping heartbeat DID get through, so the degradation is selective, which is
also the 08-19 fingerprint. **Watch item for tomorrow's open run: confirm telegram/ntfy WARNs
stopped.**

### 6.4 Hard-shutdown log corruption — a NEW forensics trap (process note)

The 18:45 unexpected shutdown corrupted both services' json log files: **forward reads
(`docker logs`, and `--since`) silently STOP at the corruption barrier (~12:41 IST) and return
nothing newer**, while small `--tail N` reads (N below the post-boot line count) DO reach the
post-boot segment. A naive `--since <boot>` read returns ZERO lines and reads exactly like "the
service logged nothing since boot". Workaround used tonight: probe `--tail` sizes until the
first timestamp crosses the boot, then sort in-process. Related standing rule
(`deploy-destroys-container-logs`) covers recreates; this is the hard-shutdown sibling.

### 6.5 §3.17 canary — 1 unpaired WARN, opening bucket, small

09:19:15 IST, bucket 09:15: 3m 75,270 vs Σ1m 73,905, shortfall −1,365 (21 lots, 1.8%),
UNPAIRED, no partner, no straddle all day. First-event lot-cache-miss territory (boot-fresh
process, day's first event) but that class normally shows a ± pair; no partner ever appeared.
Small magnitude, opening bucket, rails unaffected (broker-corrected side). Logged under the
NEW-6 watch reopened on 08-31 (which had a 64-lot/23.9% event — today's is far milder).

### 6.6 Pre-outage canary RED on two BFO strikes — unexplained, 4 min before the network death

11:50:54 IST: `data canary RED: BFO:SENSEX2690376100PE/CE — ticks flowing but no 1m bar closed
for 1789s` (bars stopped ~11:21). Possibly sparse trading on those strikes, possibly an early
symptom of the degrading network; the outage 4 minutes later makes it unverifiable. Not
escalated; noted for pattern-matching if it recurs on a clean day.

### 6.7 H31 day-context — 4th consecutive zero-failure session (partial-session caveat)

`insight trust read day-context FAILED` grep over the live window + post-boot sweeps: **0**
(trajectory 89% → 18% → 0% → 0% → 0% → 0%). Caveat: only ~43% of a session's sweeps ran. Owner
set the closing bar at 10 clean sessions (#1552); whether a truncated day counts is the ledger
keeper's call — evidence recorded here either way.

### 6.8 Owner fleet change

`scalp-golden-crossover-sensex-niftyoi-pe` disabled 2026-08-31 18:48:48 IST (registry
`updated_at`); boot line and drift script agree (37 loaded; armed `take_profit premium_pct`
count 36 → 35, `signal_exit` 38 → 37). §3.29 never-fired set otherwise unchanged: `take_profit
premium_pct` (35 armed, zero funded TP closes — 2+ months) · `signal_exit` (37) · `square_off`
(2) · tag `oi-confluence-exit` (8). INDETERMINATE standing pair unchanged
(`trailing_stop`/`stop_loss` `atr_multiple`, 2 each). Fired vocabulary delta today: none (zero
funded closes).

### 6.9 Swing book still capacity-bound (NEW-8, 7th measurement)

The 08:35 catch-up (for the 08-31 session) took **0 entries: 7 `pyramid_risk_cap` blocks**
(SUVEN, RRKABEL, TMB, ICIL, TDPOWERSYS, LOKESHMACH, APCOTEXIND) **then `max_open_paper_positions`
12/12** (risk_audit 08:35:10–08:36:00). Governors normal; book fully capacity-bound, 2nd
consecutive session.

### 6.10 Mechanical pre-checks

- `tools/ledger-consistency-check.py`: **12 REVIEW lines — the identical standing set** of
  08-26…08-31 (7×[A] chip open/closed keyword pairs, 5×[B] pickup-vs-DONE keyword class). No
  edits made; ledger consistent modulo the standing set.
- `tools/published-config-drift.py`: **69 published — 69 matched (45 clean, 24 drifted = the
  standing #1075 disabled-scalper drafts), 0 DB-only, 0 YAML-only.** Nothing republished by
  this run.

## 7 Tuning candidates

Ledger §0 group G is the authoritative status; nothing applied by this run.

| # | knob | status | today's evidence |
|---|---|---|---|
| NEW-13 (09-01) | recurring host outbound-network death + shutdowns during market hours | **OBSERVATION (owner/ops)** — 5th multi-destination network event since 08-19, this one truncating a session at 11:54 and costing the afternoon; in-stack alerting rides the same network (telegram+ntfy down post-boot, §6.3), so only the off-stack hc-ping layer can see it | §6.1/§6.3 |
| NEW-9 (08-26) | 60m-bias veto | **OPEN — day 5: 1 refused, challenger-corroborated LOSER (−₹559.18)**; tally 13 losers vs ~6 winners refused | §5.3 |
| NEW-6 (08-19, reopened 08-31) | unexplained unpaired canary WARNs | **OPEN — 1 today (opening bucket, −1,365 = 21 lots, 1.8%, unpaired)** — mild vs 08-31's pair; keep the watch | §6.5 |
| NEW-12/H44 residual | first funded fire under the re-centred band | **carried** — zero-fire again; N23-A + N26 also still unexercised | §5.1 |
| NEW-10 (08-27) | risk-limit base = current equity | **OBSERVATION (owner) — carried**; no trip (zero-fire) | — |
| watch | `strike-pick` chain-proximity | **WATCH — day-of came in at 280 fails / 17 NIFTY slugs / 0 SENSEX on a HALF session** (series 235/604/322/452/531/…/14/280) | §2 |
| NEW (08-04) | mid-session deploys | **PROPOSED — carried** | none today |
| NEW-3 (08-12) | `daily_profit_target` 1.5% | **OBSERVATION (owner) — carried** | no trip |
| NEW-1 (08-05) | paper heat-cap margin timeout | **PROPOSED — carried** | unexercised |
| T30 | `breadth` dot `>32` | **OPEN — third face: BOTH sides 0% on a mid-range tape** (CE adv 20–26, PE decl 24–30, never >32) | §3 |
| T27 | relative-floor window | **OPEN; arming rec unchanged (NO)** | binding 68.6%, banded; loosening ledger **42/34/8** — today added 5 losses (one vetoed leg family) |
| T28 | `atmIv` frozen daily stamp | **OPEN** | 1 distinct = 0.099408 (27th); `iv_abs_band` 0% 9th day |
| T3 | `iv_pair` | **OPEN (owner)** | 0% (31st session) |
| T23 | partial-bucket tolerance | **OPEN** | 1 WARN + 0 straddles (§6.5) |
| T1 / T7 | `relativeVolumeMultiplier` / composite threshold | **REJECTED — carried** | challenger-only today: 0 wins / 5 losses −₹2,520.10 |
| NEW-8 (08-24) | swing governor watch | **STANDING — 7th measurement, capacity-bound 2nd session** (7 pyramid blocks + 12/12 cap) | §6.9 |
| T8/T26 | latency | OPEN (data) — no emissions | — |
| T2 | `iv_rank` | carried, not open | NULL 678/678 |

## 8 Honesty caveats

- **This is a ~43% session.** Every §3 rate, dot support %, and the strike-pick day-of count are
  live-window (09:15–11:55) figures — §3.21's partial-session caveat applies to all of them;
  none of today's rates belong in a cross-session tuning argument without that label.
- **Regime is a PROXY stamp:** official 1d bar (KITE, backfilled 18:49) o 24,077.55 → c 24,114.50
  (+0.15% on 0.55% range, eff **0.276 = chop** on the OFFICIAL read). The CONTINUOUS close is
  unmeasurable — index 1m capture died 11:55 and the only later bar is the §3.35 post-boot
  snapshot artifact (15:29, 24,055.80 — not trusted). Doctrine (§3.33a) stamps from the
  continuous session, which does not exist here: **recorded as chop-PROXY(official-only); NOT a
  G11 observation (chop count stays 8); no CAS-delta series entry** (unmeasurable).
- The afternoon 375/375 signal-future bar count is a post-boot REST backfill (`fetched_at`
  18:49:21) — §3.35: do not read it as live coverage.
- Shadow: the day's sign rides ONE leg (10:51 TP ×6); 7 stranded-OPEN rows will settle STALE
  later and must not be re-attributed to this session. Champion figures are fan-out counts.
- §5.3's corroboration reuses the challenger books' own engine-exit fills (same entry to the
  paisa); the chain path is 2–3-min LTP granularity, no slippage/fees.
- §3.17/§3.34/§3.36 log greps ran against a corrupted log file (§6.4) — the live-window greps
  used forward reads that ARE complete up to the 12:41 barrier, so the 1-WARN count for the live
  window is trustworthy; post-boot counts came from tail reads.
- Read-only run: SELECTs, log reads, `docker inspect`, Windows event log. No restarts, deploys,
  writes, config changes, republishes. Docs-only PR: this file + rollup rows.
