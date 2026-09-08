# Session findings — 2026-09-08 (data date)

Analysis date: 2026-09-08 (scheduled post-market agent, run ~15:50–16:40 IST — before the 18:45+
evening chain, §8). Analyst: Claude (scheduled `session-analysis post`). Data: `signal_rejections`
rows **1,136** (bounds `2026-09-08T09:15:00+05:30`…`15:40`; rows 09:19:07–15:19:18), signals fired
**2 entries + 2 exits** (4 signal rows), paper trades **2 filled / 2 closed, funded net
−₹2,230.19 (0W/2L)**, shadow champion **8 closes, 2 net wins, −₹672.90**.

Session character: **Tuesday, NSE NIFTY weekly expiry DAY-OF (front weekly 09-08 expiring)** ·
gap-flat open 23,743.10 → all-day slide to a 23,640.05 continuous close (low 23,623.10) · regime
**TREND-DOWN (continuous eff 0.759; official 0.795 — trend on BOTH reads; CAS delta −4.95, the
smallest measured)** · VIX 10.98–11.33 · signal contract **`NFO:NIFTY26SEPFUT`** (named by the
fired signals' own rows) · PE-only composite day: 70 passes, ALL PE (max 0.8627; CE max 0.5585) ·
**LATEST boot yet — 08:49:42 IST, PAST the 08:05 login slot and past every 08:30–08:45 morning
cron; everything caught up on boot** (7th consecutive fully-automatic login, §4b) · **`iv_pair`
supported for the FIRST TIME EVER (4 rows, 14:12) after 35 sessions at 0%** (T3, §3).

## 1 Funnel numbers

| metric | value |
|---|---|
| rejections | 1,136 — **35 of 37** enabled scalpers emitted rows; the missing 2 (`scalp-golden-crossover-nifty`/`-sensex-niftyoi`, the CE pair) evaluated **105× each, every eval `chart-gate-failed`** (V053 denominator), so coverage is genuinely FULL |
| eval outcomes | chart-gate-failed 2,013 · confluence-blocked 1,136 · composite-below-threshold 294 · **fired 2** · discipline-paused 0 |
| fired reconciliation (§3.36) | **2 fired = 2 emitted = 2 FILLED** — zero suppressions, zero governor refusals |
| coverage | **25 of 25** populated 15-min buckets 09:15–15:15 (thinnest: 09:15 at 8, 12:45/13:00/13:15 at 8 each — midday chart-gate lull); rejections end 15:19 |
| boot health | boot **08:49:42 IST** (10th consecutive overnight host downtime — LATEST boot yet), `RestartCount=0` BOTH services (the 09-04/09-07 DB-not-ready bean-race crash did **not** recur); engine reload 0/37-unresolved transient at 08:49:57 → **37/0/0 at 08:50:58** (~61 s) |
| §3.30 freeze telemetry | **2 of 5 subs — NO flag** (needs ≥3): subs 1/2 took 1 entry each (12:07, 13:46), both first-loss-frozen at close of their trade (12:37, 14:16). Day PnL: sub 1 −₹1,574.47 · sub 2 −₹655.72. Subs 3–5 never saw a fire. `discipline-paused` 0 |

## 2 Rail findings

- **volume-floor first-block 681/1,136 (59.9%)** — banded and honest (all-fails avg operand 13,491
  vs avg threshold 26,681); `relative-volume-floor` armed **37/37** (§3.14 registry check clean).
- **`strike-pick`: 468 all-fails, ALL NIFTY-rooted (16 slugs), ZERO SENSEX — NSE weekly DAY-OF
  saturation** (§3.27 Mon–Tue NIFTY cluster; day-of series 604 (08-04) / 280 (09-01, half session) /
  **468** today). The 5 sole-blocker `strike-pick` rows in §5.3's would-have-fired set are this
  class: NIFTY slugs whose composite passed but whose expiring-today front weekly priced outside the
  delta/premium band, while the SENSEX siblings resolved and funded.
- **confluence-composite all-fails 827 = 96 `60m bias opposes the side` + 34 `stand-aside
  (both-IV-high 40/40 suppression)` + 697 score-shortfall aggregates (43 distinct 0.1064–0.5931)**
  (§3.39 split — see §6.2 for the third reason class). ⚠️ **Every one of the 96 veto fails carried a
  composite (0.3723–0.5585) ALSO below the 0.6 threshold — the veto was fully REDUNDANT today**:
  all 70 composite passes were PE and the 60m bias pointed down, so the veto's sole-blocker set is
  EMPTY (NEW-9 day 10, tally unchanged).
- First-block tail: time-window 184 · rsi-band 81 · time-of-day-preference 38 · pct-price-move 24 ·
  two-candle 24 · volume-pump 24 · confluence-composite 18 · divergence-vol-gate 18 ·
  option-side-constraint 15 · oi-cross-required 14 · strike-pick 9 · psar-durability 6 (13 distinct).

## 3 Composite + dots

- **OI bloc fully LIVE**: quadrants NEUTRAL **0/897** (SHORT_BUILDUP 420 dominant — bearish tape
  read correctly · LONG_BUILDUP 350 · SHORT_COVERING 66 · LONG_UNWINDING 61), spurt NULL 0, basis
  live 897/897. futures_oi capture 25,806 snaps / **374 of 375 minutes**. (NSE weekly expiry does
  NOT trip S24 — that is monthly-only, and the data confirms no suppression.)
- **Composite passes 70 of 897 scored (7.8%) — ALL PE (max 0.8627); CE max 0.5585.** Fired evals
  scored 0.9379 (12:06) and 0.7926 (13:45).
- Dot support (n=897 unless noted): `iv_rank` 0% (withheld, standing) · **`iv_pair` 0.45% (4/897)
  — FIRST support in 36 sessions: one 14:12 bar, 4 PE slugs, reason `iv pair gap favors side`
  (T3: the dot is alive, just ~once-a-month rare)** · oi_spurt 7.7% · vwap 10.5% · basis 10.7% ·
  trending_cross 22.0% · volume 24.1% · breadth 29.2% · rsi 40.7% · futures_oi 49.2% ·
  sentiment_slope 54.9% · underlying_oi 59.9% · drastic_oi 63.2% · sentiment 65.9% · iv_slope
  67.7% (n=124) · vix 72.4% · psar 77.8% · vwma 89.3% · supertrend 93.1% · premium_skew 94.1%
  (n=34) · **`iv_abs_band` 100% (124/124) — flipped back IN: fresh atmIv 0.100875 (09-07's 16:00
  write) just inside 0.10; T28's 4th coin-flip in 5 sessions**.
- **§3.28 breadth (T30) — down-day split, softer than 09-07**: PE 262/801 (32.7%, declines 27–37
  straddling `>32` — actually crossing part of the session), CE 0/96 (advances 13–23, never).

## 4 Data health

| field | today | verdict |
|---|---|---|
| `ivRank` | NULL 897/897 | dead-data, standing (since 07-02) |
| `dowUp` | NULL 897/897 | by design (un-armed) |
| `fiiLongPct` | live on all 897 contextful rows | healthy |
| `atmIv` | 1 distinct (**0.100875 — FRESH, 09-07's EOD write**) | frozen daily stamp, correct mechanism (G12/T28) |
| vix | 13 distinct, 10.98–11.33 | alive |
| misaligned 1m candles (§3.15) | **0 rows** | clean — 16th consecutive |
| §3.17 canary | **2 WARNs + 0 straddles — BENIGN**: ±1,430 (22-lot) pair on consecutive buckets 12:06/12:09, BOTH reported UNPAIRED = the §3.17 (08-25) day's-first lazy-lot-cache shape, released at the documented deadline (12:16:24); day's only non-benign-candidate event. **NEW-6: 3rd consecutive clean OPENING bucket** | benign |
| signal-future capture | **375/375 1m minutes** (max 15:29) | healthy, full session — incl. across the 09:23 ticker blip (§6.3) |
| per-contract tick/bar divergence | **7 `data canary RED` events, 6 contracts** (NFO NIFTY 24200CE/PE 09:35; BFO SENSEX 76800CE/PE 09:40 592 s; SENSEX 74700CE/PE + NIFTY 23150PE 09:46 594 s) — all clustered 09:35–09:46, shortly after the 09:23 ticker blip | **H49 day 4** — see §6.3 for the new correlation |
| dot-health | consistent with row-level reads; no newly-dead dot | clean |

## 4b BOOT WINDOW (§3.41) — latest boot yet (08:49), past every morning slot; boot catch-ups rescued all of it

All IST. **Circuit-breaker transitions: ZERO all day. Capture minutes lost overlapping the
window: ZERO** (login complete 25 min before the open).

- **08:49:42** — containers start (10th consecutive overnight host downtime; **latest boot yet**,
  past the 08:05 auto-login slot AND the 08:30/08:35/08:45 morning cron slots). `RestartCount=0`
  on both services — the DB-not-ready bean-race crash of 09-04/09-07 did NOT recur.
- **08:49:53** — `kite session restore: persisted token from 2026-09-07 … expired at
  2026-09-08T06:00+05:30 — NOT resumed` (#1520's 7th refusal). No pre-open 403 storm today: the
  host was down when the token died, so nothing was there to flap (NEW-16's Monday shape not
  applicable — next observation is next Monday).
- **08:50:01** — auto-login boot catch-up: inside window, attempt in 20 s → **08:50:21 authorize
  hop 1 → 302 same-origin, request_token at hop 2 → CONNECTED 08:50:21, ticker connected
  08:50:22** (7th consecutive fully-automatic login, ~39 s after boot).
- Missed-cron catch-ups, all clean: notifier-health (`NOTIFIER_HEALTH` 09-08 DONE 08:49) ·
  ingest-coverage (`INGEST_COVERAGE` 09-08 DONE 08:49, swept 09-07) · **swing catch-up replayed on
  boot** (08:49:57, §6.4) · paper reconciliation deferred behind the in-flight swing batch, then
  ran clean 08:51:15 (7 positions + 2 taken signals, 0 discrepancies).
- **08:49:57 → 08:50:58** — engine reload transient 0/37-unresolved heals to **37/0/0** (~61 s,
  the normal shape; `unresolved==0` 24 min before the open).

Verdict: **clean — an explicit zero on outages, and the strongest late-boot test of the catch-up
lattice yet**: every 08:05–08:52 morning job was missed by the host and every one was rescued by
its boot path.

## 5 Shadow outcomes + counterfactuals

### 5.1 Funded book — 2 fills, 0W/2L, net −₹2,230.19

| pos | leg | slug | entry (IST) | exit | net |
|---|---|---|---|---|---|
| 115 | SENSEX2691076400PE ×20 @710.05 | connect-the-dots-sensex-niftyoi-pe | 12:07 | TIME_STOP 12:37 | −₹1,574.47 |
| 116 | SENSEX2691076300PE ×20 @628.05 | connect-the-dots-sensex-niftyoi-pe | 13:46 | TIME_STOP 14:16 | −₹655.72 |

Same T29 shape as 09-07 from the permissive side: PE entries RIGHT on direction (index slid all
day) and both TIME_STOPs cut them in mid-drift — the champion shadow row on the SAME 12:06
leg/entry that held to square-off in the 09:27 cluster family closed green (§5.2). §3.34 heat:
grep **0** on 2 funded entries; `margin_pct` 0.00 both (long-option no-SPAN shape — N23-A stands).
§3.40 settle: **0 refused** — both legs ticked live. Emit latency 19.0 s / 16.6 s (inside the
16.0–19.5 s measured band; 09-07's 19.5 s high-water stands). §3.29 delta: TIME_STOP +2;
**never-fired set unchanged** (take_profit premium_pct armed 35 / 0 closes since 07-01 ·
signal_exit 37 / 0 · square_off 2 / 0 · tag oi-confluence-exit→CONFLUENCE_FLIP 8 / 0).
INDETERMINATE standing: the two `atr_multiple` rows; `stop_loss` premium_pct/percent bases.

### 5.2 Shadow book — 8 closes, 2 net wins, −₹672.90

**Champion: 8 closes → 4 deduped `(bar, leg, entry)` clusters**, all SENSEX PE: 09:27
`SENSEX2691076400PE` @644.85 → SQUARE_OFF **+₹1,024.28** · 12:06 76400PE @710.00 ×5 fan-out →
**mixed SQUARE_OFF/STRUCTURAL_STOP, −₹1,894.76** (a §3.24 multi-exit cluster — same entry, exits
disagreed) · 13:48 76300PE @624.05 → STRUCTURAL_STOP +₹511.78 · 13:54 76200PE @574.30 →
STRUCTURAL_STOP −₹314.20. All-time champion **−₹413,094.02** (952 closes, 318 net wins). Entry
latency p50 **1:19.0** / p95 1:19.2 (n=9) — in the structural band.

**Challenger-only class: 1 observation, 1 WIN, +₹575.09** (59th measured loosening):
composite-055 11:51 `SENSEX2691076400PE`. **Loosening ledger 58/45/13 → 59 measured / 45 losses /
14 wins.** All-time: composite-055 **−₹25,166.20** · vol-12k5 **−₹52,306.03** · vol-off
**−₹83,938.24** — REJECTED statuses stand (2nd consecutive trend-down day flattering loosenings;
the books remain deep negative).

**Per-rail counterfactual P&L (owner directive 08-20), all-time champion NET, day movers:**
rsi-band 114 / **−₹74,824.81 (+₹1,024.28 — the 09:27 square-off winner was rsi-band-refused)** ·
two-candle 43 / −₹13,954.70 · divergence-vol-gate 42 / −₹11,511.51 · volume-pump 42 / −₹8,265.64 ·
oi-cross-required 30 / **+₹875.75 (gave back −₹279.29)** · `confluence-composite` 25 /
**+₹2,045.45 (unchanged — 0 new closes; the veto never bound a passing row today)** · volume-floor
524 / −₹261,189.78 (unchanged). **Root split: all 8 today SENSEX, −₹84.11/trade** — no NIFTY
shadow close (the NIFTY legs died at `strike-pick`, §2).

### 5.3 §4.2 counterfactuals — expiry-day would-have-fired set: the blockers were strike-pick + volume rails, not the veto

Sole-blocker set: **20 rows → 5 bars, ~4 deduped SENSEX legs + 5 NIFTY strike-pick rows (no
leg resolvable)**. The SENSEX legs are corroborated by the books' own rows on the same
`(bar, leg, entry)`: 12:06 76400PE (blocked variously by two-candle / pct-price-move /
volume-pump) — champion cluster −₹1,894.76 ×5 · 13:48 76300PE +₹511.78 · 13:54 76200PE −₹314.20 ·
12:09 76300PE (volume-pump/two-candle; no shadow row — dedup-suppressed). Net read: the day's
single-rail counterfactuals roughly wash (−₹1,697 across corroborated legs) — no tuning signal.
NEW-9 (veto) day 10: **sole-blocker set EMPTY** — the veto fired only on sub-threshold composites
(§2); tally stands **27 losers refused vs ~6 winners + 2 washes**.

## 6 New data points / anomalies

### 6.1 Log barrier unchanged — `--since` still returns ZERO lines; `--tail 4000` covers today

Tested today: `docker logs ay-strategy-signal-service --since 2026-09-08T03:00:00Z` → **0 lines**
(silent artifact, standing since 09-01). The `--tail 4000` snapshot covers back to 09-07 ~10:00
IST — today fully readable. All log-derived checks came from snapshots at
`/c/Trading/ArthaYantra/log-snapshots/2026-09-08/`. Recreate proposal stands at low urgency.

### 6.2 §3.39's veto split has a THIRD reason class: `stand-aside (both-IV-high 40/40 suppression)`

Today's confluence-composite all-fails split three ways, not two: 96 `60m bias` + **34
`stand-aside (both-IV-high 40/40 suppression)`** + 697 score-shortfalls. The stand-aside is the
hero-zero family's own IV suppression (all 34 rows: `scalp-hero-zero-nifty` +
`-sensex-niftyoi`, PE side, 17 each). Not new in the DATA — it appears 08-04 (40 rows), 08-11
(18), 08-25 (10) — but every prior findings file's §3.39 split reported only two classes, so a
session where it appears inflates the "veto" or "shortfall" bucket if parsed by position. Reading
rule: split §3.39 by the full reason string; three classes exist (`60m bias` / `stand-aside
both-IV-high` / `aggregate … below threshold`).

### 6.3 H49 day 4 — 7 canary REDs / 6 contracts, now CLUSTERED (09:35–09:46) just after a silent ticker blip (09:23)

Same tick-agg-not-closing-bars shape on pinned strikes (NFO `NIFTY2690824200CE/PE`, 23150PE; BFO
`SENSEX2691076800CE/PE`, 74700CE/PE; gaps 290–594 s) — but today the events are NOT scattered:
all 7 land 09:35–09:46, and `kite ticker disconnected` fired at **09:23:02 with no reconnect line
and no capture gap** (signal future 375/375; the NEW-13 silent-re-establish family, same as
09-07's 14:26). The bar-closing gaps that triggered the REDs start ~09:31–09:36 — consistent with
the tick-agg mirror taking minutes to resume bar-closing on low-traffic pinned strikes after a
WS re-establish, while the DB heals underneath via the KITE tail re-fetch. First session where
H49 has a plausible mechanism correlate (a ticker re-establish) rather than appearing on a clean
network; evidence accrues to ledger H49 — the 09-07 instances (8 REDs with the blip at 14:26 ≠
any RED window) still lack it, so this is a correlate, not yet the mechanism.

### 6.4 Swing catch-up replayed ON BOOT (08:49 > the 08:35 slot) — manas 2 entries, 13 cap-exceedance

`swing catch-up: booted 08:49:57 IST, AFTER today's fire — replaying the missed sweep on boot`,
for session 2026-09-07: **manas-arora 143 candidates → 2 entries (#305 KABRAEXTRU ×? @636.00
composite 0.647, #306 MTARTECH @7,519.00 composite 0.581), would-enter 15, admitted 2,
cap-exceedance 13** (SAKAR explicitly pyramid-cap-refused); **minervini 140 candidates → 0
entries — `entry pass skipped — the minervini book gate blocks entry at run start`, 21
would-enter/cap-exceedance, 14 sell-decision rows persisted**. NEW-8 12th measurement: manas
partially unbound 2nd time (cap binding at the margin), minervini fully book-gate-blocked. The
09-07 oddity recurs: the summary line says `0 refusal(s)` while cap-refusal WARNs precede it —
the counter counts a different class (standing observation, not escalated).

### 6.5 Mechanical pre-checks

- `tools/ledger-consistency-check.py`: **12 REVIEW lines — the identical standing set** of
  08-26…09-07 (7×[A] chip open/closed keyword pairs, 5×[B] pickup-vs-DONE keyword class). No
  edits; ledger consistent modulo the standing set.
- `tools/published-config-drift.py`: **69 published — 69 matched (45 clean, 24 drifted = the
  standing #1075 disabled-scalper drafts), 0 DB-only, 0 YAML-only.** Nothing republished.

### 6.6 H31 day-context — 8th consecutive clean day

Refresh durations 360 ms–3.0 s across the session, no FAILED movement, phase margin intact.

### 6.7 09-07 evening chain (inherited watches) — ALL CLEAN

EVENING_CHAIN DONE 18:58 · BHAVCOPY 09-07 SUCCESS 8,703 rows · MARKET_CONTEXT_DAY 09-07 SUCCESS ·
MANAS_SCREEN 2,298 / MINERVINI_SCREEN 1,810 · BHAVCOPY_CLOSE 216 · MINERVINI_PLANE_DIVERGENCE
DONE 18:49 · NOTIFIER_HEALTH + INGEST_COVERAGE 09-08 DONE 08:49 (boot catch-ups).

## 7 Tuning candidates

Ledger §0 group G/H is the authoritative status; nothing applied by this run.

| # | knob | status | today's evidence |
|---|---|---|---|
| T3 | `iv_pair` | **OPEN (owner) — FIRST SUPPORT in 36 sessions: 4/897 rows, one 14:12 PE bar** — the dot is alive, just extremely rare; the 0%-forever premise weakens | §3 |
| H49 (ledger) | per-contract tick-agg bar-closing sparse | **OPEN — day 4: 7 REDs / 6 contracts, clustered 09:35–09:46 after a 09:23 silent ticker re-establish — first plausible mechanism correlate** | §6.3 |
| NEW-9 (08-26) | 60m-bias veto | **OPEN — day 10: veto fully redundant (96 fails, all on sub-threshold composites; sole-blocker set EMPTY); tally 27 losers vs ~6 winners** | §2/§5.3 |
| NEW-16 (09-07) | weekend-stale Kite token 403 storm | **carried, not applicable today** (host down when the token died); next observation next Monday | §4b |
| NEW-6 (08-19) | unpaired opening-bucket canary WARN | **CLEAN — 3rd consecutive clean opening; day's 2 WARNs are the benign 12:06/12:09 ±1,430 lazy-cache pair** | §4 |
| NEW-13 (09-01) | recurring host outbound-network death | **OBSERVATION (owner/ops) — carried**; 09:23 silent ticker re-establish, zero capture impact — but see its new H49 correlation | §6.3 |
| NEW (09-04) | DB-not-ready boot-race crash | **carried — did NOT recur today** (`RestartCount=0` both, on the latest boot yet) | §4b |
| log barrier (09-01) | strategy-signal docker log | **unchanged — `--since` still ZERO lines (retested); `--tail` covers today; recreate at low urgency** | §6.1 |
| NEW-10 (08-27) | risk-limit base = current equity | **OBSERVATION (owner) — carried**; no trip | — |
| watch | `strike-pick` chain-proximity | **NSE weekly DAY-OF saturated: 468 all-fails / 16 NIFTY slugs / 0 SENSEX** (§3.27 cluster shape holds) | §2 |
| NEW (08-04) | mid-session deploys | **PROPOSED — carried**; no deploy today | — |
| NEW-3 (08-12) | `daily_profit_target` 1.5% | **OBSERVATION (owner) — carried** | no trip |
| NEW-1 (08-05) | paper heat-cap margin timeout | **PROPOSED — carried**; heat grep 0 on 2 funded entries (N23-A coverage question stands) | §5.1 |
| T30 | `breadth` dot `>32` | **OPEN** — down-day split: PE 32.7% (declines 27–37 straddling the line), CE 0% | §3 |
| T29 | exit model dominates entry gate | **OPEN (exit-band track)** — 3rd face in a row: funded PE TIME_STOPs −₹2,230.19 on a trend-down day while the same-leg shadow square-off cluster went +₹1,024.28 | §5.1/5.2 |
| T27 | relative-floor window | **OPEN; arming rec unchanged (NO)** | binding 59.9%; loosening ledger **59/45/14** — 2nd consecutive trend-day challenger win |
| T28 | `atmIv` frozen daily stamp | **OPEN** | fresh stamp 0.100875 just INSIDE the band → `iv_abs_band` 100%; 4th coin-flip in 5 sessions |
| T23 | partial-bucket tolerance | **OPEN** | 2 WARNs, both the benign day's-first pair; 0 straddles |
| T1 / T7 | `relativeVolumeMultiplier` / composite threshold | **REJECTED — carried** | challenger books remain deep negative |
| NEW-8 (08-24) | swing governor watch | **STANDING — 12th measurement: manas 2 entries + 13 cap-exceedance (2nd partially-unbound session); minervini book-gate-blocked at run start** | §6.4 |
| T8/T26 | latency | **OPEN (data) — shadow p50 1:19.0 in band (n=9); funded emit 16.6/19.0 s inside the measured band** | §5.1/5.2 |
| T2 | `iv_rank` | carried, not open | NULL 897/897 |

## 8 Honesty caveats

- **Regime: TREND-DOWN, from the CONTINUOUS session** (o 23,743.10 → continuous freeze 23,640.05
  pinned 15:15–15:28; day range 23,623.10–23,758.95; net −0.43% on 0.57%, **eff 0.759**). Official
  CAS print 15:29 **−4.95** → close 23,635.10 (official read −0.45%/0.57%, eff 0.795 — trend on
  both reads, aligned stamps; the smallest CAS delta measured). **G11 chop count stays 10.**
- **This run executed ~15:50–16:40 IST, before the evening chain**: today's bhavcopy EOD, screens,
  settles, market-context write and canaries had not run — tomorrow's run inherits them.
  (09-07's chain is verified clean in §6.7.)
- Log-derived numbers come from `--tail` snapshots (§6.1) — `--since` on strategy-signal still
  silently returns zero lines and must never be read as a quiet session.
- The §6.3 H49↔ticker-blip link is a CORRELATION on one session (labeled `computed` from
  timestamps); 09-07's REDs had no adjacent blip, so it is not yet the mechanism.
- Shadow figures are fan-out counts (§3.24); deduped clusters are in §5.2. §5.3 counterfactuals
  are corroborated by the books' own rows on the same legs, not hand-priced.
- Read-only run: SELECTs, log/tail reads, `docker inspect`, in-container health GETs. No restarts,
  deploys, writes, config changes, republishes. Docs-only PR: this file + rollup rows.
