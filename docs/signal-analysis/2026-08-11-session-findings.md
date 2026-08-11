# Session findings — 2026-08-11 (data date)

Analysis date: 2026-08-11 (scheduled post-market agent). Analyst: Claude (scheduled
`session-analysis post`). Data: `signal_rejections` rows **884** (bounds
`2026-08-11T09:15:00+05:30`…`15:30`), signals fired **2 ENTRY + 1 EXIT**, paper trades **1
opened / 1 closed (+₹581.46)**, shadow closes **12** (champion only).

Session character: **NSE weekly expiry day-of** (NIFTY weeklies expiring today) · VIX 11.83–12.38
· trend-DOWN tape (continuous o→c −0.51%, efficiency 0.846) · **36-minute mid-session power loss
11:36–12:12 IST** (host suspended; feed dead, stack up — self-healed, see §6.1 and the
stack-outage register) · signal contract `NFO:NIFTY26AUGFUT` (log-confirmed; closes 24,515–24,561).

---

## 1 Funnel numbers

| metric | value |
|---|---|
| rejections | 884 (34 of 38 scalpers emitting) |
| silent slugs | 4 — `golden-crossover`/`open-high-low` CE-base twins ×2; PE twins emitted → CE chart-gate silence on a down tape, not load failure (boot 38/0/0 at 01:42 IST) |
| eval outcomes | chart-gate-failed 1,874 · confluence-blocked 884 · composite-below-threshold 410 · **fired 2** · discipline-paused 0 |
| coverage | 24 of 25 15-min buckets populated 09:15–15:15; the **11:45 bucket is EMPTY = the outage window** (eval buckets wrote all-zero rows 11:39–12:09 — process alive, feed dead) |
| first-block histogram | volume-floor 498 (56%) · time-window 154 · rsi-band 80 · time-of-day 34 · strike-pick 8 · confluence-composite 6 · rest ≤16 |
| all-fails expansion | confluence-composite 566 · volume-floor 498 (avg operand 8,344 vs banded avg threshold 19,838) · **strike-pick 322** · rsi-band 300 |

**THE HEADLINE — first funded scalper fire since 2026-08-05, and it won.** 11:06 bar: `scalp-connect-the-dots-sensex-niftyoi-pe` + `scalp-golden-crossover-sensex-niftyoi-pe` both fired
(composite **0.9510**), filled into ONE paper position by design (pyramiding average-in, 2 OPENED
events): `SENSEX2681378800PE` BUY 40 @ 741.15, sub-account 1. Closed **TIME_STOP 11:37:16 IST,
+₹581.46 (+2.0%)** — settled *inside* the outage window off a ~1-minute-old tick, exactly the #694
doctrine (exits use best available truth). The 11:36 EXIT signal (id 172) is the paired
close-signal. Sub-account 1 banked ≥1% of its ₹30k allocation → profit-locked for the day per
§12.7; no later entry attempt occurred (`discipline-paused` 0 all day), so the freeze bound
nothing.

## 2 Rail findings

- **§3.27 chain-proximity watch: PREDICTION CONFIRMED.** NSE weekly day-of → **322 `strike-pick`
  fails, ALL NIFTY-rooted (14 slugs), ZERO SENSEX-rooted.** Cluster history now: 08-03 eve 235 /
  08-04 day-of 604 / 08-05 Wed 0 / 08-07 post-BSE-Fri 350 (SENSEX) / 08-10 UNOBSERVED (outage) /
  **08-11 day-of 322 (NIFTY)**. Next discriminator: 08-12 Wednesday control (expect 0).
- **The saturation had a measured COST today, not just a count.** The 11:06 fired bar's NIFTY
  twins (`scalp-connect-the-dots-nifty-pe` id 23016, `scalp-golden-crossover-nifty-pe` id 23013)
  were blocked with `strike-pick` as the **sole** failing rail — the same setup that paid +₹581 on
  the SENSEX book could not resolve a NIFTY leg on the expiring chain. Counterfactual in §5.
- volume-floor: banded thresholds live (avg 19,838 vs day's 3m volume p50 7,995 / p90 25,740 /
  max 94,640 on the future) — floor sat ~p85-90, tracking the thin tape. No flat-threshold
  fingerprint; `relative-volume-floor` armed on 38/38 published scalpers (§3.14 check clean).
- `chain-unavailable` 14 rows, all one row per slug (14 slugs) — a single 3m bar during the
  outage-recovery minute; honest gate outcome, not a Kite blip episode.

## 3 Composite + dots

- Distribution (scored rows, n=664): mass at 0.3–0.5 (478), 68 rows at 0.6, 68 at 0.7–0.8;
  **CE 80 / PE 584** — a PE-sided day, consistent with the trend-down stamp. 114 rows had
  composite ≥ threshold; 2 fired.
- Dot support (complete session, n=664 unless noted): `iv_pair` **0%** (16th session — T3, owner)
  · `iv_rank` **0%** (withheld, dead-data, standing) · trending_cross 2.4% · oi_spurt 6.0% ·
  vwap 10.2% · basis 12.0% · volume 25.0% · futures_oi 48.5% · sentiment_slope 49.4% · rsi 53.0%
  · vix 53.9% · psar 59.9% · underlying_oi 63.9% · vwma 82.2% · drastic_oi 85.8% · breadth 88.0%
  · supertrend 97.6% · `iv_abs_band` 100% (n=98; frozen daily stamp, 12th session).
- **§3.28 refinement — `breadth` is SIDE-AWARE, and today shows it:** the rule string is one
  (`advances/declines > 32`) but the operand differs per side — CE rows test **advances** (today
  11–17, **0/80 support**) and PE rows test **declines** (today 33–39, **584/584 support**).
  The dot remains a per-session step function (§3.28), but per SIDE: today it was a free +1.0 on
  every PE composite and dead weight on every CE. The G16 `neverCrossing` probe judges the
  session-wide dedup, so a split like today's reads neither-dead-nor-free — worth remembering
  when reading its flag.
- OI bloc fully live (no S24 suppression — weekly, not monthly): quadrants NEUTRAL **0/664**,
  spurt nulls only on the 220 context-less rows, `futures_oi_snapshots` 337/375 minutes (the 38
  missing ≈ the outage window).

## 4 Data health

| field | today | verdict |
|---|---|---|
| `ivRank` | NULL 884/884 | dead-data, standing (since 07-02) |
| `dowUp` | NULL 884/884 | by design (un-armed) |
| `fiiLongPct` | 12.57, 1 distinct | daily EOD stamp, alive (known class) |
| `atmIv` | 1 distinct | frozen daily stamp — correct behaviour (G12/T28) |
| breadth pair | advances 11–17 / declines 33–39, 7 distinct | alive, side-split above |
| vix | 30 distinct, 11.83–12.38 | alive |
| ceIvAvg6 / skew / basis | 68 / 74 / 69 distinct | alive |
| misaligned 1m candles (§3.15) | **0 rows** | clean — today's outage backfill wrote minute-aligned buckets only (contrast 07-15/20/22) |

- §3.17 canary: **2 unpaired WARNs + 1 straddle** (G9 suppression working). 12:16 IST WARN on the
  12:12 bucket (shortfall 66,820, engine 3m 11,700 vs Σ1m 78,520) is the **outage-recovery
  bucket** — the engine's 3m bar was thin while the DB 1m side carried the backfill; explained,
  not the frozen-partial class. 14:10 WARN shortfall −455 (7 lots, 12.3% of a thin 3,705 bucket —
  fired on the pct arm, under the 650 absolute) — benign residue magnitude, unpaired. Straddle
  ±195 at 14:31 suppressed correctly.
- Kite session validated 15:59 IST; market-data canary GREEN, 0 problems, 69 ticked tokens.
- Engine reloads today: 4 rows, all `installed=f`, 38/0/0 — periodic reconciles, **no mid-session
  deploy** (container up since 01:41 IST).

## 5 Shadow-book outcomes + counterfactuals

**Champion: 12 closes, 1 win, −849.60 pts, −₹18,144.94 net** (all-time −₹133,347.54). Deduped
(§3.24): **6 (bar, leg) events** — 09:24 NIFTY PE **+₹448.87 W** · 09:24 SENSEX PE −₹1,199.44 ·
09:48 SENSEX 78800PE ×6 −₹12,583.71 · 11:06 SENSEX 78800PE ×2 −₹3,536.69 · 15:00 hero-zero CE ×2
−₹1,273.97. Entry latency p50 1:17.9 / p95 1:19.6 (n=12) — structural (G8), unchanged.

**Challenger variants: ZERO opens (last challenger open 2026-08-07 11:55).** Verified NOT a
defect: boot log shows the active set `[vol-off, vol-12k5, composite-055, dot-null-withheld]`
loaded; `ShadowVariants.accepts` requires **every non-overridden rail to pass**, and no row since
08-07 midday has had its failing set fully covered by any variant's overrides (today's
volume-floor sole-blocker class always carried a second failing rail; the 6
`confluence-composite` first-blocks all sat < 0.55). Champion-only days are the mechanism working
on this tape, not silence. `dot-null-withheld` (registered 08-01) has 0 rows ever — its
`armedPolicyCouldHaveFired` clamp is the documented inert-by-design guard.

**Two multi-exit clusters (§3.24 exit experiments), both stop-favouring — 5th consecutive
observation, first on a TREND day:**
- 09:48: `market-movers` STRUCTURAL_STOP 10:01 **−35.50 pts** vs five SQUARE_OFF holds
  **−114.45 pts each**.
- 11:06: STRUCTURAL_STOP 12:47 **−43.65** vs SQUARE_OFF hold **−125.95** — and the FUNDED book's
  30-min TIME_STOP on the same leg beat both: **+14.54 pts** (+₹581.46), locked near the premium
  peak (~755 at 11:37) before the afternoon decay. Early-exit dominance again; prior G11
  observations were chop-day, today extends the pattern to trend-down.

**§4.2 counterfactual — the strike-pick-blocked NIFTY twins** (the one rejection class the shadow
book structurally skips: no leg resolves). Modelled leg: NIFTY 24500 PE, expiry **today**
(day-of), chain LTP entries; modelled exit = the 30-minute harness horizon (a modelling CHOICE
per §3.16 — the engine's own `max_bars` spans five horizons):

| bar | entry LTP | exit @+30 min | outcome |
|---|---|---|---|
| 11:06 | 68.75 | 74.20 (11:36, last pre-outage snap) | **WOULD-WIN +5.45 pts (+7.9%)** |
| 11:12 | 75.20 | horizon 11:42 = **inside the outage hole** | UNRESOLVED (pre-hole 11:36 → −1.00; first post-hole 12:14 → −7.75) |
| 11:15 | 68.75 (11:16 snap) | horizon 11:45 in hole | UNRESOLVED (+5.45 / −1.30 by convention) |
| 11:18 | 71.90 | horizon 11:48 in hole | UNRESOLVED (+2.30 / −4.45) |

Honesty caveats: 2–3-min snapshot granularity, no slippage/fees, ±1-strike pick approximated
(engine picker unavailable — that's why the rail blocked), and the chain has a 11:36–12:12
capture hole. The clean read: the 11:06 NIFTY twin mirrors the funded SENSEX winner —
**expiry-day chain saturation cost the NIFTY book a winning trade** (~+₹4,000 gross at 75-lot
NIFTY sizing… stated as scale only, not a P&L claim). Also true: legs entered 6–12 min later
would likely have lost — the edge was the 11:06 bar, not the whole cluster.

## 6 New data points / anomalies

### 6.1 Mid-session power loss 11:36–12:12 IST (36 min) — self-healed, forensics clean

Already registered in the memory outage register the same evening; recorded here as the
session-file fact set. Feed dead / stack up (containers' `StartedAt` untouched — uptime proves
nothing across a host suspend). `subscriber_health_events`: `receive-stall` 12:12:53 ("no candle
received for 2148s while the feed is live — Redis candles.1m subscription dropped"),
`resubscribe`, `recovery` 12:13:53 (53s). Eval-outcome buckets wrote **all-zero rows
11:39–12:09** — the V045 "zero proves the process was alive" semantics doing their job (contrast
08-10: NO rows). Feed watchdog fired 3×, gap-backfilled (aligned buckets — §3.15 clean); engine
reload 12:13 38/0/0. The one trading event inside the window was the TIME_STOP settle (§1),
which behaved per doctrine. Rejections lose ~11 bars (11:36–12:12); every §3 table above is a
**partial session in that window** (§3.21 class).

### 6.2 §3.34 heat-gate evaluability — first funded test since the #1326 fix: PASS

`heat call failed|heat unassessable` grep over the session: **0** on a 1-funded-entry day — the
margin call succeeded (the 08-05 first-call timeout class did not recur; #1326's master warm-up
held). Per the 08-05 correction this proves evaluability only, NOT coverage — the entry is a long
option BUY carrying 0 SPAN, so the 60% cap still binds nothing (owner question N23-A stands).

### 6.3 Mechanical pre-checks

- `tools/ledger-consistency-check.py`: **10 REVIEW lines — the identical standing false-positive
  set** as 08-06…08-10 (5×[A] snapshot/self-referential chips, 5×[B] keyword refs). Ledger
  consistent in substance; no edits required.
- `tools/published-config-drift.py`: **69 published — 69 matched (67 clean, 2 STALE-PUBLISH),
  0 DB-only, 0 YAML-only.** Same 2 as 08-03…08-10: `minervini-cheat-3c` /
  `minervini-primary-base` (1.0.2 drafts of 08-01, name+description only). Republish proposal
  carried — **nothing republished by this run** (GAINS: name/description text; LOSES: nothing —
  but per doctrine the diff must be re-read at republish time).

### 6.4 §3.29 unexercised-path audit (day delta)

Fired vocabulary since 07-01: TRAILING_STOP 13 · STRUCTURAL_STOP 6 · **TIME_STOP 5 → 6 (today's
close)** · STOP_LOSS 5 · MANUAL 2. Armed set unchanged (10 (type,basis) rows + tag). Never-fired
stands: `take_profit premium_pct` (36) · `signal_exit` (38) · `square_off` (2) · `stop_loss
percent` (4) · tag `oi-confluence-exit` (8). INDETERMINATE pair (`trailing_stop atr_multiple` 2,
`stop_loss atr_multiple` 2) stands. Classification notes: take_profit remains
unreachable-this-regime (nearest miss today: the funded leg peaked ~+2% vs the +35% bracket);
signal_exit remains shadowed by earlier exits (today TIME_STOP won the race at bar 10).

### 6.5 §3.30 freeze telemetry

Sub-account 1: 2 OPENED events (both 11:07, the pyramid pair), last entry 11:07, day PnL
**+₹581.46** → profit-locked (§12.7) from ~11:37. Sub-accounts 2–5: zero entries (nothing else
fired). **0 of 5 frozen before 14:30 by the loss rail; 1 of 5 profit-locked** — the design
working, not starvation. `discipline-paused` counter 0 (no entry attempt met a frozen book).

## 7 Tuning candidates

Ledger §0 group G is the authoritative status; nothing applied by this run.

| # | knob | status | today's evidence |
|---|---|---|---|
| watch | `strike-pick` chain-proximity | **WATCH — day-of CONFIRMED (322, all-NIFTY)** | first measured opportunity COST (§2/§5: the 11:06 NIFTY twin was a would-win). 08-12 Wednesday control next (expect 0) |
| NEW-1 (08-05) | paper heat-cap margin timeout | **PROPOSED — carried** | first funded re-test PASSED (§6.2); coverage question N23-A unchanged |
| T30 | `breadth` dot `>32` | **OPEN** | side-aware refinement (§3): CE 0/80, PE 584/584 — the fixed threshold is a per-side session bias |
| T27 | relative-floor window | **OPEN; arming rec unchanged (NO)** | floor tracked thin tape at ~p85-90, zero flat rows |
| T28 | `atmIv` frozen daily stamp | **OPEN** | 1 distinct again (12th) |
| T3 | `iv_pair` | **OPEN (owner)** | 0% (16th session) |
| T23 | partial-bucket tolerance | **OPEN — watch** | recovery-bucket WARN explained by outage; one 7-lot thin-bar WARN (pct arm); straddle suppression worked |
| T1 | `relativeVolumeMultiplier` | **REJECTED — carried** | — |
| T7 | composite threshold | **REJECTED — carried** | composite-055: 0 challenger-accepted rows today (all sub-0.55 or multi-rail) |
| NEW (08-04) | mid-session deploys | **PROPOSED — carried** | clean today (no mid-session recreate; 4 reconcile-only reloads) |
| NEW (08-03) | minervini republish | **PROPOSED — carried** | 7th session (§6.3) |
| T10 | stale OPEN paper positions | **OWNER — chronic** | **18 OPEN unchanged** (6 manas + 12 minervini) |
| T8/T26 | latency | OPEN (data) | shadow p50 1:17.9 / p95 1:19.6 (n=12); funded entry 11:06 bar → 11:07:19 fill (~79s) — same structural class |
| T2 | `iv_rank` | carried, not open | NULL 884/884 |
| T29 | scalper `time_stop` | **CLOSED** | today's funded TIME_STOP beat both the stop and the hold on its own leg (§5) — consistent with the close verdict; G11 chop-count unchanged (today = trend) |

## 8 Honesty caveats

- The 11:36–12:12 window makes every session-level table partial (§3.21); ~11 evaluation bars and
  ~38 OI-snapshot minutes are structurally absent, and chain-based counterfactual exits crossing
  the hole are UNRESOLVED, not losses.
- Shadow P&L (brackets/structural/square-off, no time stop) and the funded book (per-strategy
  `max_bars`) are different exit models — today they disagreed in sign on the same leg (§5); both
  are stated.
- The §5 counterfactual horizon (30 min) is a harness modelling choice, never "the armed
  fleet-wide stop" (§3.16 correction).
- Read-only run: SELECTs, `docker logs`, `docker inspect`, in-container health GETs. No restarts,
  deploys, writes, config changes, or republishes. Docs-only PR: this file + rollup rows.
