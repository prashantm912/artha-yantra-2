# Session findings — 2026-08-31 (data date)

Analysis date: 2026-08-31 EOD (scheduled post-market agent, run ~15:45–16:30 IST). Analyst: Claude
(scheduled `session-analysis post`). Data: `signal_rejections` rows **1,324** (bounds
`2026-08-31T09:15:00+05:30`…`15:40`; rows 09:19–15:04), signals fired **0** (fired evals 0, no
suppressions), paper trades **0 entries / 0 closes** — but **positions 99/100 (the 08-28 stuck
pair) were MANUALLY closed by the owner on 08-28 19:56 IST** (§6.1), shadow closes **66** across
4 variants (champion 49).

Session character: **Monday, NSE-weekly eve (Sep-01 Tuesday expiry tomorrow)** · down-drift day
(official o 24,117.55 → c 24,080.40 = −0.15% on 0.56% range, eff 0.275 = **chop**; continuous
freeze 24,050.25 = −0.28%, continuous eff **0.498 = mixed** — NINTH straddle and the FIRST with
REVERSED polarity: every prior straddle read continuous *below* official; doctrine §3.33a keeps
the continuous stamp ⇒ **mixed**; CAS delta **+30.15**) · signal contract **`NFO:NIFTY26SEPFUT`**
· overnight HOST downtime again (4th consecutive): all containers started **08:32:30 IST**,
`RestartCount=0` · **FIRST FULLY-AUTOMATIC KITE LOGIN — the armed auto-login SUCCEEDED
END-TO-END (§4b): boot → CONNECTED in 37 s, zero manual action, NEW-11 CLOSES.**

## 1 Funnel numbers

| metric | value |
|---|---|
| rejections | 1,324 — **38 of 38 scalpers, FULL coverage** (3rd full-coverage session; `premium_skew` n=16, `iv_*` n=172) |
| eval outcomes | chart-gate-failed 2,072 · confluence-blocked 1,324 · composite-below-threshold 174 · **fired 0** · discipline-paused 0 |
| fired reconciliation (§3.36) | 0 fired = 0 emitted + 0 suppressed — nothing reached the risk gate |
| coverage | 24 of 24 populated 15-min buckets 09:15–15:00; rejections end 15:04 (15:15 bucket empty = window class); `subscriber_health_events` 0 rows; eval buckets fresh (zeros) to the close |
| boot health | boot 08:32:30 IST (post host-downtime), 0/38-unresolved transient → **38/0/0 at 08:33:43 (~57 s — fastest healed transient recorded; the boot-window breaker stall of 08-28 is gone with the login fix, §4b)** |
| paper (funded) | **0 entries / 0 closes** — zero-fire session (composite passed on 206 rows but nothing survived the rails/veto). §3.30 trivially 0/5 — explicit zero: `paper_events` OPENED count 0 |

## 2 Rail findings

- **volume-floor first-block 856/1,324 (64.7%)** — banded and honest (thresholds 9,603.75–60,596.25,
  43 distinct, zero flat); `relative-volume-floor` armed on 38/38 enabled (drift script clean).
- **`strike-pick` Monday-before-NSE-weekly: 14 all-fails, 7 NIFTY-rooted slugs, 0 SENSEX** — the
  Mon/Tue NSE cluster window opened MILD (series 235/604/322/452/531/…/**14**). Consistent with the
  §3.27 chain-pricing read: the Sep-01 front weekly priced its delta-band strikes inside the static
  band for most of the session. Tomorrow is the NSE weekly day-of — next window observation.
- First-block tail: time-window 244 · rsi-band 50 · time-of-day-preference 36 ·
  confluence-composite 20 · two-candle 16 · volume-pump 16 · pct-price-move 16 · supertrend-15m 14 ·
  divergence-vol-gate 14 · directional-change-gate 10 · oi-cross-required 10 · hero-zero 8 ·
  psar-durability 6 · morning-opening-formation 4 · call-put-delta-filter 2 ·
  option-side-constraint 2 (17 distinct rails).
- **confluence-composite all-fails split (§3.39): 594 `60m bias opposes the side`
  (composite 0.2660–0.8081) + 376 score-shortfall aggregates.** Sole-blocker veto set: §5.3 — and
  today it is the veto's **first adverse day**.

## 3 Composite + dots

- **OI bloc fully LIVE** — quadrants NEUTRAL **0/1,040**, spurt NULL 0, basis LIVE 1,040/1,040.
  futures_oi capture 25,806 snaps / **374 of ~375 minutes**.
- **Composite passes 206 of 1,040 scored (19.8%) — 136 CE / 70 PE**; max 0.8081.
- Live-dot support (complete session, n=1,040 unless noted): `iv_abs_band` 0% (n=172, **8th
  day** — atmIv stamp 0.094231, below the 10–12 band) · `iv_rank` 0% (withheld, standing) ·
  `iv_pair` 0% (**30th** — T3, owner) · oi_spurt 3.3% · trending_cross 9.6% · volume 17.7% ·
  premium_skew 25.0% (n=16) · breadth **42.9%** (see below) · vix 42.9% · vwap 46.7% ·
  underlying_oi 50.8% · futures_oi 55.0% · rsi 55.8% · sentiment_slope 56.2% · basis 57.1% ·
  psar 66.2% · sentiment 66.5% · vwma 81.0% · iv_slope 91.9% (n=172) · drastic_oi 95.2% ·
  supertrend 100% (free today).
- **§3.28 breadth (T30) — full BOTH-side saturation returns:** PE **446/446 = 100%** (declines
  42–45, always > 32) and CE **0/594** (advances 8–15, never). The side-aware step function at its
  purest — a free +1.0 for every PE row and dead weight for every CE row, all session.

## 4 Data health

| field | today | verdict |
|---|---|---|
| `ivRank` | NULL 1,324/1,324 | dead-data, standing (since 07-02) |
| `dowUp` | NULL 1,324/1,324 | by design (un-armed) |
| `fiiLongPct` | live on all 1,040 contextful rows | healthy |
| `atmIv` | 1 distinct (0.094231) | frozen daily stamp — correct (G12/T28, 26th) |
| vix / ceIvAvg6 / skew | 14 / 72 / 96 distinct | alive |
| misaligned 1m candles (§3.15) | **0 rows** | clean — 10th consecutive |
| §3.17 canary | **2 WARNs + 0 straddles, BOTH UNPAIRED AND UNEXPLAINED** (§6.3) | watch — NEW-6 class recurrence |
| signal-future capture | **375/375 min** aligned 1m on `NIFTY26SEPFUT` (KITE 353 + TICK_AGG 22, 0 BACKFILL) | ✓ |
| futures_oi capture | 25,806 snaps / **374 of ~375 min** | ✓ |

## 4b BOOT WINDOW (§3.41) — FIRST FULLY-AUTOMATIC LOGIN, CLEAN

**The armed auto-login succeeded end-to-end with zero manual action — the first morning ever.**
Timeline (all IST):

- **08:32:30** — containers boot (host powered off overnight, 4th consecutive; after the 08:05
  cron slot, so `catchUpOnBoot` is the operative path, as designed).
- **08:32:42** — `kite session restore: persisted token from 2026-08-29T05:23Z expired at
  2026-08-30T06:00+05:30 — NOT resumed; a fresh login is required` — **the #1520 fix working: no
  dead-token resume, no 403 storm, no breaker trip** (contrast 08-28, where exactly this resumed
  token opened `kite-rest` for 6 minutes).
- **08:32:46** — boot catch-up armed ("will attempt in 20s if still not connected").
- **08:33:06** — `authorize hop 1 answered HTTP 302, Location
  https://kite.zerodha.com/connect/finish (query redacted), same-origin=true` →
  `request_token found at authorize hop 2` — **the #1518 fix working: the AUTHORIZE step's missing
  token was an INTERMEDIATE 302 all along (the hypothesis #1515's origin fix could not settle),
  and following the redirect chain resolves it.**
- **08:33:07** — `kite session status -> CONNECTED` · `auto-login: session established`.
  **Boot → CONNECTED: 37 seconds.** Engine reload healed 0/38 → 38/0/0 at 08:33:43.
- **Circuit-breaker transitions in the boot window: ZERO.** Three isolated 400s at 08:35–08:38
  (2× `invalid token`, 1× `invalid from date`) were absorbed without opening the breaker (#1519's
  intent); they appear only in the failure ring of a much later transition line.
- **Capture minutes lost that overlap the window: 0** (session valid ~42 min before the open;
  signal-future 375/375, futures_oi 374/375, options capture normal).
- Post-close, out of window but reported for completeness: `kite-rest` OPEN 15:45:13 → CLOSED
  15:58:00, failure ring naming `invalid segment for continuous data` 400s — the H26
  empty-backfill class (documented same day in #1543), post-session, zero trading impact, and the
  #1512 diagnostics named the cause unprompted.

**Verdict: NEW-11 CLOSES.** The three-fix chain (#1518 redirect-follow, #1519 boot-403 breaker
guard, #1520 expired-token refusal) is live-verified in one morning; the 08:15 watchdog's boot
catch-up had nothing to page about.

## 5 Shadow outcomes + counterfactuals

### 5.1 Funded book

Zero entries. The only funded-book movement since the 08-28 file: **positions 99/100 were
manually closed by the owner 2026-08-28 19:56 IST at ₹586.05/₹522.70 — realized −₹3,874.82 and
−₹5,017.97** (`close_reason=MANUAL`; the 08-28 chain-mark estimate ≈ −₹8,751 vs booked total
−₹8,892.79 — the estimate was honest). NEW-12's operational half is resolved; see §6.1 for the
structural halves (H44) which are now LIVE.

### 5.2 Shadow book

**Champion: 49 closes, 16 net wins, −513.00 pts, −₹21,091.20** → 15 deduped `(bar, leg, entry)`
clusters on 8 bar times. The day is TWO stories superimposed:

- **Morning PE stops −₹52.3k**: 09:27/09:45/10:39/11:09 PE clusters all negative — worst 09:45
  `SENSEX2690377600PE` −₹18,862.61 (6 slugs) and 09:45 `NIFTY2690124200PE` −₹15,711.49 — plus
  the 11:57 CE cluster −₹16,076.27.
- **The 11:51 CE pair +₹48.1k**: `NIFTY2690123950CE` @156.15 **TAKE_PROFIT ×7 +₹27,775.79**
  (a rare shadow TP cluster) and `SENSEX2690376400CE` @795 SQUARE_OFF +₹20,341.93.

Concentration caveat: without the 11:51 pair the day is ≈ −₹69k; the effective independent
sample is ~8 bar times. All-time champion **−₹321,568.59** (794 closes, 279 net wins). Shadow
entry latency p50 78 s / p95 81 s (n=66, structural class, unchanged).

**Challenger-only class: 3 observations, 3 wins, +₹1,407.57** — all the SAME deduped leg (12:00
`NIFTY2690123950CE`, +₹469.19 in each of composite-055 / vol-12k5 / vol-off; champion dedup held
its 11:54 entry). **Loosening ledger 34/29/5 → 37 measured / 29 losses / 8 wins.** All-time:
composite-055 **−₹16,090.63** (day +₹7,343.15, 3/3 wins) · vol-12k5 −₹43,290.19 · vol-off
−₹68,277.05. REJECTED statuses stand (one green chop day does not outweigh the prior).

**Per-rail counterfactual P&L (owner directive 08-20), all-time champion NET:** volume-floor
423 / **−₹184,459.32** (day −₹45,147.92 over 22 refused — the morning PE stop clusters) ·
rsi-band 102 / −₹68,646.08 (day −₹14,955.94) · two-candle 37 / −₹13,303.71 ·
call-put-delta-filter 7 / −₹11,149.59 · divergence-vol-gate 36 / −₹10,860.52 ·
morning-opening-formation 4 / −₹9,629.13 · **`confluence-composite` 20 / +₹7,148.76 — the
cushion REBUILT (+₹6,873.96 today, carried entirely by the 2-trade 11:51 pair — n=2, not
decision-grade)**; pct-price-move (+₹2,440.50) and supertrend-15m (+₹1,278.11) also positive.
**Root split did NOT flip for the first time in 6 measured days: SENSEX −₹138.13/trade (351) vs
NIFTY −₹616.45 (443)** — same direction as 08-28; still not actionable.

### 5.3 §4.2 counterfactuals — the 60m-bias-vetoed set (day 4, NEW-9): FIRST ADVERSE DAY

Sole-blocker veto set: 8 rows → 4 bar-times × 2 roots, **all CE, 11:51–12:03** — and today the
veto refused the day's biggest winners:

| bar | leg | entry | outcome (champion-corroborated where a shadow row exists) |
|---|---|---|---|
| 11:51 | NIFTY2690123950CE | 156.15 | champion cluster **+₹27,775.79 (TAKE_PROFIT ×7) — WIN refused** |
| 11:51 | SENSEX2690376400CE | 795.00 | champion cluster **+₹20,341.93 — WIN refused** |
| 11:54 | NIFTY23950CE / SENSEX76600CE | 185.00 / 739.45 | +₹472.44 / +₹415.62 — small WINs refused |
| 12:00 | NIFTY2690123950CE | 185.05 | challenger-only row +₹469.19 — WIN refused |
| 12:03 | NIFTY24000CE / SENSEX76600CE | 158.40 / 768.40 | §4.2 square-off model: 159.00 (+0.6 pts) / 764.45 (−3.95 pts) — ≈ wash |

**Veto ledger after 4 days: days 1–3 refused 12/12 losers under engine exits; day 4 refused ~6
winners (2 large, ≈ +₹48.5k of engine-corroborated cluster P&L) + 2 washes.** The veto is no
longer one-sided — it behaves as a momentum filter that saves the chop-morning losers and costs
the trend-turn winners. Keep accumulating (n=4 sessions); no proposal either way.

## 6 New data points / anomalies

### 6.1 NEW-12 follow-through — stuck positions resolved; H44 structural fixes LIVE

- **Operational half:** owner manually closed 99/100 on 08-28 evening (§5.1). Funded book flat.
- **Structural halves shipped as H44 and live-verified today:** #1527 (no-tick fill indicator +
  a fill-time closability gate, deliberately DISARMED = measure-only) and #1539 (**re-centre the
  pinned ATM band on spot during the session**). Measured today: `OptionAtmPinner` passes every
  ~5 min at `resolved=2/2, desired=84, pinned=84` and **62 pin roll-offs during the session
  window** — the band now follows spot instead of staying frozen at the 09:15 strike set. The
  08-28 class (picker chooses a strike outside a static band) loses its mechanism; the first
  funded fire on a drifting-spot day is the real verification.
- `close_reason` vocabulary delta (§3.29): **MANUAL 2 → 4** (the two owner closes). Never-fired
  set unchanged: `take_profit premium_pct` (36 armed, zero funded TP closes in 2 months) ·
  `signal_exit` (38) · `square_off` (2) · tag `oi-confluence-exit` (8). INDETERMINATE standing
  pair unchanged (`trailing_stop`/`stop_loss` `atr_multiple`, 2 each).

### 6.2 First fully-automatic login morning

§4b in full. NEW-11 closes; the failing step was the redirect chain, not the origin.

### 6.3 Two unpaired, unexplained canary WARNs — NEW-6 class recurrence

- **11:34:14 IST**, bucket 11:30: 3m bar 13,260 vs Σ1m 17,420, **shortfall 4,160 = 64 NIFTY
  lots, 23.9% of the expected sum** — just under the >25% frozen-partial line. UNPAIRED, no
  partner WARN or straddle, **no reconnect line 11:25–11:40** (both §3.17 reconnect
  discriminators absent), and not the 08-25 first-event cache-miss shape (that class produces a
  ± pair; here no partner exists at all).
- **15:19:13 IST**, bucket 15:12: shortfall 780 (12 lots, 1.1%) — small, session-tail, also
  unpaired.

NEW-6 was CLOSED-FAVOURABLE on 08-25 after 4 clean sessions; today re-opens the watch (this is
the first unexplained unpaired WARN since). Rails read the broker-corrected 3m side, so no
gating impact is implied; the in-memory 1m mirror is the diverging side by §3.17's mechanism.

### 6.4 H31 day-context — 3rd consecutive ZERO-failure session

`insight trust read day-context FAILED` grep: **0** (trajectory 89% → 18% → 0% → 0% → 0%).
Same denominator caveat (successes do not log).

### 6.5 Swing book capacity-bound on the Monday entries pass

The 08:35 catch-up ran its entries pass and took **0 entries: 14 fresh candidates
governor-blocked** (`pyramid_risk_cap` — "blocked by the 6.0% portfolio open-risk cap",
`risk_audit` 92–105) **and then `max_open_paper_positions` tripped: "book is at capacity: 12
open / cap 12"** (id 106). The governors working, but the swing book is now fully
capacity-bound — fresh screen candidates cannot enter until an exit frees a slot. Observation,
not a defect (NEW-8 6th measurement, clean).

### 6.6 First live days for the two new audit surfaces

- **N23-A heat-cap premium-outlay shadow (#1537)**: armed, zero fires today ⇒ unexercised (needs
  a funded entry).
- **N26 silent-entry-veto audit (#1536)**: live, zero veto rows today (nothing reached the entry
  path). Both go on the "verify on first funded fire" list.

### 6.7 Mechanical pre-checks

- `tools/ledger-consistency-check.py`: **12 REVIEW lines — the identical standing set of
  08-26/08-27/08-28** (7×[A] chip open/closed keyword pairs, 5×[B] pickup-vs-DONE keyword
  class). No edits made; ledger consistent modulo the standing set.
- `tools/published-config-drift.py`: **69 published — 69 matched (45 clean, 24 drifted = the
  standing #1075 disabled-scalper drafts), 0 DB-only, 0 YAML-only.** Unchanged; nothing
  republished by this run.

## 7 Tuning candidates

Ledger §0 group G is the authoritative status; nothing applied by this run.

| # | knob | status | today's evidence |
|---|---|---|---|
| NEW-11 (08-28) | auto-login AUTHORIZE failure | **CLOSED — fixed and live-verified**: #1518 (follow the redirect chain) + #1519 + #1520; first fully-automatic login 08:33:07 IST, 37 s boot→CONNECTED, zero breaker trips | §4b |
| NEW-12 (08-28) | funded legs outside the tick band unclosable | **RESOLVED operationally (owner MANUAL closes 08-28 19:56) + structural fix LIVE (H44: #1527 measure-only closability gate, #1539 band re-centre — 62 roll-offs measured today).** Residual verification: first funded fire under the re-centred band | §6.1 |
| NEW-9 (08-26) | 60m-bias veto (inside confluence-composite) | **OPEN — day 4 is the FIRST ADVERSE day: refused ~6 winners incl. the 11:51 pair (+₹48.5k champion-corroborated) + 2 washes; running tally 12 losers refused (d1–3) vs ~6 winners refused (d4)** — no longer one-sided; keep accumulating | §5.3 |
| NEW-6 (08-19, reopened) | unexplained unpaired canary WARNs | **REOPENED — 2 today (11:34 −4,160 = 64 lots at 23.9%; 15:19 −780), both unpaired, no reconnect, not the cache-miss shape** | §6.3 |
| NEW-10 (08-27) | risk-limit base = current equity | **OBSERVATION (owner) — carried**; no trip today (zero-fire) | — |
| watch | `strike-pick` chain-proximity | **WATCH** — Mon-before-NSE-weekly came in MILD (14 fails / 7 NIFTY slugs vs 235–604 in the prior cluster); tomorrow = Sep-01 day-of | §2 |
| NEW (08-04) | mid-session deploys | **PROPOSED — carried** | no mid-session deploy today |
| NEW-3 (08-12) | `daily_profit_target` 1.5% | **OBSERVATION (owner) — carried** | no trip (zero-fire) |
| NEW-1 (08-05) | paper heat-cap margin timeout | **PROPOSED — carried**; N23-A shadow now armed, unexercised | §6.6 |
| T30 | `breadth` dot `>32` | **OPEN — full both-side saturation returns** (PE 100%, CE 0%) | §3 |
| T27 | relative-floor window | **OPEN; arming rec unchanged (NO)** | binding 64.7%, banded; loosening ledger now **37/29/8** — today added 3 wins (one leg) |
| T28 | `atmIv` frozen daily stamp | **OPEN** | 1 distinct = 0.094231 (26th); `iv_abs_band` 0% 8th day |
| T3 | `iv_pair` | **OPEN (owner)** | 0% (30th session) |
| T23 | partial-bucket tolerance | **OPEN** | 2 WARNs + 0 straddles — both unexplained (§6.3) |
| T1 | `relativeVolumeMultiplier` | **REJECTED — carried** | vol-12k5 challenger-only: 1 win (+₹469.19, shared leg) |
| T7 | composite threshold | **REJECTED — carried** | composite-055 challenger-only: 1 win; all-time still −₹16,090.63 |
| NEW-8 (08-24) | trail-should-have-fired watch | **STANDING — 6th clean measurement** (14 pyramid_risk_cap trips + capacity trip = governors normal) | §6.5 |
| T8/T26 | latency | OPEN (data) — no emissions today; shadow p50 78 s / p95 81 s | §5.2 |
| T2 | `iv_rank` | carried, not open | NULL 1,324/1,324 |

## 8 Honesty caveats

- **This run executed ~15:45–16:30 IST** — before the 18:4x evening batch and the 18:52/18:53
  swing settles (EXITS-only; 0/0 is the normal correct outcome, H27); tonight's ingest outcomes
  are tomorrow's verifications.
- Regime stamped from the CONTINUOUS session (§3.33a): eff 0.498 = **mixed**; official 0.275 =
  chop — 9th straddle, first with reversed polarity. **G11 chop count stays 8** (the continuous
  stamp governs).
- The §5.3 corroborations reuse the champion book's own engine-exit fills for 6 of 8 legs; the
  12:03 pair is a 15:12 square-off model on 3-min chain LTPs (no slippage/fees). The day-2
  model-divergence caveat stands.
- The champion −₹21,091.20 is a fan-out figure (49 closes → 15 deduped clusters on 8 bar
  times); the 11:51 pair is +₹48.1k of it, so the effective sample is small and mixed-sign.
- Read-only run: SELECTs, log greps, in-container reads. No restarts, deploys, writes, config
  changes, republishes.
- Docs-only PR: this file + rollup rows.
