# Live in-session data-health check — 2026-07-29 (data date)

Analysis date: 2026-07-29, ran 09:43–12:45 IST (scheduled `session-analysis live`, doc written on
owner ask mid-session). Analyst: Claude (scheduled live-data-health task). **Read-only run** — no
restarts, no writes, no deploys during market hours.
Verdict: **GREEN.**

Scope note: this is the mid-session §4.1 data-health watch. The evening `session-analysis post` run
owns `2026-07-29-session-findings.md` and should fold this in. Numbers are as of **12:43 IST** unless
a sample time is given; the session was still running when this was written.

**Two sibling runs cover the same session from other angles** — the market-open gate
([`2026-07-29-open-gate.md`](2026-07-29-open-gate.md), PR #1101) and the midday liveness gate
([`2026-07-29-midday-gate.md`](2026-07-29-midday-gate.md), PR #1102), both PASS. Those are §4.3
liveness gates; **this** file is the §4.1 data-health watch, and the material it does not duplicate is
§3 (shadow books), §4.1 (dot support rates), §6 (the paper-book outcomes and the ₹15,000 sizing
evidence) and §7 (contract derivation, phantom-candle and null probes). Where the three overlap they
agree: this run's 12:43 sample (Σ 2,100 / 854 rejections / 6 fires) sits ~3 min after the midday
gate's 12:39 read and is consistent with it.

2026-07-29 is an **ordinary Wednesday** — no NSE or BSE monthly expiry — so the whole OI bloc is live
again and yesterday's S24 suppression (`2026-07-28-live-health.md` §6) is gone.

---

## 0 Verdict

**GREEN.** Both machine canaries healthy, engine receiving *and* evaluating bars, capture full, no
data-integrity probe failed, no health event. Nothing for the owner to act on **today**.

Two things worth carrying, one decision-relevant:

1. **The engine fired 6 entries and the paper book took 2 positions — the first live fires since the
   ₹15,000-budget sizing landed, and they produced direct evidence for the HELD #1075 budget raise**
   (§6). Every **NIFTY** leg was rejected `computedLots=0` because one lot costs more than the whole
   per-sub-account budget (`premium=285.25 lot=65 budget=15000` ⇒ **₹18,541 needed vs ₹15,000
   available**), while the **SENSEX** legs funded fine (₹482.05 × 20 = ₹9,641). This is exactly the
   live data the owner deferred the decision to 2026-08-12 to collect. **Not acted on — reported only.**
2. **`iv_abs_band` came back ALIVE at 103/103** after eight dead sessions (§4.1). Second dot revival in
   three sessions (`fii` was 07-28). The standing dead set is unchanged at `ivRank` + `dowUp`.

## 1 Preconditions

| check | result |
|---|---|
| Host-clock guard (B8) | Host `[DateTime]::UtcNow` = **2026-07-29 04:13:04**, container `now()` = **04:13:05.083**. **~1 s** apart — no ⚠ CLOCK-DRIFT line, container time not substituted. |
| Trading day / in-session | Wed 2026-07-29, first read **09:43 IST**, inside 09:15–15:30. **No monthly expiry** (NSE was Tue the 28th, BSE is Thu the 30th). |
| Stack | **11/11** `ay-*` containers healthy. strategy-signal up ~8 h, booted 2026-07-28T20:25:31Z (01:55 IST), no restart in-session. |
| Engine load (§3.10) | Boot line: `signal engine loaded 38 published strategies (0 dropped on an unresolved universe, 0 failed to load)`. `strategy.strategies` enabled+published = **44** = **38 scalpers + 6 non-scalper** (swing, batch engine). **Full scalper coverage — no T9 shortfall.** |

## 2 Check 1 — engine liveness (counters, never `signal_rejections`)

| sample (IST) | `chart-gate-failed` | `composite-below-threshold` | `confluence-blocked` | `fired` | **Σ** | `..._received_age_s` | `..._evaluated_age_s` |
|---|---|---|---|---|---|---|---|
| 09:43:17 | 18 | 0 | 18 | 0 | **36** | 29.890 | 29.737 |
| 09:51:13 | 58 | 0 | 50 | 0 | **108** | 13.220 | 13.212 |
| 12:43:19 | 1,178 | 62 | 854 | **6** | **2,100** | 2.884 | 2.878 |

`ay_signal_eval_failures_total` = **0** at all three samples. `discipline-paused`,
`confluence-gate-absent`, `unscoreable-indicators-warming` = 0 throughout.

**Σ ADVANCING 36 → 108 → 2,100 ⇒ engine alive.** Both gauges fresh and non-negative at every sample
(no `-1` "no bar this boot", no sub-`-1` clock fault) — README §4.3's *received fresh + evaluated
fresh* row. The two spaced samples deliberately straddle a bar boundary **after 09:45** per README
§4.1, so the +72 delta is not the trade-window ramp artefact.

Corroborator — `strategy.signal_eval_outcomes`, latest bucket only (never a session-wide `sum()`):
**12:39 → 32**, 12:36 → 32, 12:33 → 32. Fresh, non-zero, landing every 3 min. The documented
window ramp reproduced again: 09:42 → 4, **09:45 → 36**, as 2 in-window slugs became 16.

`strategy.subscriber_health_events` since 09:15: **0 rows**. ⚠️ Per README §4.3 that absence proves
nothing on its own — it is recorded as consistent-with, not as evidence.

### 2.1 Interior coverage buckets (§3.11 — never certify from min/max)

| 15-min bucket (IST) | 09:15 | 09:30 | 09:45 | 10:00 | 10:15 | 10:30 | 10:45 | 11:00 | 11:15 | 11:30 | 11:45 | 12:00 | 12:15 | 12:30 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| rows | 8 | 10 | 80 | 68 | 90 | 90 | 70 | 61 | 65 | 40 | 90 | 67 | 75 | 40 |
| slugs | 2 | 2 | 16 | 16 | 18 | 18 | 18 | 18 | 15 | 18 | 18 | 16 | 16 | 16 |

**No interior hole** — every bucket populated, 14/14. The 8/10-row opening buckets are the
pre-09:45 trade window (only the `morning-trade` pair in-window), not a gap.

Funnel context (not the liveness signal): **854 rejections / 18 slugs**, composite **0.2128–0.8511**,
**178 rows passed composite**. First-blocking-rail histogram is dominated by one rail:

| rail | rows | share |
|---|---|---|
| `volume-floor` | **643** | **75.3%** |
| `time-window` | 102 | 11.9% |
| `two-candle` / `volume-pump` / `rsi-band` / `pct-price-move` | 12 each | 5.6% total |
| `divergence-vol-gate` / `time-of-day-preference` | 10 each | 2.3% |
| `oi-cross-required` / `directional-change-gate` | 8 each | 1.9% |

`volume-floor` at three quarters of all first-blocks, **with the relative floor armed**, is the one
number the EOD run should chase (§9).

## 3 Check 2 — shadow book + variants

Opened today (all four books writing):

| variant | opened | OPEN | closed | closes with NULL `pnl_net` |
|---|---|---|---|---|
| `champion` | 22 | 14 | 8 | **0** |
| `vol-off` | 5 | 1 | 4 | **0** |
| `vol-12k5` | 3 | 1 | 2 | **0** |
| `composite-055` | 1 | 1 | 0 | **0** |

**No close carries a null `pnl_net`** ⇒ no NFO/BFO lot-size lookup failure (the F8 flag this check
exists for).

League (`GET /api/v1/signal-rejections/shadow-summary`, all-time, 12:43):

| variant | open | closed | wins | pnl points | **pnl NET ₹** | unpriced |
|---|---|---|---|---|---|---|
| `champion` | 14 | 227 | 92 | −367.65 | **−39,440.37** | 0 |
| `vol-off` | 1 | 52 | 14 | −458.05 | **−19,435.23** | 0 |
| `vol-12k5` | 1 | 40 | 12 | −272.35 | **−11,005.21** | 0 |
| `composite-055` | 1 | 11 | 3 | −10.20 | **−1,542.15** | 0 |

`unpriced = 0` across all four books. All four still negative; `vol-off` remains the worst per-close.

## 4 Machine canaries (STEP 0)

| canary | result |
|---|---|
| `GET /api/v1/market/health/data` | `{"status":"GREEN","marketOpen":true,"tickedTokens":105,"problems":[]}` at both 04:13Z and 07:13Z — data plane machine-verified, `tickedTokens > 0`. |
| `GET /api/v1/signal-rejections/dot-health` | 09:43: 18 scanned / 4 context-bearing. 12:43: 200 scanned / 40 context-bearing. **dead at both: `iv_rank`, `dow` only** (both `required:false`). **alive: `breadth`\*, `fii`, `oi_spurt_price`, `vix`, `futures_oi`\*, `underlying_oi`\***. (\* = required) |

**Dead set matches the standing ledger exactly** (`2026-07-28-session-findings.md` §4: the pair
`ivRank` + `dowUp`). **Nothing newly dead.** `fii` held alive for a second session (#1050 confirmed).
`oi_spurt_price` and both OI quadrant dots are back **alive** — expected, yesterday's 0/16 was the
monthly-expiry S24 suppression, not an outage, and today is the control that proves it.

### 4.1 `iv_abs_band` came back ALIVE — 103/103 after eight dead sessions

`2026-07-28-session-findings.md` §3 carries `iv_abs_band` at **0/180, dead (8th session)**. Today it
supports on **103 of 103** rows that carry it. Dot support, all 722 dot-bearing rows (`iv_abs_band` /
`iv_slope` appear on 103):

| dot | supports | pct | dot | supports | pct |
|---|---|---|---|---|---|
| `basis` | 722/722 | 100% | `sentiment_slope` | 309/722 | 42.8% |
| `breadth` | 722/722 | 100% | **`iv_abs_band`** | **103/103** | **100%** |
| `vix` | 722/722 | 100% | `volume` | 93/722 | 12.9% |
| `supertrend` | 722/722 | 100% | `oi_spurt` | 71/722 | 9.8% |
| `vwma` | 630/722 | 87.3% | `vwap` | 35/722 | 4.8% |
| `drastic_oi` | 624/722 | 86.4% | `iv_slope` | 5/103 | 4.9% |
| `psar` | 502/722 | 69.5% | `trending_cross` | **0/722** | 0% |
| `sentiment` | 475/722 | 65.8% | `iv_pair` | **0/722** | 0% |
| `rsi` | 434/722 | 60.1% | `iv_rank` | **0/722** | 0% |
| `underlying_oi` | 385/722 | 53.3% | | | |
| `futures_oi` | 366/722 | 50.7% | | | |

⚠️ **`iv_abs_band` at exactly 100% is a *free* dot, not a healthy one** — README §3.6 flags 0% and
~100% as the same class of finding. The EOD run should decide which it is (threshold outside the
operand's range vs a genuinely one-sided IV day) before anyone treats the revival as good news.

`volume` at **12.9%** is its second non-zero session (07-28: 3.6%), consistent with README §3.20 —
the dot resolves the **static** 125,000 floor at `ConnectTheDotsScorer.java:141`, never the armed
relative floor, so it tracks how thick the tape is rather than any tuning. `trending_cross` dead a
further session on live OI (not an expiry artefact this time — worth an EOD look).

## 5 Check 5 — EXT-02 Upstox rate budget

`docker logs ay-market-data-service --since 2026-07-29T03:45:00Z`: **0** matches for
rate-budget / acquire-failure / rate-limit, **0** `unpriced` reasons, **0** `future-stamped tick`
drops. No rate-starvation of the armed F9 margin path; the open−30 min batch-lane pause is holding.

## 6 NEW — first fires under the ₹15,000 budget, and what they cost

### 6.1 Six entries fired, three slugs

`ay_signal_eval_outcome_total{outcome="fired"} = 6`, and `strategy.signals` carries **10** rows for
the session — 6 BUY entries in two waves plus 4 SELL signal-exits:

| IST | slug | side | composite | leg | status |
|---|---|---|---|---|---|
| 11:06 | `scalp-connect-the-dots-sensex-niftyoi` | BUY | 0.8569 | `SENSEX26JUL77200CE` (qty 20) | EXPIRED |
| 11:06 | `scalp-golden-crossover-sensex-niftyoi` | BUY | 0.8569 | `SENSEX26JUL77200CE` (qty 20) | EXPIRED |
| 11:06 | `scalp-golden-crossover-nifty` | BUY | 0.8569 | `NIFTY2680424000CE` (**qty NULL**) | EXPIRED |
| 11:36 | `scalp-connect-the-dots-sensex-niftyoi` | SELL | 0.8569 | — | ACTIVE |
| 11:39 | `scalp-golden-crossover-nifty` | SELL | 0.8569 | — | ACTIVE |
| 12:12 | `scalp-connect-the-dots-sensex-niftyoi` | BUY | 0.8429 | `SENSEX26JUL77200CE` (qty 20) | EXPIRED |
| 12:12 | `scalp-golden-crossover-sensex-niftyoi` | BUY | 0.8429 | `SENSEX26JUL77200CE` (qty 20) | EXPIRED |
| 12:12 | `scalp-golden-crossover-nifty` | BUY | 0.8429 | `NIFTY2680424000CE` (**qty NULL**) | EXPIRED |
| 12:18 | `scalp-golden-crossover-sensex-niftyoi` | SELL | 0.8429 | — | ACTIVE |
| 12:18 | `scalp-golden-crossover-nifty` | SELL | 0.8429 | — | ACTIVE |

All entries signalled off `NFO:NIFTY26AUGFUT` (contract derived in §7).

### 6.2 Paper book: 2 positions, both losers, −₹1,897.38

| id | leg | qty | entry | closed (IST) | reason | realized ₹ |
|---|---|---|---|---|---|---|
| 41 | `SENSEX26JUL77200CE` | **40** | 482.05 | 11:37:20 | `TIME_STOP` | **−971.06** |
| 42 | `SENSEX26JUL77200CE` | **40** | 498.05 | 12:19:17 | `STRUCTURAL_STOP` | **−926.32** |

**`qty 40` on a 20-lot SENSEX contract is the documented pyramiding merge, not a sizing bug** — two
slugs (`connect-the-dots-sensex-niftyoi` + `golden-crossover-sensex-niftyoi`) emitted the *same*
`(book, exchange, tradingsymbol, side)` in the same wave, and `PaperService.openPosition` averages a
second open into the first rather than rejecting it. Expected behaviour; noted so the EOD run does
not read it as a double-size defect.

### 6.3 ⚠️ Every NIFTY leg was unfundable — live evidence for the HELD #1075 budget raise

Both `scalp-golden-crossover-nifty` fires resolved a leg and then took **no** paper position. The
decisive log line (`PaperEmissionGuard`, WARN, thread `signal-eval`):

```
paper ENTRY zero-sized: strategy=scalp-golden-crossover-nifty book=scalper
  symbol=NFO:NIFTY2680424000CE premium=285.25 lot=65 budget=15000 computedLots=0
```

Twice today, `premium` 285.25 and 288.65:

| leg | premium | lot | one lot costs | budget | lots |
|---|---|---|---|---|---|
| `NIFTY2680424000CE` | 285.25 | 65 | **₹18,541** | ₹15,000 | **0** |
| `NIFTY2680424000CE` | 288.65 | 65 | **₹18,762** | ₹15,000 | **0** |
| `SENSEX26JUL77200CE` | 482.05 | 20 | ₹9,641 | ₹15,000 | 1 |

**The NIFTY 65-lot at a ₹285 ATM premium does not fit inside a ₹15,000 sub-account at all**, so the
NIFTY-rooted half of the scalper fleet is currently unfundable on any comparable tape, while
SENSEX-rooted legs fund at 1 lot. This is precisely the class of live evidence the owner deferred
[#1075](https://github.com/prashantm912/artha-yantra-2/pull/1075) (the ₹15,000 → ₹20,000
`budget_inr` raise) to **2026-08-12** to collect — task `revisit-scalper-budget-inr-2026-08-12`.

**Reported, not acted on.** No knob was touched: the raise stays the owner's call, and the arithmetic
cuts both ways — the memoed concurrency cliff is exactly at ₹15,000 (above it each ₹30,000
sub-account holds 1 position, not 2, so concurrency drops 8 → 5). Today adds one data point on the
"raise it" side; the decision still needs the fuller window.

## 7 Data-integrity probes

| probe | result |
|---|---|
| **§3.18 signal contract — DERIVED, not assumed** | `context.chart.close` spans **24,258.60–24,278.00**. `NIFTY26AUGFUT` 1m day range **24,222.40–24,319.60** contains it; `NIFTY26SEPFUT` **24,319.50–24,440.00** excludes it. ⇒ contract is **`NIFTY26AUGFUT`** (the July roll, unchanged since 07-27). |
| §3.15 misaligned (phantom) 1m candles since 09:15 | **0 rows.** No feed outage, so no phantom-bar inflation of the `volume-floor` operand — which matters more than usual given `volume-floor` is 75% of first-blocks (§2). |
| §3.12 OI quadrant liveness | `futuresQuadrant = NEUTRAL` on **0 / 736** context-bearing rows. Fully live — yesterday's 16/16 NEUTRAL was the expiry suppression and today is its control. |
| §3.7 data-health nulls (736 context-bearing rows) | `ivRank` NULL **736/736** (dead-data, carried since 07-02) · `dowUp` NULL **736/736** (by design, un-armed) · `fiiLongPct` NULL **0/736** ✅ · `spurtPricePct` NULL **0/736** ✅ · no `advances`/`declines` zero-pair. |
| Capture freshness | `NIFTY26AUGFUT` **209** minute-aligned 1m bars since 09:15, max bucket **12:43 IST** at wall-clock 12:43 — full coverage, no interior gap. |
| Options-chain capture | 88,322 snapshots / 28 distinct minutes by 09:44, last 09:44 (3-min cadence, on schedule). |

## 8 Honesty notes / gaps in this run

- **The two engine-counter samples ~8 min apart are the load-bearing pair** (09:43 → 09:51, Σ 36 →
  108). The third sample at 12:43 is three hours later and is corroboration, not the spaced read.
- **No counterfactual (§4.2) was run.** The session was still live when this was written; the
  would-have-fired set and its premium paths belong to the EOD run.
- **The `volume-floor` 75.3% share is reported, not diagnosed.** No ground-truth percentile query was
  run against the AUGFUT 3m volume distribution — that is §3.8 work for the EOD run, and the contract
  is now derived (§7) so it can be done correctly.
- **`iv_abs_band` at 100% is flagged, not explained.** Whether it is a revival or a free dot needs the
  scorer call-site read that README §3.20 prescribes.
- The `fired`/paper section reads the live log stream and the live tables; both were verified
  non-empty, so the absences quoted (no rate-budget lines, no health events) are real absences rather
  than a dead pipe — except `subscriber_health_events`, whose emptiness proves nothing by design.

## 9 Carry into the evening `post` run

1. **Chase `volume-floor` = 75.3% of first-blocks** (§2) with the armed relative floor in place — run
   the §3.8 ground-truth percentiles against **`NIFTY26AUGFUT`** (contract derived, §7) and place the
   per-slug thresholds on it. Pair with the §3.14 published-config check that the
   `relative-volume-floor` tag is still armed on all 38.
2. **Resolve `iv_abs_band` 103/103** (§4.1): revival or free dot? Read its `add(dots, …)` call site
   per README §3.20 before recording it either way.
3. **`trending_cross` 0/722 on a fully-live OI day** (§4.1) — yesterday's expiry excuse does not apply.
   Check whether it is threshold or data.
4. **Fold §6.3 into the #1075 evidence base** for the 2026-08-12 decision: how many NIFTY-rooted fires
   went `computedLots=0` over the full session, and what the SENSEX/NIFTY funded split was. Do **not**
   propose the raise off one session.
5. Confirm the two paper closes (`TIME_STOP`, `STRUCTURAL_STOP`, −₹1,897.38) reconcile against the
   shadow book's champion rows for the same legs — the shadow book does not replicate signal-exits, and
   four SELL signal-exit rows fired today, so the two books should legitimately disagree.
6. Re-run the §3.15 phantom-candle probe at EOD — zero at 12:43 only proves no outage *yet*.
7. Re-check the host-clock guard at EOD — ~1 s this run. B8 stays a free-running-CMOS watch item.
