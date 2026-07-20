# Session findings — 2026-07-17 (data date)

Analysis date: 2026-07-20 (scheduled post-market agent, run 08:45 IST pre-open — see §0).
Analyst: Claude (scheduled `session-analysis post`).
Data: `signal_rejections` rows **523** (09:24–15:18 IST bar times), signals fired **3**, paper
positions opened **0**, shadow positions **21** (11 champion + 10 challenger).
Session character: **trend-up CE day.** Every scored bar was CE-side (0 PE composite rows); the
24000CE front-weekly ran 267.05 → 370.80 intraday. VIX 13.15 and rising, breadth 36/14 advances,
ATM IV 12.49%, premium skew −42.4%. NIFTY front future = `NIFTY26JULFUT`; front weekly expiry
2026-07-21 (Tue).

---

## 0 Scheduling note (why this file is dated 07-17, written 07-20)

The scheduled post-market agent fired at **08:45 IST on 2026-07-20**, i.e. *before* that day's
09:15 open, so "today" had no session to analyse. The skill's `post` default is *the most recent
completed session*, which was **Friday 2026-07-17** — and it had no findings file (the folder
contract requires one per session). This file closes that gap. 2026-07-18/19 were the weekend.

**Follow-up (not a finding, an ops item):** the cron currently lands pre-open, not post-market.
Owner should re-point it to ≥16:00 IST.

## 1 Funnel numbers (§3.1–3.2)

| metric | 2026-07-10 | 2026-07-15 | **2026-07-17** |
|---|---|---|---|
| rejections | 701 | 396 | **523** |
| distinct strategies emitting | 35 | 33 | **17** ⚠ |
| published + enabled strategies | 39 | ~63 | **63** |
| signals fired | 9 | 3 | **3** (all `scalp-straddle-nifty`) |
| paper positions opened | — | — | **0** |
| bar-time coverage | 09:19–14:52 | 09:50–15:21 | **09:24–15:18 (full session, no eval stall)** |

No eval stall this session — rejections ran to 15:18 IST against a 15:29 last bar. That is the
first clean full-session coverage since 2026-07-06.

**First-blocking-rail histogram** (523 rows):

| rail | n | avg operand | avg threshold | avg margin |
|---|---|---|---|---|
| volume-floor | **292 (55.8%)** | 27,691.6 | 59,332.8 | −31,641.3 |
| time-window | 149 (28.5%) | — | — | — |
| rsi-band | 17 | 77.25 | — | — |
| confluence-composite | 12 | 0.494 | 0.600 | −0.106 |
| time-of-day-preference | 11 | — | — | — |
| two-candle | 7 | — | — | — |
| pct-price-move | 7 | 0.708 | 1.000 | −0.292 |
| volume-pump / divergence-vol-gate | 6 / 6 | 58,911.7 | — | — |
| oi-cross-required | 3 | 105.1 | — | — |
| (9 more rails) | ≤2 each | | | |

`volume-floor` + `time-window` = **84.3%** of all first-blocks. Same shape as every prior session.

**All-failed-rails expansion** (§3.3, unnested `checks[]`, fail rows only) — top 8:

| rail | policy | fails | avg operand | avg threshold |
|---|---|---|---|---|
| confluence-composite | FAIL_CLOSED | 317 | 0.528 | 0.600 |
| volume-floor | FAIL_CLOSED | 292 | 27,691.6 | 59,332.8 |
| time-window | FAIL_CLOSED | 149 | — | — |
| trend-change | FAIL_CLOSED | 49 | — | — |
| constituent-gate | FAIL_OPEN | 49 | −0.010 | — |
| directional-vix-gate | FAIL_OPEN | 49 | 13.235 | — |
| pct-price-move | FAIL_OPEN | 47 | 0.614 | 1.000 |
| divergence-vol-gate | FAIL_CLOSED | 47 | 33,905.1 | — |

## 2 Rail findings (§3.3 / §3.5 / §3.8)

### 2.1 `volume-floor` — binding at ~p82 of the session's own 3m volume, and it vetoed the day's winners

Ground truth (§3.8), 3m rollup of `NIFTY26JULFUT` 1m bars, 09:15–15:30 IST:

| bars | min | p50 | p90 | p95 | max |
|---|---|---|---|---|---|
| 125 | 7,410 | **26,780** | 77,805 | 120,185 | 347,295 |

The rail is the **RELATIVE** floor armed by [#605](https://github.com/prashantm912/artha-yantra-2/pull/605)
(`k=1.5` × trailing-20-bar median, `minBars=10`, `ScalperGates.relativeVolumeFloor`), which is why the
threshold varies per bar (avg 59,332.8). Placing the average threshold on the session distribution puts it
between p90 (77,805) and p50 — roughly **p80–p85**. The average blocked operand, 27,691.6, is
essentially the session median. So the rail is behaving as designed (a top-quintile expansion filter),
not reading dead data — but it is the single biggest gate in the funnel.

**Counterfactual (shadow book, §5): the bars it blocked were the day's money.** 10 of the 11 champion
shadow positions were `volume-floor`-blocked; they closed **6W/4L for +₹22,344.95 net**. That is the
same signature as 2026-07-06 ("volume floor vetoed WINNERS" on a trend-up day) and the mirror of
2026-07-03 (vetoed only losers on a bleed day). **This remains a REGIME finding, not yet structural** —
it now reads 2-for / 1-against across trend-up vs bleed days.

**Would-have-fired set (§3.5):** **9 rows** passed composite AND had `volume-floor` as their only failed
evaluated rail.

### 2.2 `confluence-composite` — the largest true failure count, mass parked exactly at threshold

317 composite fails (avg 0.528 vs 0.600). The distribution is not a long tail — **202 of 359 scored rows
(56%) sit in the 0.6 bucket**, i.e. at or immediately around the threshold. Combined with §3's dead-dot
cap (§3 below), the composite is doing most of the filtering by a hair.

### 2.3 Rails with no evidence of miscalibration this session

`rsi-band` (avg operand 74.1 on a trending-up day — correctly hot), `oi-divergence-magnitude`
(22.0 vs 20.0), `pct-price-move` (0.614 vs 1.000), `psar-durability` (0.028 vs 0.050),
`vwap-distance` (0.004 vs 0.004) all read plausibly. No order-of-magnitude gaps.

## 3 Composite + dots (§3.4 / §3.6)

**Composite histogram** (359 scored rows, all CE, zero PE):

| bucket | 0.2 | 0.3 | 0.4 | 0.5 | **0.6** | 0.7 |
|---|---|---|---|---|---|---|
| n | 4 | 19 | 36 | 90 | **202** | 8 |

No 0.8+ bucket. Threshold 0.600.

**Dot support rates** (359 rows unless noted):

| dot | w | supports | % | read |
|---|---|---|---|---|
| `iv_abs_band` | 0.8 | 0/49 | **0.0** | DEAD — ATM IV 12.49% just above the "10–12 trend-play" band |
| `vix` | 1.0 | 0/359 | **0.0** | data ALIVE (13.15, rising) — 0% is directional, VIX rose all day vs CE |
| `iv_rank` | 0.8 | 0/359 | **0.0** | DEAD-DATA — `ivRank` NULL on 523/523 rows |
| `iv_pair` | 0.8 | 0/359 | **0.0** | DEAD — ceIvAvg6 0.1176 vs peIvAvg6 0.1180, gap 0.0004 ≪ 0.02 threshold |
| `basis` | 1.0 | 0/359 | **0.0** | DEAD — investigate (no basis field surfaced in `context.macro`) |
| `oi_spurt` | 1.0 | 9/359 | 2.5 | alive but rare (was 1.6% on 07-15 post-#675/#676) |
| `volume` | 1.0 | 14/359 | 3.9 | mirrors §2.1 |
| `sentiment_slope` | 1.0 | 101/359 | 28.1 | |
| `trending_cross` | 1.0 | 146/359 | 40.7 | |
| `underlying_oi` | 1.0 | 186/359 | 51.8 | |
| `rsi` | 1.0 | 194/359 | 54.0 | |
| `futures_oi` | 1.5 | 205/359 | 57.1 | |
| `psar` | 1.0 | 253/359 | 70.5 | |
| `sentiment` | 1.0 | 273/359 | 76.0 | |
| `breadth` | 1.0 | 276/359 | 76.9 | alive (REGIME up-day) |
| `drastic_oi` | 1.0 | 308/359 | 85.8 | |
| `iv_slope` | 0.8 | 45/49 | 91.8 | |
| `supertrend` | 1.0 | 338/359 | 94.2 | |
| `vwma` | 1.0 | 346/359 | 96.4 | |
| `premium_skew` | — | 15/15 | 100.0 | small n |
| `vwap` | 2.5 | 359/359 | **100.0** | FREE — the heaviest dot never discriminated |

**Dead-weight cap math.** Common-dot Σw = 2.5 (vwap) + 1.5 (futures_oi) + 0.8 (iv_rank) + 0.8 (iv_pair)
+ 14 × 1.0 = **19.6**. Permanently-dead weight this session = iv_rank 0.8 + iv_pair 0.8 + vix 1.0 +
basis 1.0 = **3.6**.

- max achievable composite = (19.6 − 3.6) / 19.6 = **0.8163**
- threshold 0.600 ⇒ effective bar on the LIVE dots = 0.600 / 0.8163 = **0.735**

Not structurally unpassable (cap 0.816 > 0.600), but **18.4% of the composite's weight was inert**, and
the live dots must clear 73.5% rather than 60%. Same cap as 2026-07-06 (0.816) — no regression, no
improvement.

**`vwap` at 100/100 is the mirror-image problem to a dead dot:** the single heaviest weight (2.5,
12.8% of Σw) contributed zero discrimination all session. Flagging as a new watch item — a dot that
always supports inflates every composite equally and is indistinguishable from lowering the threshold
by 0.128.

## 4 Data health (§3.7)

| field | result (523 rows) | class |
|---|---|---|
| `ivRank` | **NULL 523/523 (100%)** | dead-data — known since 07-02, still dead |
| `fiiLongPct` | **NULL 523/523 (100%)** | dead-data — `fiiBiasSign` present (=1), the pct is not |
| `dowUp` | NULL 523/523 | **by design** (Dow un-armed) |
| `vix` / `vixLevel` | 0 nulls, 0 zeros (13.15) | HEALTHY |
| breadth `advances`/`declines` | 0 zero-pairs (36/14) | HEALTHY (revived #486, still alive) |
| `atmIv`, `ceIvAvg6`, `peIvAvg6`, `ceIvSlope`, `peIvSlope` | populated | HEALTHY |
| `premiumSkewPct`, `constituentBias`, `fiiBiasSign` | populated | HEALTHY |

**Capture was flawless.** `NIFTY26JULFUT` / AUG / SEP = 375/375 1m bars each; `SENSEX26JULFUT` /AUG =
375, SEP 372. Chain snapshots: NIFTY 50 362, SENSEX 361, plus BANKEX/BANK/FINSERVICE/MIDSELECT ~188 each,
09:19→15:32 IST. SENSEX front weekly rolled correctly 2026-07-16 → 2026-07-23.

## 5 Shadow-book outcomes

**Exit-fidelity caveat (standing):** indicator-driven exits (trend-flip / signal-exit) are NOT replicated —
premium brackets, structural stop and 15:12 square-off only. Rejections blocked before leg resolution
(time-window, chain, straddle path) never shadow.

**Champion book — 11 closed, 7W/4L, +381.90 pts, +₹23,946.92 net (costs ₹876.58).** Best session on record.

| bar (IST) | strategy | leg | entry | exit | close | pts | % | net ₹ | blocked by |
|---|---|---|---|---|---|---|---|---|---|
| 09:45 | market-movers | 24000CE | 267.05 | 248.70 | STRUCTURAL_STOP | −18.35 | −6.9 | −1,270.54 | volume-floor |
| 09:45 | connect-the-dots | 24000CE | 267.05 | 331.10 | SQUARE_OFF | +64.05 | +24.0 | +4,077.89 | volume-floor |
| 09:45 | two-candle | 24000CE | 267.05 | 331.10 | SQUARE_OFF | +64.05 | +24.0 | +4,077.89 | volume-floor |
| 09:45 | gap-theory | 24000CE | 267.05 | 370.80 | **TAKE_PROFIT** | +103.75 | +38.9 | +6,654.75 | volume-floor |
| 09:45 | trend-change | 24000CE | 267.05 | 331.10 | SQUARE_OFF | +64.05 | +24.0 | +4,077.89 | volume-floor |
| 09:45 | open-high-low | 24000CE | 267.05 | 331.10 | SQUARE_OFF | +64.05 | +24.0 | +4,077.89 | volume-floor |
| 10:15 | trending-oi | 24000CE | 265.80 | 331.10 | SQUARE_OFF | +65.30 | +24.6 | +4,159.18 | volume-floor |
| 10:15 | golden-crossover | 24000CE | 265.80 | 240.40 | STRUCTURAL_STOP | −25.40 | −9.6 | −1,727.99 | volume-floor |
| 10:15 | market-movers | 24000CE | 265.80 | 240.40 | STRUCTURAL_STOP | −25.40 | −9.6 | −1,727.99 | volume-floor |
| 14:15 | market-movers | 24050CE | 264.20 | 290.10 | SQUARE_OFF | +25.90 | +9.8 | +1,601.97 | pct-price-move |
| 14:45 | hero-zero | 24950CE | 2.70 | 2.60 | SQUARE_OFF | −0.10 | −3.7 | −54.02 | volume-floor |

⚠ **CORRELATION CAVEAT — read this before citing the +₹23.9k.** The 11 positions are **not 11
independent edges**. Six are the *same leg at the same bar* (24000CE @ 267.05, 09:45) taken by six
different strategies; three more are the same leg at 10:15. Deduplicated to distinct entry events:

| event | positions | outcome |
|---|---|---|
| 09:45 24000CE @267.05 | 5 | 4W/1L (TP hit once) |
| 10:15 24000CE @265.80 | 3 | 1W/2L |
| 14:15 24050CE @264.20 | 1 | W |
| 14:45 24950CE @2.70 | 1 | L |

**2 of 4 distinct entry events won.** The headline PnL is one good trade idea multiplied across the
strategy fleet, not a 64%-win-rate book. Any tune justified from this session must be justified from
the 4 events, not the 21 rows.

**Per-rail attribution (champion, closed):**

| blocking rail | n | wins | net ₹ | avg % |
|---|---|---|---|---|
| volume-floor | 10 | 6 | **+22,344.95** | +13.0 |
| pct-price-move | 1 | 1 | +1,601.97 | +9.8 |

**Variant league — this session:**

| variant | closed | wins | net wins | pts | net ₹ | cost ₹ |
|---|---|---|---|---|---|---|
| champion | 11 | 7 | 7 | 381.90 | **+23,946.92** | 876.58 |
| composite-055 | 4 | 2 | 2 | 36.90 | +2,062.95 | 335.55 |
| vol-12k5 | 3 | 2 | 2 | 46.10 | +2,747.15 | 249.35 |
| vol-off | 3 | 2 | 2 | 46.10 | +2,747.15 | 249.35 |

**Challenger-only entries (the true delta — rows a variant took that champion did not):**

| variant | leg | bar | pts | net ₹ |
|---|---|---|---|---|
| composite-055 | 24000CE | 10:21 | +36.10 | +2,260.33 |
| composite-055 | 24000CE | 10:21 | −21.15 | −1,455.67 |
| composite-055 | 24000CE | 12:48 | +33.10 | +2,065.24 |
| composite-055 | 24000CE | 12:48 | −11.15 | −806.95 |
| vol-12k5 | 24000CE | 10:21 | +36.10 | +2,260.33 |
| vol-12k5 | 24000CE | 10:36 | +35.40 | +2,214.81 |
| vol-off | 24000CE | 10:21 | +36.10 | +2,260.33 |
| vol-off | 24000CE | 10:36 | +35.40 | +2,214.81 |

Loosening the volume floor (`vol-12k5` ≡ `vol-off` this session) added **+₹4,475 across 2 entries, 2/2
wins**. Loosening composite to 0.55 added **+₹2,063 across 4 entries, 2/4 wins** — i.e. composite-055
took the same 10:21 entry *and* two losers the tighter books avoided.

**Cumulative league (all sessions, for scale):**

| variant | closed | wins | pts | net ₹ |
|---|---|---|---|---|
| champion | 77 | 26 | −499.70 | **−38,251.21** |
| vol-off | 12 | 4 | −37.80 | −3,400.90 |
| vol-12k5 | 6 | 2 | +28.15 | +1,348.76 |
| composite-055 | 6 | 2 | +17.85 | +654.89 |

**The champion book is still ₹38k underwater cumulatively.** 2026-07-17 was its best day; it does not
reverse the verdict.

**Entry latency (F8):** p50 **75.9s**, p95 **95.0s** (21 positions). Historically stable
(07-03 73s/85s · 07-06 87s/105s · 07-10 83s/93s · 07-15 73s/170s), so this is structural, not new —
but it is far above the README's ~5 s flag line, and every shadow entry price is a ~75-second-late
fill relative to `bar_time`. Treated as a standing caveat on all shadow PnL, tracked in §7.

## 6 New data points / anomalies

### 6.1 ⚠ NEW ALARM — strategy coverage collapsed: 17 of 63 published strategies emitted anything

| session | distinct slugs | of which `%sensex%` | of which `%-pe` |
|---|---|---|---|
| 2026-07-10 | 35 | 16 | 24 |
| 2026-07-15 | 33 | 14 | 21 |
| **2026-07-17** | **17** | **4** | **6** |

Registry state: **63 published + enabled** scalpers, of which **42 are `%sensex%`**. On 07-17 only
**4 sensex slugs** emitted rejections — and all four are `-pe` variants that emitted **exactly 2 rows
each, at the identical two bars (10:48 and 12:42 IST)**. The 38 sensex CE variants emitted **zero rows
all session**.

Excluded as causes (verified):
- **Not a capture failure.** SENSEX chain 361 snaps 09:20→15:32, front weekly correctly rolled to
  2026-07-23; `SENSEX26JULFUT` 375/375 1m bars.
- **Not an instrument-resolution split.** All 523 rows are `NFO` / `NIFTY26JULFUT` — per ADR-0003 the
  sensex variants *signal* on the NIFTY future and only *execute* on BFO, so a silent sensex family is
  not explained by a BFO problem.
- **Not an eval stall.** Rejections ran 09:24→15:18.

**Cause UNVERIFIED.** 07-17's `ay-strategy-signal-service` logs are gone (container restarted 08:35 IST
2026-07-20), so the load line for that day cannot be read. The two live hypotheses are (a) a partial
load — the 2026-07-16 §6.3 standing check says *the honest health signal is `unresolved == 0`, never
`loaded > 0`* — or (b) a legitimate side-selection effect where the PE/sensex fleet simply never had a
PE-directional setup on an all-CE day. Hypothesis (b) explains the `-pe` slugs but **does not explain
38 silent sensex CE variants**, so (a) is the leading candidate.

**Action for the next session (cheap, decisive):** capture the engine's boot line
(`signal engine loaded N published strategies (M dropped …)`) into the findings file every run, and
add a per-session `count(DISTINCT strategy_slug)` vs `count(published+enabled)` ratio to the funnel
table. Promoted to README §3 as dimension **§3.10**.

### 6.2 3 signals fired, 0 paper positions opened

All three fires were `scalp-straddle-nifty` on `NIFTY26JULFUT` 3m:

| IST | side | tradeable | composite |
|---|---|---|---|
| 09:48 | BUY | NIFTY2672124200CE | 1.000 |
| 11:18 | SELL | *(none)* | 1.000 |
| 14:15 | BUY | NIFTY2672124300CE | 0.845 |

`strategy.paper_positions` has **zero rows opened on 07-17**. Consistent with the 07-07/07-10 sessions
where straddle fires were recorded advisory-only, but worth confirming it is intentional rather than a
silent paper-book refusal. The 11:18 SELL carries no `tradeable_tradingsymbol` at all.

### 6.3 ⚠ CARRIED — 18 paper positions still OPEN since 2026-07-16

`strategy.paper_positions`: 18 OPEN (newest `opened_at` **2026-07-16 20:00:05 IST**), 5 CLOSED (newest
2026-07-12). These are the same 18 left unmanaged by the 07-16 F10 cold-start incident. Two full
sessions later they are **still open** — they were not squared off, not stopped out, not aged out.
This is now an open-position hygiene problem independent of the incident that created it.

### 6.4 `vwap` dot supported 100% of 359 rows

New watch item (see §3). The heaviest single dot (w=2.5, 12.8% of Σw) discriminated nothing.
Symmetric to a dead dot: a permanently-supporting dot is an unlabelled threshold reduction.

### 6.5 F10 Part A (#874) confirmed working on a real cold start

Not a 07-17 datum — observed while reading today's (07-20) logs, recorded here because it closes the
07-16 file's open question ("the channel-listener path is not yet exercised live"):

```
03:05:16.404Z  signal engine loaded 0 published strategies (63 dropped on an unresolved universe, 0 failed to load)
03:06:51.687Z  signal engine loaded 63 published strategies (0 dropped on an unresolved universe, 0 failed to load)
03:11:01.666Z  signal engine reload unchanged (63 loaded, 0 unresolved, 0 load errors)
```

Cold boot at 08:35:16 IST loaded **0 of 63**; the self-heal chain recovered to **63 loaded / 0
unresolved** 95 seconds later, ~39 minutes before the open. The fix works on a real startup, not just
the drill.

## 7 Tuning candidates

Carried forward from prior sessions plus this session's new rows. Nothing here is applied — every row
is a PROPOSAL for the owner.

| # | knob | current | proposed | evidence | class | status |
|---|---|---|---|---|---|---|
| T1 | `artha.scalper.oi.relativeVolumeMultiplier` (volume-floor `k`) | 1.5 | 1.2 (or 1.0) | 07-17: rail was 55.8% of first-blocks; blocked trades = +₹22,345 / 6W-4L (2 of 4 distinct events won); `vol-off`+`vol-12k5` delta +₹4,475 2W/2L. Counter-evidence 07-03 (vetoed only losers). Cumulative `vol-off` −₹3,401 vs `vol-12k5` +₹1,349 | **REGIME** (2 for / 1 against) | **PROPOSED** — do NOT apply on one session; `vol-12k5` is the better-behaved challenger, keep both books running |
| T2 | `iv_rank` dot | weight 0.8, `ivRank` NULL 100% since 07-02 | either source ivRank or drop the dot from Σw | dead-data 523/523 across every session logged | **STRUCTURAL** | **PROPOSED** (carried, unchanged since 07-02) |
| T3 | `iv_pair` gap threshold | 0.02 (recalibrated #675/#676) | ~0.005 | 07-17 gap 0.0004; 0/359 support; still 0% after the recalibration that was supposed to revive it | **STRUCTURAL** | **PROPOSED** (carried from 07-15, now confirmed twice) |
| T4 | `basis` dot | w 1.0, 0/359 support | investigate — no basis operand surfaced in `context.macro` | 0% support with no visible input field | **STRUCTURAL (suspected dead-data)** | **PROPOSED — NEW 07-17** |
| T5 | `iv_abs_band` band | 10–12 | widen to 10–13 | ATM IV 12.49% sat just outside; 0/49 | **REGIME** (single session) | **PROPOSED — NEW 07-17**, collect more |
| T6 | `vwap` dot weight | 2.5 | re-examine (either narrow the support condition or cut weight) | 359/359 support = zero discrimination at the largest weight | **STRUCTURAL** | **PROPOSED — NEW 07-17** |
| T7 | composite threshold | 0.600 | no change | `composite-055` delta 2W/2L +₹2,063 — it bought the same winner plus two losers | — | **REJECTED for now** (keep the challenger book running) |
| T8 | shadow entry latency | p50 ~76s, structural | build: stamp entry at bar close, not +76s | consistent across 5 sessions; README flags p95 > 5s | **STRUCTURAL (data-model)** | **PROPOSED** → README §7 backlog |
| T9 | strategy-coverage watchdog | none | alert when `distinct emitting slugs / published+enabled` drops below the prior session's ratio | §6.1: 35 → 33 → **17** of 63, undetected | **STRUCTURAL** | **PROPOSED — NEW 07-17, highest priority** |
| T10 | 18 stale OPEN paper positions | open since 07-16 | owner decision: square off / age out / investigate | §6.3 | ops | **OWNER** |

## 8 Honesty caveats

- **The +₹23,946.92 champion session is 4 distinct entry events, not 11.** Six positions are one leg at
  one bar. Do not cite the row count as a win rate.
- Shadow exits replicate brackets / structural stop / square-off only — **no indicator-driven exits**.
- Every shadow entry is stamped ~76 s after `bar_time` (§5). The fills are late relative to the bar the
  gate scored; direction of the bias is unknown and unmeasured.
- 07-17 is a **single trend-up day**. §2.1's "the floor blocked winners" is the same claim 07-06 made and
  the opposite of 07-03. Two-for-one is not a mandate.
- §6.1's cause is **unverified** — the container logs for that day no longer exist. The finding (coverage
  collapsed) is measured; the explanation is a hypothesis.
- No PE-side composite rows exist at all this session, so nothing here says anything about PE behaviour.
- All costs are the 1-lot engine fill model (statutory + ₹20/lot). Not cost-adjusted twice.
