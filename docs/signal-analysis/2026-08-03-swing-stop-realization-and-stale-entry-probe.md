# Swing stop realization + the "stale entry price" probe — two findings from PR #1245

**Date:** 2026-08-03 · **Scope:** READ-ONLY investigation. No code, no config, no arming, no DB writes.
**Origin:** the two mechanical findings carried in [`2026-08-03-e1-forward-paper-reliability.md`](2026-08-03-e1-forward-paper-reliability.md) (PR #1245) §4.3 and §5.
**Data:** live DB `artha` (`ay-timescaledb`). Every bound is a `+05:30` literal; every rendering is `AT TIME ZONE 'Asia/Kolkata'`.
**Code HEAD read:** `bac035e4`.

---

## 0. Verdicts

### Finding A — "the 8% swing stop realizes at ~11.7%" — **CONFIRMED, and the mechanism is exactly as stated.**

The claim reproduces on 100% of the population where it *can* appear (5 of 5 `STOP_LOSS` closes), and the
cause is overwhelmingly the close-basis exit: **91.4% of the excess over the configured stop is
close-basis overshoot**, 7.2% is costs, 1.4% is exit slippage, 0% is corporate actions. Two corrections to
the framing, both material:

- **"The 8% stop" is Minervini-only.** Manas Arora is `min(2 × ATR(20), 10% × entry)`, measured live at
  **−7.07% to −10.05%**. Pooling the two books into one "8% stop" number is wrong, and it is what makes
  the configured-stop mean read −8.34% instead of −8.00%.
- **The realized figure is −11.99%, not −11.7%.** −11.73% is the *gross* price move; it omits costs.

**On stability and the tail — and this is the part that must not be skipped before anyone re-tunes:** on
**222,720** simulated stop-fires over real NSE bars (§4), the close-basis rule's mean is **−9.86%** and
its median **−9.28%** — meaningfully *tighter* than the live book's −11.73% gross. **The live n=5 mean
falls between the simulated 10th (−12.00%) and 25th (−10.45%) percentile of a single trade. It is a bad
draw, not the central tendency.**
The distribution is nonetheless fat-tailed and unbounded: p05 **−13.25%**, p01 **−16.74%**.

**On the remedies (§4.2, §4.5), all computed on the same 222,720 stop-fires:**

| remedy | mean realized | worst (excl. CA artifacts) | what it costs |
|---|---|---|---|
| **do nothing** (live) | −9.82% | −28.98% | — |
| **tighter configured %** (8→5) | −6.81% | — | **does not narrow the overshoot at all** (invariant at 1.72–1.76 pts); raises the stop-hit rate 52.3% → 65.6% |
| **intrabar touch / equity ticks** | **−8.22%** | −30.39% *(worse)* | **17.3% more stop-outs** — 38,582 trades that recovered by the close; their forgone P&L is **unmeasured** |

> **Therefore: do not re-tune the stop percentage, and do not build the intrabar exit on this evidence.**
> The n=5 sample overstates the problem by ~2 points; a tighter percentage does not touch the mechanism;
> and the intrabar remedy's benefit is measured only on the loss side while its cost sits entirely on the
> unmeasured winner side. **The defensible action is to update the position-sizing expectation to the
> measured −9.8% mean / −28.98% worst — not to change the exit rule.**

### Finding B — "two live entries priced off a stale close" — **FALSIFIED. Both entries priced off the correct session's close; the rupee error is ₹0.**

`KAPSTON` filled at **475.14 = 474.90 × 1.0005**, and 474.90 is **2026-07-31's** close, not 07-30's
(07-30 closed at **464.00**, which would have filled at 464.23). `SCPL` filled at **615.31 = 615.00 ×
1.0005**, and 615.00 is 07-31's close (07-30 closed at 551.15 → 551.43). The 07-31 daily bars were
present, and a sweep of **all 32** batch-priced swing entries in the book's history finds **32 same-day,
0 stale**.

**Root cause of the false positive: `bucket::date` is UTC.** A `1d` bar bucketed `2026-07-31T00:00+05:30`
is `2026-07-30T18:30Z`, so `bucket::date` renders it **2026-07-30** — exactly the trap CLAUDE.md warns
about. Every date label in #1245 §5 is shifted one session earlier; its "last full day 2026-07-30 with
2,701 symbols" **is** 07-31 with 2,701, and its "stray row on 08-02, a Sunday" is 08-03, a Monday.

**But the investigation surfaced two real defects that the false finding was standing in front of** — a
5-session candle-projection hole in June (§5.2) and a freshness guard that exists, works, and is not
armed on the path that needs it (§5.5). Those are the items worth carrying forward.

---

## 1. Method, and the one caveat that constrains everything

**Both source tables are retro-mutable, and this was verified rather than assumed** *(computed)*:

```sql
SELECT date_trunc('month', trade_date)::date mon, count(*) rows_n,
       to_char(max(fetched_at) AT TIME ZONE 'Asia/Kolkata','MM-DD HH24:MI') newest_write
FROM marketdata.nse_eod_bhavcopy GROUP BY 1 ORDER BY 1;
```
```
    mon     | rows_n | newest_write
------------+--------+--------------
 2025-10-01 |  63704 | 08-03 08:19     <- rows for OCTOBER 2025 written TODAY
 2026-04-01 |  63963 | 08-03 08:20
 2026-06-01 |  68457 | 08-03 08:20
 2026-07-01 |  75139 | 08-02 18:31
```

So a decision row from 07-31 cannot be reproduced from today's data by re-running the strategy. **Every
claim below is therefore anchored on something immutable** — the persisted `paper_positions` /
`paper_orders` rows written at decision time — and where a candle value is load-bearing, `fetched_at` is
used to prove the row has **not** been rewritten since. Two further notes on `fetched_at`:

- For `source='BHAVCOPY'` rows it **is** first-seen: the projection is `insertIgnoreAll` (DO-NOTHING).
- For `source='KITE'` rows it is **last-write-wins**, so it dates the most recent upsert, not arrival.
  This is directly observable: `KAPSTON`'s 07-31 bar carries `fetched_at = 07-31 20:05:02`, which is
  *after* the 20:00:55 Minervini batch that priced off it — the 20:05 Manas batch re-upserted the same
  bar and moved the timestamp. **`fetched_at` cannot be used to prove a bar was absent at a past instant.**

---

## 2. Finding A — does the gap reproduce on the full closed population? *(Q1)*

**Yes, on 100% of the population where the initial stop can bind — but that population is 5 trades, and
it cannot be widened from the live book.** *(computed)*

```sql
SELECT close_reason, count(*) n,
       count(*) FILTER (WHERE realized_pnl/(qty*avg_entry_price) < (stop_loss/avg_entry_price-1))
         AS exceeded_configured_stop,
       round(avg(realized_pnl/(qty*avg_entry_price)*100),3) mean_realized_pct,
       round(min(realized_pnl/(qty*avg_entry_price)*100),3) worst_pct,
       round(avg(stop_loss/avg_entry_price-1)*100,3) mean_configured_stop_pct
FROM strategy.paper_positions
WHERE book IN ('minervini','manas-arora') AND status='CLOSED' AND stop_loss IS NOT NULL
GROUP BY 1 ORDER BY 1;
```
```
 close_reason  | n  | exceeded_configured_stop | mean_realized_pct | worst_pct | mean_configured_stop_pct
---------------+----+--------------------------+-------------------+-----------+--------------------------
 MANUAL        |  1 |                        1 |           -10.568 |   -10.568 |                   -9.641
 STOP_LOSS     |  5 |                        5 |           -11.992 |   -14.534 |                   -8.336
 TRAILING_STOP | 11 |                        0 |            -4.194 |    -7.714 |                   -8.569
```

Reading this honestly:

- **5 of 5 `STOP_LOSS` closes exceeded their configured stop.** No counter-example exists.
- **0 of 11 `TRAILING_STOP` closes did** — not because the trail is immune, but because by the time the
  50-day-MA / Chandelier trail binds it sits *above* the initial stop, so the initial stop is never the
  binding rule. These 11 are not evidence against the finding; they are outside its scope.
- The single `MANUAL` close also exceeded, but it is an owner intervention, not a rule.
- **Independent names: 4, not 5.** `SATIN` appears in both books at the identical entry price (269.00),
  identical exit fill (239.69) and identical exit date — one market event, sampled twice.

**So the live book cannot answer "is 11.7% stable?" at all.** n=4 independent events, one calendar month,
one regime. §4 answers it on real bars instead.

---

## 3. Finding A — decomposition: is it close-basis, or slippage / cost / CA? *(Q2)*

**Close-basis overshoot is 91.4% of the excess. Costs are 7.2%, exit slippage 1.4%, corporate actions 0%.** *(computed)*

The exit chain, confirmed in code, fills at the **daily bar close minus 5 bps** — never at the stop level:

| step | file:line | what it does |
|---|---|---|
| trigger | `ExitEvaluator.java:322-323` | reads `series.candle(primaryIndex).close()` — the close is the *only* price fed to any rule |
| compare | `ExitEvaluator.java:450-455` | `close <= entryPrice − distance` for a LONG |
| distance | `ExitEvaluator.java:469-500` | `percent` → `entry × pct/100`; `atr_multiple` → `min(2×ATR(20), cap_pct × entry)` |
| publish | `SwingBatchEngine.java:1071-1073` | `new SignalExited(lot.id(), id, reason, bar.close())` |
| settle | `PaperService.java:1126-1134` | the caller's price wins (`refSource = "CALLER"`); the live-tick path is skipped |
| fill | `LtpSlippageV1.java:56-60, 78-79` | `reference − bpsAmount(EQUITY_FALLBACK_BPS=5, reference)` |

The stop level is a **trigger, never a fill**. Per-trade arithmetic:

```sql
WITH t(sym, book, entry_px, stop_px, exit_fill, realized, qty) AS (VALUES
 ('GNA','minervini',592.30,544.6400,507.75,-1377.40,16),
 ('SBCL','manas-arora',794.45,722.7566,695.45,-2122.75,21),
 ('SATIN','manas-arora',269.00,246.0898,239.69,-1921.36,64),
 ('SATIN','minervini',269.00,247.3604,239.69,-1050.75,35),
 ('KRN','minervini',1286.14,1182.6600,1156.02,-934.75,7))
SELECT sym, book,
  round((stop_px/entry_px-1)*100,3)                              AS a_configured_stop_pct,
  round((stop_px*0.9995/stop_px-1)*100,3)                        AS b_exit_slip_on_stop_pct,
  round((exit_fill-stop_px*0.9995)/entry_px*100,3)               AS c_close_basis_overshoot_pct,
  round((exit_fill/entry_px-1)*100,3)                            AS d_gross_price_move_pct,
  round((realized/(qty*entry_px)-(exit_fill/entry_px-1))*100,3)  AS e_costs_pct,
  round(realized/(qty*entry_px)*100,3)                           AS f_realized_total_pct
FROM t ORDER BY f_realized_total_pct;
```
```
  sym  |    book     | a_config | b_slip | c_close_basis | d_gross | e_costs | f_realized
-------+-------------+----------+--------+---------------+---------+---------+-----------
 GNA   | minervini   |   -8.047 | -0.050 |        -6.182 | -14.275 |  -0.260 |    -14.534
 SBCL  | manas-arora |   -9.024 | -0.050 |        -3.392 | -12.461 |  -0.262 |    -12.724
 SATIN | manas-arora |   -8.517 | -0.050 |        -2.333 | -10.896 |  -0.264 |    -11.160
 SATIN | minervini   |   -8.044 | -0.050 |        -2.805 | -10.896 |  -0.264 |    -11.160
 KRN   | minervini   |   -8.046 | -0.050 |        -2.025 | -10.117 |  -0.266 |    -10.383
```

| component | mean | share of the 3.656 pt excess |
|---|---|---|
| **a.** configured stop | **−8.336%** | *(the baseline, not part of the excess)* |
| **c.** close-basis overshoot | **−3.347%** | **91.4%** |
| **e.** costs (round trip, ~26 bps) | −0.263% | 7.2% |
| **b.** exit slippage (5 bps) | −0.050% | 1.4% |
| **f.** realized total | **−11.992%** | |

**Corporate actions contribute nothing here** *(computed)*: `SELECT * FROM marketdata.corporate_action_events
WHERE tradingsymbol IN ('GNA','SBCL','SATIN','KRN')` returns **0 rows**.

**Why the configured mean is −8.34% and not −8.00%** — two separate reasons, both worth naming:

1. **Minervini is genuinely 8%**, but the stop is computed off the *unslipped* close while
   `avg_entry_price` carries the 5 bps buy slippage, so it measures as `0.92/1.0005 − 1 = −8.046%`.
   All four Minervini stops land in −8.044…−8.048%. *(sourced:* `minervini-vcp.yaml:41`,
   `{basis: percent, value: 8}`, identical in all four Minervini YAMLs*)*
2. **Manas Arora is not a percentage stop at all.** `{basis: atr_multiple, value: 2, atr_period: 20,
   cap_pct: 10}` *(sourced:* `manas-arora-breakout.yaml:47`, `manas-arora-vcp.yaml:45`*)*. Measured
   across its 9 live positions the stop ranges **−7.07% (GRWRHITECH) to −10.05% (SCPL)**.

> ⚠️ **Consequence for how this finding should be quoted:** "the 8% stop realizes at 11.7%" conflates two
> different stop rules and one cost basis. The defensible sentence is: **"a close-basis stop realizes
> ~3.3 percentage points wider than its trigger level, plus ~0.3 pts of cost and slippage — and the
> overshoot has no upper bound."**

---

## 4. Finding A — is the gap stable, and what would each remedy actually do? *(Q3, Q4)*

### 4.0 Method

n=4 independent live events cannot answer a distribution question, so the exit rule was replayed on real
NSE daily bars. **Population:** the 2,272 symbols the live Minervini/Manas screens have ever ranked,
2018-01-01 → 2026-07-31, filtered to a Minervini-shaped trend template (close > SMA50 > SMA150 > SMA200,
within 25% of the 52-week high, ≥30% above the 52-week low, ≥₹1 cr 50-day turnover, price > ₹10) →
**425,795 entries**. **Rule:** buy at that day's close × 1.0005; stop at close × 0.92; walk forward 60
sessions; exit at the first close below the stop, filling at that close × 0.9995 — i.e. the exact chain
traced in §3. **222,720 entries (52.3%) hit the stop.** Every figure below is `fill / entry − 1`, so it
is directly comparable to §3's `d_gross_price_move_pct` (both exclude fees).

Reference point: a trade filling *exactly at* the 8% stop realizes `0.92 × 0.9995 / 1.0005 − 1 =`
**−8.092%**. Everything worse than that is overshoot.

### 4.1 The distribution — the mechanism is stable, the tail is not *(Q3)*

```
          rule           |   n    | mean_pct |  p50   | p25_worse | p10_worse | p05_worse | p01_worse |  worst
-------------------------+--------+----------+--------+-----------+-----------+-----------+-----------+---------
 close-basis (ACTUAL)    | 222720 |   -9.855 | -9.276 |   -10.447 |   -11.999 |   -13.254 |   -16.744 | -94.957
 intrabar-touch (remedy) | 261302 |   -8.244 | -8.092 |    -8.092 |    -8.092 |    -8.680 |   -11.401 | -95.197
```

**Three readings, in order of how much they should change behaviour:**

1. ⚠️ **The live n=5 is an unlucky draw, not the central tendency.** The live gross mean is **−11.73%**;
   the simulated distribution puts that between **p10 (−11.999%)** and **p25 (−10.447%)** — of a *single
   trade*. The population mean is **−9.86%** and the median **−9.28%**. **Quoting "11.7%" as the expected
   realized risk overstates it by roughly 2 percentage points.**
2. **The overshoot itself is small and stable; the tail is where the risk lives.** Median overshoot is
   `9.276 − 8.092 =` **1.18 points**. But p05 is −13.25% and p01 −16.74%, i.e. **1 stopped trade in 100
   loses more than double the configured 8%.**
3. **The intrabar rule fills exactly at the stop in the ordinary case** — its p50, p25 *and* p10 are all
   **−8.092%**, the at-the-stop reference to three decimals. More than 90% of intrabar exits are exact.

**Per-year, the central tendency is remarkably stable** — this is the direct answer to "is the gap stable
or does it widen?":

```
  yr  |   n   | mean_realized_pct |  p50   |  worst
------+-------+-------------------+--------+---------
 2019 |  6383 |            -9.599 | -9.032 | -24.490
 2020 | 12427 |           -10.553 | -9.571 | -94.957
 2021 | 51331 |            -9.587 | -9.166 | -26.428
 2022 | 31695 |           -10.096 | -9.562 | -26.271
 2023 | 27830 |            -9.562 | -9.091 | -76.159
 2024 | 63969 |            -9.915 | -9.319 | -37.763
 2025 | 17132 |            -9.994 | -9.286 | -53.849
 2026 | 11953 |            -9.951 | -9.233 | -75.192
```

Mean spans **−9.56% to −10.55%** across eight years including the COVID crash (2020, the widest). Median
spans −9.03% to −9.57%. **The mechanism does not drift.** Only the tail moves — and the extreme `worst`
values are mostly a data artifact (§4.4).

### 4.2 Remedy 1 — a tighter configured percentage. **It does not fix the mechanism.** *(Q4)*

```
         cfg          | fired  | hit_rate_pct | mean_realized_pct | p05_worse
----------------------+--------+--------------+-------------------+-----------
 5% configured        | 279267 |        65.55 |            -6.811 |   -10.156
 6% configured        | 259598 |        60.93 |            -7.832 |   -11.213
 7% configured        | 240745 |        56.51 |            -8.845 |   -12.229
 8% configured (LIVE) | 222720 |        52.28 |            -9.855 |   -13.254
```

*(This grid retains the 0.087% suspected-CA rows of §4.4 — they move the 8% mean by 0.04 pts, from
−9.855% to −9.815%, and affect all four rows alike, so they cannot change the comparison.)*

Subtracting each rule's own at-the-stop reference gives the **overshoot**, which is the quantity the
finding is actually about:

| configured | at-the-stop reference | mean realized | **overshoot** | stop-hit rate |
|---|---|---|---|---|
| 5% | −5.094% | −6.811% | **1.717 pts** | 65.55% |
| 6% | −6.094% | −7.832% | **1.738 pts** | 60.93% |
| 7% | −7.093% | −8.845% | **1.752 pts** | 56.51% |
| **8% (live)** | −8.092% | −9.855% | **1.763 pts** | 52.28% |

> **The overshoot is invariant to the configured percentage — 1.72 to 1.76 points across the whole grid.**
> Tightening the stop does not narrow the gap; it slides the whole distribution down while **raising the
> stop-hit rate from 52.3% to 65.6%** (a 25% relative increase in how often the book is stopped out).
>
> **This is why the "just tune it tighter" reflex is wrong here**, independently of the n=5 objection.
> Whether that trade — 3.0 points less loss per stop, 13.3 more stop-outs per 100 entries — is net
> positive depends entirely on what the extra stopped-out trades would have gone on to do, which **this
> analysis did not measure**. Per the standing prior (every measured loosening/tightening of a live gate
> has had to clear legs → P&L → sign robustness → costs), **that is a reason to leave it alone, not a
> reason to act.**

### 4.3 What actually drives the tail: gap-downs *(Q3)*

```
      gap_bucket      |   n    | mean_overshoot_pts | mean_realized_pct |  worst
----------------------+--------+--------------------+-------------------+---------
 a. gap-open <= -10%  |   1487 |             11.757 |           -19.837 | -94.957
 b. gap-open -5..-10% |   5018 |              3.727 |           -11.815 | -26.730
 c. gap-open -2..-5%  |  22445 |              2.303 |           -10.393 | -26.327
 d. gap-open 0..-2%   |  75611 |              1.701 |            -9.792 | -26.557
 e. gap-open >= 0     | 118159 |              1.495 |            -9.585 | -26.428
```

(`gap_bucket` = the trigger day's open vs the prior close.)

**Strictly monotonic, and violently so at the end.** On the 53% of stop-fires where the trigger day did
*not* gap down, the overshoot is **1.50 points** — the rule behaves almost as configured. On the 0.67%
that gapped ≥10% down, the overshoot is **11.76 points** and the mean realized loss **−19.84%**.

> ⚠️ **The load-bearing consequence: the worst cases are gap-downs, and no exit-rule change can fix a
> gap-down.** When the market opens below the stop, an intrabar-touch rule and a live tick subscription
> both fill at the open — the same bad price the close-basis rule is being blamed for. **The tail is a
> property of the tape, not of the exit basis.** Any remedy should be judged on the other 99.3%.

### 4.4 The tail is partly a data artifact — and how much *(Q3)*

The `worst` column reaching −94.957% is not a market event. Partitioning on a one-day move worse than
−30% (a proxy for an unadjusted split/bonus in stored history):

```
                  cls                   |   n    | mean_pct |  worst
----------------------------------------+--------+----------+---------
 suspected unadjusted CA (1-day < -30%) |    194 |  -56.673 | -94.957
 genuine                                | 222526 |   -9.815 | -28.978
```

**194 trades — 0.087% — carry the entire catastrophic tail.** Excluding them:

```
       scope        |   n    | mean_pct |   p05   |   p01   |  worst
--------------------+--------+----------+---------+---------+---------
 excl. suspected CA | 222526 |   -9.815 | -13.222 | -16.541 | -28.978
```

**So the honest worst observed is −28.98%, not −94.96%** — still 3.6× the configured stop, and the number
that should be used for position-sizing. The mean barely moves (−9.855% → −9.815%), so §4.1–§4.3 are
unaffected. *(That 194 is consistent with `marketdata.corporate_action_events` holding 162 `FAILED` and
248 `DETECTED` rows — see open doubt 3; the proxy also removes genuine crashes, so this is a slightly
optimistic tail.)*

### 4.5 Remedies 2 and 3 — intrabar touch, and an equity tick subscription *(Q4)*

**At daily-bar resolution these are the same model** and are reported once: exit on the first day whose
**low** touches the stop, filling at the stop unless the day opened below it (gap-through → fill at the
open). A 15-second tick poller fills at the first tick at or below the stop, which is the stop level; an
overnight gap still fills at the open, because ticks only flow 09:15–15:30. The difference between them
is intraday slippage and latency, which daily bars cannot resolve.

```
         scope          |   n    | mean_pct |  p05   |   p01   |  worst  | pct_gapped_through
------------------------+--------+----------+--------+---------+---------+--------------------
 intrabar-touch excl CA | 261108 |   -8.216 | -8.658 | -11.303 | -30.389 |               7.42
```

Head to head, both excluding suspected CAs:

| | close-basis (LIVE) | intrabar-touch / ticks | delta |
|---|---|---|---|
| mean realized | −9.815% | **−8.216%** | **1.60 pts tighter** |
| p05 | −13.222% | **−8.658%** | 4.56 pts tighter |
| p01 | −16.541% | **−11.303%** | 5.24 pts tighter |
| **worst** | −28.978% | **−30.389%** | **1.41 pts WORSE** |
| trades stopped out | 222,526 | **261,108** | **+17.3%** |

**Three findings, and the third is the one that decides it:**

1. **It works where it is supposed to.** Median/p25/p10 all land exactly on the stop; the p01 improves by
   5.2 points. This is a real and large reduction in tail risk.
2. **It is slightly worse in the very worst case** (−30.39% vs −28.98%). On a catastrophic gap-down the
   intrabar rule fills at the open; the close-basis rule sometimes catches a close that recovered off the
   low. Small, but it means "strictly better" is false.
3. ⚠️ **It converts 38,582 surviving trades into stop-outs — 17.3% more exits.** Those are trades whose
   intraday low pierced the stop and whose close recovered above it. **What they went on to earn is not
   measured here**, and it is the entire question: the remedy's cost is paid in forgone winners, and its
   benefit is measured in narrower losses. Comparing only the loss distributions — as the table above
   does — is exactly the one-sided comparison that would make a bad change look good.

> **Recommendation: measure the forgone-winner side before anyone builds this.** Two independent reasons
> not to act today:
>
> - The 17.3% additional stop-outs are unpriced (above).
> - **The build cost is already documented and was already declined.**
>   [`2026-08-02-manas-exit-stop-doctrine.md`](2026-08-02-manas-exit-stop-doctrine.md) §1 priced "go
>   intraday" as *a feature request — subscribe ~14+ small-cap equities to the live feed, add a ratchet
>   writer, add a second column, and decide what three UI surfaces display* — and concluded **"Stay
>   EOD-managed"** on a P&L measurement that could not discharge the burden (n=9 legs, t=+0.88).
>   **This analysis does not overturn that.** It measures a different axis (realized risk width, not P&L)
>   and finds a genuine improvement there — but the missing half is the same missing half.
>
> The owner has already accepted EOD-only exits (T10 option (b), #992). **The defensible action from this
> document is to update the position-sizing expectation to the measured −9.8% mean / −28.98% worst, not
> to change the exit rule.**

---

## 5. Finding B — the stale-entry probe

### 5.1 The premise, falsified three independent ways *(Q3)*

**Way 1 — the entry prices match the 07-31 close, not 07-30's.** *(computed)*

```sql
SELECT tradingsymbol, to_char(bucket AT TIME ZONE 'Asia/Kolkata','MM-DD') bar, close,
       to_char(fetched_at AT TIME ZONE 'Asia/Kolkata','MM-DD HH24:MI:SS') last_write_ist,
       round(close*1.0005,2) would_have_priced
FROM marketdata.candles WHERE interval='1d' AND exchange='NSE'
  AND tradingsymbol IN ('KAPSTON','SCPL')
  AND bucket IN (timestamptz '2026-07-30T00:00:00+05:30', timestamptz '2026-07-31T00:00:00+05:30');
```
```
 tradingsymbol |  bar  |  close   | last_write_ist | would_have_priced
---------------+-------+----------+----------------+-------------------
 KAPSTON       | 07-30 | 464.0000 | 07-30 20:04:58 |            464.23
 KAPSTON       | 07-31 | 474.9000 | 07-31 20:05:02 |            475.14   <- recorded avg_entry_price
 SCPL          | 07-30 | 551.1500 | 07-30 20:05:06 |            551.43
 SCPL          | 07-31 | 615.0000 | 07-31 20:05:25 |            615.31   <- recorded avg_entry_price
```

`paper_positions.avg_entry_price` is **475.1400** (`id=52`) and **615.3100** (`id=53`). Inverting the 5 bps
buy slippage pins the close the batch read to `[474.8975, 474.9075]` — i.e. **474.90 to the paisa**.

**This survives the retro-mutability trap.** The 07-30 bars carry `fetched_at = 07-30 20:04:58 / 20:05:06`
— last written on 07-30 and never since — so they already held 464.00 / 551.15 when the 07-31 batch ran.
The batch could not have read 474.90 from a 07-30 bar.

**Way 2 — the persisted stop level is derived from the same close and lands exactly.** *(computed)*
`KAPSTON` `stop_loss = 436.9080`; `474.90 × 0.92 = 436.9080` **exactly**. `SCPL` `stop_loss = 553.5000`;
`615.00 × 0.90 = 553.5000` **exactly** (Manas's 10% ATR cap bound). Off the 07-30 closes these would be
426.88 and 496.04.

**Way 3 — the sweep. All 32 batch-priced entries are same-day; none is stale.** *(computed)*

```sql
WITH pos AS (
  SELECT id, book, tradingsymbol, avg_entry_price,
         (opened_at AT TIME ZONE 'Asia/Kolkata')::date AS entry_d,
         round(avg_entry_price/1.0005, 2) AS implied_close
  FROM strategy.paper_positions WHERE book IN ('minervini','manas-arora') AND side='BUY'),
bars AS (
  SELECT tradingsymbol, (bucket AT TIME ZONE 'Asia/Kolkata')::date d, close,
         row_number() OVER (PARTITION BY tradingsymbol ORDER BY bucket) rn
  FROM marketdata.candles WHERE interval='1d' AND exchange='NSE'
    AND bucket >= timestamptz '2026-06-01T00:00:00+05:30')
SELECT CASE WHEN abs(p.implied_close - b0.close) <= 0.02 THEN 'SAME-DAY (correct)'
            WHEN abs(p.implied_close - b1.close) <= 0.02 THEN 'STALE (prev session)'
            ELSE 'unmatched' END AS verdict, count(*)
FROM pos p
LEFT JOIN bars b0 ON b0.tradingsymbol=p.tradingsymbol AND b0.d=p.entry_d
LEFT JOIN bars b1 ON b1.tradingsymbol=p.tradingsymbol AND b1.rn=b0.rn-1
GROUP BY 1;
```
```
      verdict       | count
--------------------+-------
 SAME-DAY (correct) |    32
 unmatched          |     2
```

The 2 unmatched are `SENORES` and `SBCL`, both opened **2026-07-05 16:48 IST — a Sunday**, outside the
20:00/20:05 cron. Their implied closes (1381.90, 794.05) are Friday **07-03**'s closes — i.e. the last
trading session, which is **correct**, not stale. They are seed positions, not batch entries.

**Rupee error from the alleged mispricing: ₹0.00 on both positions.** *(computed)* For scale, had the
premise held, the error would have been `(475.14−464.23) × 19 = ₹207.29` on `KAPSTON` and
`(615.31−551.43) × 23 = ₹1,469.24` on `SCPL`.

### 5.2 Why the finding looked true, and the real hole it was standing in front of *(Q1, Q2)*

**The false positive is the UTC `bucket::date` trap, reproduced exactly** *(computed)*:

```sql
SELECT bucket::date AS pr_utc_date, (bucket AT TIME ZONE 'Asia/Kolkata')::date AS correct_ist_date, count(*)
FROM marketdata.candles WHERE interval='1d' AND exchange='NSE'
  AND bucket >= timestamptz '2026-07-29T00:00:00+05:30' GROUP BY 1,2 ORDER BY 1;
```
```
 pr_utc_date | correct_ist_date | count
-------------+------------------+-------
 2026-07-28  | 2026-07-29       |  2694
 2026-07-29  | 2026-07-30       |  2698
 2026-07-30  | 2026-07-31       |  2701     <- #1245's "last full day 07-30, 2,701 symbols"
 2026-08-02  | 2026-08-03       |     1     <- #1245's "stray row on 08-02, a Sunday"
```

Both of #1245's cited numbers (2,701 and 1) match to the row. There was never a hole on 07-31.

**However — there IS a real, recurring-shaped hole, and it is a different failure mode.** A full-year
sweep comparing raw bhavcopy rows against their candle projection *(computed)*:

```sql
WITH raw AS (SELECT trade_date d, count(*) raw_n FROM marketdata.nse_eod_bhavcopy
             WHERE trade_date >= date '2026-01-01' GROUP BY 1),
cand AS (SELECT (bucket AT TIME ZONE 'Asia/Kolkata')::date d, count(*) cand_n,
                count(*) FILTER (WHERE source='BHAVCOPY') bhav_n
         FROM marketdata.candles WHERE interval='1d' AND exchange='NSE'
           AND bucket >= timestamptz '2026-01-01T00:00:00+05:30' GROUP BY 1)
SELECT r.d, to_char(r.d,'Dy') dow, r.raw_n, coalesce(c.cand_n,0) cand_n, coalesce(c.bhav_n,0) bhav_n
FROM raw r LEFT JOIN cand c ON c.d=r.d WHERE coalesce(c.cand_n,0) < 1000 ORDER BY r.d;
```
```
     d      | dow | raw_n | cand_n | bhav_n
------------+-----+-------+--------+--------
 2026-06-12 | Fri |  3246 |    441 |      0
 2026-06-15 | Mon |  3287 |    440 |      0
 2026-06-16 | Tue |  3257 |    440 |      0
 2026-06-18 | Thu |  3245 |    440 |      0
 2026-06-19 | Fri |  3247 |    440 |      0
```

**Five sessions where the raw bhavcopy ingested fine (3,245–3,287 rows each) but the candle projection
wrote zero.** Only ~440 KITE-sourced bars exist on those days — the subscribed instruments — against
~2,690 on a healthy session. A sixth, **2026-06-17, is partial** (2,188 BHAVCOPY rows vs ~2,470 typical).

This is a **projection** failure, not an ingest failure — the two writes are adjacent statements in
`BhavcopyBackfillService.java:345-346` (`nseRepo.upsertAll(rows)` then
`candles.insertIgnoreAll(projectNse(rows))`), so the raw table succeeding while `candles` stays empty
means the second statement did not run or wrote nothing. The `fetched_at` pattern is consistent with
that: the five bad dates' raw rows were written by scattered catch-up runs (06-15 15:13, 06-16 02:30,
06-16 18:59, 06-19 03:13, 06-20 02:52), while the dates that *did* project were all written by one bulk
re-fetch at **06-20 12:39**.

**Materiality: none to the live book.** All six sessions precede the first swing batch run
(`min(run_date) = 2026-07-06`), and there has been **zero recurrence in the 30 sessions since 2026-06-22**.
It is a closed historical hole, not an active defect — but it is the concrete instance of the failure
mode #1245 hypothesised, and it is invisible to the existing watcher (§6.1).

### 5.3 Was the 07-31 bhavcopy actually late? *(the near-miss worth recording)*

**No — it landed 2h08m before the batch.** *(computed)*

```
BHAVCOPY ingest run 70329: started 07-31 17:51:34, finished 17:52:44, SUCCESS, 49,024 rows
07-31 BHAVCOPY-sourced candles: 2,555 rows, fetched_at 07-31 17:52:18
minervini batch ran_at: 07-31 20:00:55 IST   |   manas-arora: 20:05:25 IST
```

The **19:30 IST** scheduled run (`artha.bhavcopy.eod-cron:0 30 19 * * MON-FRI`) is only 30 minutes ahead
of the 20:00 Minervini batch, which is a thin margin — but on 07-31 an earlier 17:51 run had already
covered it. **No timing defect is demonstrated**; the margin is noted as a latent risk, not a finding.

### 5.4 Does the staleness path guard the EXIT side, or only entry? *(Q4)*

**Both, identically — and the reason is that there is only one fetch.** *(computed)*

Entry (`SwingBatchEngine.java:462`) and exit (`:801`) both call the same private `series(...)` helper
(`:1116-1127`), which is a `cache.computeIfAbsent` over a `seriesCache` map created once per run at
`:291` and threaded into both passes at `:306` and `:311`. For a symbol that is both a candidate and a
held anchor, the exit pass reuses the *identical* `List<EngineCandle>` object — one HTTP call, one M14
log line, one counter increment covering both. The single exit-only path (`retryFetch`, `:957-969`) calls
the same `candles.fetch` and gets the same treatment.

**So the asymmetry the question probes for does not exist. Neither side is guarded.**

### 5.5 The exact guard that should have refused, and why it did not *(Q5)*

Three distinct things are often conflated here. Precisely:

**(a) M14 is not the guard, and on this scenario it would not even have logged.** The `stale` flag is
**not an age check** — it originates at `CandleQueryService.java:87-100` as an *exception handler*:

```java
      try {
        ensureCoverage(exchange, tradingsymbol, baseInterval, from, to);
      } catch (Exception fetchFailure) {
        log.warn("gap fetch failed for {}:{} {} — serving cached data stale: {}", ...);
        stale = true;
      }
```

`stale:true` means "the upstream gap-fetch threw", not "the data is old". `asOf` is
`OffsetDateTime.now(clock)` — the *response* time — so it carries zero freshness information. A genuinely
missing 07-31 bar reached via a healthy fetch sets `stale:false` and produces **no log and no metric**.
The client's handling is visibility-only by explicit design *(sourced:* `MarketDataCandlesClient.java:115-125`,
and the build spec at `2026-08-02-e4-128-batch-scoping.md:383-391`: *"do not add a refusal gate in this
slice … that's a HOLD-tier behaviour change"*).

**(b) The guard that exists and would have worked is `truncateToSession`, and the scheduled path passes
it `null`.** *(computed)* `SwingBatchEngine.java:983-987`:

```java
  private static List<EngineCandle> truncateToSession(
      List<EngineCandle> series, String symbol, LocalDate requiredBarDate) {
    if (requiredBarDate == null || series.isEmpty()) {
      return series;
    }
```

With a pinned `requiredBarDate` it truncates to the session bar and drops to empty on a missing one,
producing the counted, alerted `STOP NOT EVALUATED TODAY` skip at `:809-814`. The **08:35 catch-up path
pins it; the 20:00/20:05 scheduled path passes `null`** (`runDaily(doctrine)` → `runDaily(doctrine, null,
true)`, `:201-203`), documented at `:220-221` as *"`null` — the scheduled / on-demand path — truncates
nothing"*.

> ⚠️ **Correction to a stale claim.** `2026-08-02-e4-128-batch-scoping.md:296-297` records catch-up as
> *"default-OFF pending the owner arming it"*. **It is armed live** *(computed)*:
> `docker exec ay-strategy-signal-service sh -c 'env | grep SWING'` → `ARTHA_SWING_CATCHUP_ENABLED=true`.
> So the guard is not merely written — it is running, on the 08:35 path, every weekday. It is simply not
> on the path that prices entries.

**(c) There is no freshness assertion of any kind on the scheduled entry path.** *(computed)*
`SwingBatchEngine` does not import `MarketCalendar`. The entry price is taken unconditionally from
`series.get(series.size() - 1)` (`:466` → `:672`), and the only gate is `series.size() < doctrine.minBars()`
(`:463`) — a **count**, not a date. The 2026-07-05 audit row *"M14 | No daily-bar freshness assertion"*
(`docs/audits/2026-07-05-full-codebase-audit.md:464`) remains literally true; M14 shipped only its
second clause.

**Summary answer to Q5: the guard is `truncateToSession` with a pinned `requiredBarDate`. It did not
refuse because the scheduled path deliberately passes `null`. Nothing failed — the gate was never armed
on that path.** And on 2026-07-31 there was nothing to refuse.

---

## 6. Incidental findings (not asked for; on the live data path)

### 6.1 The ingest canary scores a zero-row bhavcopy GREEN

`IngestCoverageCanary.java:584-618` treats `BHAVCOPY` as `Policy.REQUIRE_SUCCESS`, satisfied by
`success.size() >= 1` **regardless of `rows_written`** — only the `SCREENER` and `CAPTURE` policies gate
on `maxRows > 0`. Worse, the ledger counts **raw `nse_eod_bhavcopy` rows**, not candle-projection rows
(`BhavcopyBackfillService.java:230-231`). **The June hole in §5.2 is exactly the shape this cannot see:
raw ingest SUCCESS with 3,246 rows, candle projection zero, canary GREEN.** `GET /api/v1/market/health/ingest`
delegates to the same policy and inherits the blind spot. `BhavcopyCloseCanary` does not cover it either —
it joins bhavcopy against `candles` only where the 1d bar is `source='KITE'`, so an *absent* row produces
no join row at all.

### 6.2 `swing_batch_refusals` has never recorded a row, and 2 sessions have no batch run

`SELECT count(*) FROM strategy.swing_batch_refusals` → **0**, all time — the refusal ledger has never
recorded a row, consistent with §5.5(c): on the scheduled path there is nothing that can refuse.

Separately, of the 20 weekdays in `2026-07-06 .. 2026-07-31`, **18** have batch rows; **2026-07-09** and
**2026-07-17** have none. 2026-07-17 is the known V047 incident (its `swing_catchup_runs` rows are
`ABANDONED`). **2026-07-09 was a strategy-signal-service outage** *(computed)* — that day has **0**
`strategy.signals` rows against 10 on 07-08 and 14 on 07-10, and **0** swing batch rows, while
`marketdata.candles` took **7,529** writes, i.e. market-data was healthy and only the signal service was
silent. No catch-up row exists for it because catch-up did not begin claiming until ~2026-07-28 (the
oldest `swing_catchup_runs` row). Not a data defect; recorded so the 18-of-20 run count is not later
mistaken for one.

> ⚠️ `marketdata.ingest_runs` is **useless as a liveness probe before 2026-07-10** — its first row is
> 07-10, so a query for "was the stack up on 07-09" returns 0 rows for both a dead stack and a
> not-yet-existing ledger. This cost one wrong inference during this investigation before the
> cross-check above corrected it.

### 6.3 `IntrabarExitResolver` is not dead code

#1245 §4.3 cites `docs/audits/2026-07-10-research-fidelity-audit.md:132` for *"`IntrabarExitResolver` is
dead code"*. That audit line is stale by ~3 weeks: #762 (`@ b34e9b45`, merged 2026-07-12) gave it a live
production call site at `ReplayEngine.java:560-568`, gated on
`BacktestRunner.java:200` (`touchBasis == "bar_hl_worstof"`). The *conclusion* survives — it is
**backtest-only, opt-in, and no swing YAML opts in**, and the live batch runs `ExitEvaluator`, never
`ReplayEngine` — but "dead code" is the wrong reason. **The intrabar remedy in §4 is therefore not a
greenfield build; a tested implementation of the fill semantics already exists.**

---

## 7. Open doubts

1. **The §4 simulation's entry population is a proxy, not the live screen.** It applies a
   trend-template filter (close > SMA50 > SMA150 > SMA200, within 25% of the 52-week high, ≥30% above the
   52-week low, ≥₹1 cr 50-day turnover) to the 2,272 symbols the live screens have ranked. The live
   screens additionally use RS-rank, free-float m-cap, VCP/base geometry and a per-batch admission cap.
   The overshoot magnitude is a property of the tape rather than of the entry rule, so I expect it to
   transfer — but **I did not verify that**, and a genuinely tighter entry filter could select
   lower-volatility names and shrink it.
2. **The simulation omits the trailing stop.** Real trades can exit on the 50-day-MA / Chandelier trail
   before the initial stop fires, so the simulated stop-fire population is a **superset** of the real one.
   This does not bias the *overshoot* measurement (it is conditional on the stop firing either way) but it
   does mean the simulated **hit rates** in §4.2 are upper bounds, and the `n` there should not be read as
   "trades this book would take".
   - ⚠️ **The single most decision-relevant thing I did NOT measure: what the 38,582 extra stop-outs
     under the intrabar remedy (§4.5) would have earned.** Every remedy comparison in §4 is a
     *loss-distribution* comparison. A rule that exits more often will always look better on the loss
     distribution alone — that is arithmetic, not evidence. Until the forgone-winner side is measured on
     the same population, **§4.5's table must not be read as favouring the remedy**, and the
     recommendation is stated accordingly.
3. **Unadjusted corporate actions contaminate the far tail.** `marketdata.corporate_action_events` holds
   **162 `FAILED`** and **248 `DETECTED`** rows, so some stored history is certainly still unadjusted; a
   1.5× split reads as a −33% one-day crash. §4.4 partitions on a −30% one-day move as a proxy and
   reports the tail both ways. **That proxy also removes genuine crashes**, so the CA-excluded tail is
   itself slightly optimistic. I did not cross-reference each tail trade against the CA table.
4. **`fetched_at` is last-write-wins for KITE rows, so §5.1 Way 1's "not rewritten since" argument
   applies only to the 07-30 bars** (whose timestamps genuinely predate the 07-31 batch). It cannot
   establish when the 07-31 bars first appeared — that is why the falsification rests on the persisted
   `avg_entry_price` arithmetic rather than on bar timestamps.
5. **The June projection hole's mechanism is inferred, not proven.** I established *that* the raw rows
   exist and the candle rows do not, and that the two writes are adjacent statements. I did **not** find
   the log line or the code path that skipped the projection — the containers have long since rotated
   those logs, and the code at `bac035e4` projects unconditionally. It may have been fixed since;
   I did not bisect.
6. **Cost rate carried from #1245, spot-checked not re-derived.** §3's `e_costs_pct` is computed as
   `realized − gross(avg_entry_price basis)`, which **excludes entry slippage already baked into
   `avg_entry_price`**. #1245's open doubt #1 flags an unreconciled ~3× gap against a `FeeConstants`
   code-read. That gap is immaterial here (0.26% against a 3.35% close-basis term) but it is **not
   resolved by this document**.
7. **One regime.** The live n=5 all fall in 2026-07, a single month on a persistently bullish tape.
   §4.3's per-year table is what carries the stability claim; the live book carries none of it.
8. **§6.2's 2026-07-09 outage is characterised, not root-caused.** I established that the signal service
   emitted nothing while market-data stayed healthy. I did **not** determine why it was down, and
   container logs from that date have long since rotated.

---

## 8. Claim labels

`computed` — every DB measurement in this document: the closed-population table (§2), the per-trade
decomposition (§3), the whole of §4, the entry-price sweep and its three falsification lines (§5.1), the
UTC/IST date reproduction and the June projection hole (§5.2), the 07-31 ingest timing (§5.3), the
`seriesCache` single-fetch structure (§5.4), the `ARTHA_SWING_CATCHUP_ENABLED=true` live env read (§5.5),
`swing_batch_refusals` = 0 and the two missing batch dates (§6.2), and the corporate-action event counts.

`sourced` — the stop-rule YAML values (`minervini-vcp.yaml:41`, `manas-arora-breakout.yaml:47`); the
exit-chain file:line trace (`ExitEvaluator`, `SwingBatchEngine`, `PaperService`, `LtpSlippageV1`); the M14
build spec and ledger row; the T10 owner decision (`2026-07-25-weekly-bug-queue.md:35,51`); #762 /
`ReplayEngine` (§6.3); the `IngestCoverageCanary` policy code (§6.1). Each was read at `bac035e4`; the
close-basis fill semantics were additionally corroborated by row arithmetic (every exit fill in §3 equals
the trigger day's close × 0.9995 to the paisa) before being relied on.

`recalled` — none load-bearing.

`assumed` — that `ltp_slippage/v1`'s 5 bps equity rate applied uniformly across the whole simulated
window (§4); it is the current constant, and the simulation is a counterfactual, so this affects all
compared rules identically and cannot change their ranking.
