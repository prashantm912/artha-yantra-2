# Minervini live plane split — measurement and verdict (2026-08-03)

**Scope:** read-only investigation of the "live-only Minervini exposure, ≤0.93%" claim raised in
passing by the M6/M9 measurement (`docs/signal-analysis/2026-08-03-m6-m9-impact-measurement.md`
§3.4, branch `origin/docs/m6-m9-impact-measurement` @ `84d13d88` — **not yet on `main`** at the time
of writing). No production code, config, YAML, migration or `.env` touched. Nothing armed.
All DB access read-only against the LIVE `artha` database.

---

## 0. Verdict first

| clause of the reported premise | verdict |
|---|---|
| "the live Minervini YAML gate has **no `close > sma50` term at all**" | **TRUE**, and confirmed against the four LIVE PUBLISHED configs, not just the YAML files |
| "that condition exists only in the funnel screen" | **TRUE literally, MISLEADING as stated** — it is genuinely enforced in the live chain, one plane over and up to one screen-date stale |
| "the screen reads `nse_eod_bhavcopy` while the exit path reads `candles`@1d" | **TRUE** |
| "**entry and exit** are decided on different corporate-action planes" | **FALSE.** Live entry *evaluation* and live exit read the SAME plane, from the SAME per-run cache. The split is between candidate **selection** and the engine |
| the split is a **corporate-action**-plane split | **FALSE.** The two planes implement the same CA semantics by two mechanisms and agree to **2 paise / 0.013%**. The measured exposure has a different cause |
| ≤0.93% bound | **REPRODUCED** (0.93% unrestricted, 0.996% on the clean as-of-date subpopulation) — but the **attribution is wrong** |
| is this a bypass of #757's shared plane? | **NO.** #757 unified the three *bhavcopy* readers; it never claimed to unify bhavcopy with `candles`. The genuine uncovered same-class consumer is the still-open chip `task_53ce441b` |

**One-line summary:** there *is* a real plane split and the 0.93% number is real, but it is a
**moving-average window divergence caused by both source tables being retro-mutable**, not a
corporate-action divergence. In every one of the 38 measured exposures the two planes' **closes are
byte-identical** and only the 50-bar mean differs.

**Highest-value thing in this document is not the premise at all** — it is §5.3: a latent money-path
hazard where a retroactive CA rewrite of the `candles` series is compared against a *stored,
un-rewritten* entry price by the 8% percent-stop. Measured live: **0 of 63** swing signals has ever
spanned a corporate-action ex-date, so it has never fired.

---

## 1. Question 1 — the exact live read paths

### 1.1 Live entry EVALUATION and live EXIT are the SAME plane

`SwingBatchEngine` drives both passes. Both call the same private `series(...)` helper, against the
same per-run `seriesCache`:

| pass | call site | resolves to |
|---|---|---|
| entry pass | `SwingBatchEngine.java:462` | `series(doctrine, symbol, seriesCache, requiredBarDate)` |
| gate re-check | `SwingBatchEngine.java:617` | same |
| exit pass | `SwingBatchEngine.java:801` | same |
| the helper | `SwingBatchEngine.java:1116-1127` | `candles.fetch(EX="NSE", symbol, IV="1d", now−warmupDays, now)` |

`MarketDataCandlesClient.fetch` (`MarketDataCandlesClient.java:78`) issues
`GET /api/v1/market/candles` (`:88`) with **no `adjust` parameter**, so it takes the controller
default `adjust=back` (`CandlesController.java:61`), which for `interval=1d` routes through
`splitBonusAdjuster.adjust(...)` (`CandlesController.java:80`) → `EquitySplitBonusAdjuster`.

Ultimate table: **`marketdata.candles` @ `interval='1d'`**, CA-adjusted in Java **only for rows whose
`source='BHAVCOPY'`** (`EquitySplitBonusAdjuster.java:41`).

**So the premise's "entry and exit are decided on different corporate-action planes" is false.**
They share a plane *and a cache object*. **[sourced]**

### 1.2 The split that DOES exist: candidate selection vs the engine

| stage | reader | ultimate table | CA rule |
|---|---|---|---|
| candidate **selection** (SEPA funnel) | `TrendTemplateService.java:109` → `AdjustedEquityDailySql.SCREENER_BASE_CTE` | `marketdata.nse_eod_bhavcopy` (`series IN ('EQ','BE')`) | SQL lateral, applied to **every** row |
| VCP **pivot geometry** (the level the entry gate crosses) | `DailyBarReader.java:21` → `AdjustedEquityDailySql.GEOMETRY_SYMBOL_SQL` | `marketdata.nse_eod_bhavcopy` | same SQL lateral |
| entry **evaluation** + **exit** | `SwingBatchEngine.java:462/:801` → `MarketDataCandlesClient.java:78` → `CandlesController.java:80` | `marketdata.candles`@1d | Java adjuster, **`source='BHAVCOPY'` rows only** |

Both CA rules read the same ratio table, `marketdata.eod_corporate_actions`, and both multiply by the
product of ratios whose `ex_date` is strictly after the bar. **[sourced]**

### 1.3 The pivot is the one genuinely cross-plane *comparison*

`MinerviniDoctrine.toCandidate` (`MinerviniDoctrine.java:133-136`) seeds the funnel's `pivot` /
`cheatPivot` / `thrust` as flat context series; `SwingBatchEngine.java:1090-1114` turns each into a
one-bar `EngineSeries`. The live gate `crossover: { fast: px, slow: pivot }` therefore compares a
**`candles`-plane `px`** against a **bhavcopy-plane `pivot` level**. That, not the sma50 trail, is
the sharpest cross-plane surface in the chain. It is mostly harmless because the two planes' closes
agree (§3.2), but it is the surface that would break first under a corporate action landing between
the screen run and the evening batch. **[sourced]**

---

## 2. Question 4 — is `close > sma50` genuinely absent from the live gate?

**Absent from the YAML — yes. Absent from the live chain — no.**

All four live Minervini strategies are `enabled=t` with a non-null `published_version_id`. Read from
`strategy.strategy_versions` (the config the engine actually runs, not the YAML):

```
          slug          | version |  status   |                      published gate
------------------------+---------+-----------+------------------------------------------------------
 minervini-vcp          | 1.0.1   | published | crossover(px,pivot) AND vol > 1.2 AND px > sma20
 minervini-power-play   | 1.0.1   | published | crossover(px,pivot) AND vol > 1.2 AND thrust > 0
 minervini-cheat-3c     | 1.0.1   | published | crossover(px,cheat) AND vol > 1.2
 minervini-primary-base | 1.0.1   | published | crossover(px,w52h)  AND vol > 1.2
```

No `sma50` term in any of the four. All four carry
`trailing_stop {basis: indicator, alias: sma50}` in `exit_rules`. **[computed]**

**But it IS enforced upstream, in the live chain.** The universe is `mode: minervini_funnel`
(all four YAMLs), the batch scopes itself to that mode (`SwingBatchEngine.java:1157-1160`), and the
funnel serves **only** `passes_all = TRUE` rows:

- `MinerviniFunnelService.java:75` — `WHERE r.screen_date = ? AND r.passes_all = TRUE`
- `passes_all` ⇔ all 8 trend-template gates, and gate index 4 is
  `g[4] = gt(close, sma50)` — a **strict `>`** (`MinerviniGates.java:42`)

So a name cannot reach the live engine at all unless it closed strictly above its 50-day MA **on the
bhavcopy plane, as of the last persisted screen**. The correct statement of the gap is therefore
narrower and more precise than "the term is missing":

> `close > sma50` is enforced **on a different price plane and up to one screen-date stale**, never
> re-checked on the plane the engine and the exit actually use.

**[sourced]**

---

## 3. Question 2 — how many names, and when

### 3.1 The population

| quantity | value |
|---|---|
| screen dates persisted in `marketdata.minervini_screen_results` | **21** (2026-07-03 → 2026-07-31) |
| all scanned name-dates | **37,114** (1,812 distinct symbols) |
| **funnel-passing name-dates** (`passes_all`) — the operative population | **5,051** (375 distinct symbols) |
| of those, with a `candles`@1d bar on the same date | **5,037** |
| of those, with a full 50-bar candles window | **5,037** |

Live state at measurement time: 42 Minervini signals ever (20 EXPIRED / 14 TAKEN / 8 ACTIVE) plus 21
Manas Arora — **63 swing signals total**. **[computed]**

### 3.2 Same-instant plane divergence (both planes read now)

Recomputing plane A live (`nse_eod_bhavcopy` + today's `eod_corporate_actions`, exactly
`AdjustedEquityDailySql`'s rule) against plane B (`candles`@1d + the source-gated Java rule), over the
5,037 comparable name-dates:

| measure | result |
|---|---|
| closes identical (< 0.5 paise) | **5,011 / 5,037 = 99.48%** |
| closes differing ≥ 1% | 24 |
| closes differing ≥ 10% | 0 |
| sma50 mean absolute divergence | **0.3136%** |
| sma50 max absolute divergence | 8.315% |
| sma50 divergence ≥ 1% | 458 (9.1%) |
| `close > sma50` verdict flips, A-true→B-false | **3** |
| `close > sma50` verdict flips, A-false→B-true | 9 |

All 3 A-true→B-false flips are on symbols with **`ca_ever = 0`** — no corporate action has ever been
recorded for them. **[computed]**

### 3.3 As-of-screen-date exposure (the operationally correct comparison)

Both source tables are retro-mutable (§5.1), so a live recompute is not the same measurement the
engine made on the day. Restricting plane B to name-dates whose entire 50-bar window carries
`fetched_at ≤ screen_date + 2` — i.e. never rewritten since — and comparing against the persisted
screen row (which *is* plane A as of that date):

| subpopulation | size | candles-plane `close ≤ sma50` |
|---|---|---|
| **clean** (window never rewritten) | **3,814** | **38 (0.996%)** |
| rewritten after D (indeterminate as-of-D) | 1,223 | 9 |
| unrestricted total | 5,037 | 47 (0.933%) |

**This reproduces the M6/M9 doc's 0.93% exactly.** The bound stands. **[computed]**

### 3.4 …but the cause is not corporate actions

Decomposing the 47:

| CA within the 50-bar reach | window contains non-`BHAVCOPY` bars | count |
|---|---|---|
| no | yes | 43 |
| no | no | 3 |
| **yes** | yes | **1** |

**46 of 47 have no corporate action anywhere near the window.** And in every one of the 38 clean
exposures the two planes' **closes are identical to the paisa** — the entire disagreement lives in
the 50-bar mean. Sample (all 38 share this shape):

```
   symbol   | screen_date |  close_a  |  close_b  |  sma50_a  | sma50_b
------------+-------------+-----------+-----------+-----------+---------
 BHEL       | 2026-07-10  |  395.2500 |  395.2500 |  395.0954 |  396.04
 GRINDWELL  | 2026-07-23  | 2004.0000 | 2004.0000 | 1999.8280 | 2007.56
 THERMAX    | 2026-07-08  | 4602.5000 | 4602.5000 | 4593.4980 | 4611.71
 WHEELS     | 2026-07-06  | 1476.0000 | 1476.0000 | 1465.4400 | 1476.38
```

Every case is razor-thin: the close sits *between* the two planes' 50-day means, typically 0.2–0.7%
apart. **[computed]**

### 3.5 Proof that the two CA rules agree

Direct comparison over names carrying a real corporate action, comparing raw `nse_eod_bhavcopy`
closes against the `candles` rows for the same dates:

```
 CUB  (BONUS 3:4, ex 2026-06-12) — candles source = KITE
 d           source  candles_close  bhav_raw   candles/raw
 2026-05-20  KITE          187.55    250.05        0.7500
 2026-06-11  KITE          192.60    256.80        0.7500
 2026-06-12  KITE          201.70    201.70        1.0000   <- ex-date
```

The Kite-sourced rows arrive from the broker **already split-adjusted** by exactly the ratio, which is
precisely the premise `EquitySplitBonusAdjuster.java:19-22` states when it skips them. The bhavcopy
plane reaches the same number by applying the SQL factor to the raw close. Identical results by two
routes. Same pattern confirmed on BRIGADE (0.75) and ANANDRATHI (0.50).

Residual between the two mechanisms, measured over CUB's 48 pre-ex-date bars:
**max 2.5 paise = 0.0133%** — broker rounding vs our `setScale(4, HALF_UP)`, nothing more. **[computed]**

Confirmation from the other direction: **NARMADA** (SPLIT 1:2, ex 2026-07-31). Its entire July
`candles` series was rewritten by Kite at `2026-07-31 11:46:56 UTC`, halving every historical bar,
while `nse_eod_bhavcopy` kept the raw values and the *screener's* factor lateral halves them at read
time. Both planes land on the same series. **[computed]**

---

## 4. Question 3 — which direction does it err?

**At the funnel boundary the error is effectively one-way.** The screen is a hard gate: a name that
fails `passes_all` never enters `resolveMinerviniFunnel`'s output, is never a `SwingCandidate`, and
the engine never evaluates it. So the "plane A denies / plane B would admit" direction — 9 cases in
the same-instant recompute, and structurally larger over the broad universe — has **no live
consequence**.

The reachable direction is: **the screen ADMITS a name that the engine's own plane already shows at
or below its 50-day MA**. That is the 38 / 3,814 ≈ **1.0%**, and it is the direction that matters
because the only entry-bar-reachable exit rule in these four strategies is the `sma50` close-below
trail. On such a bar the trail could fire on the entry bar itself.

Two qualifications that shrink it further, both consistent with the M6/M9 doc's own reasoning:

1. It is an **upper bound**. Reaching an actual entry additionally needs the pivot/cheat/52wh
   crossover and `vol > 1.2` (and `px > sma20` for `minervini-vcp`) **on the candles plane**, which a
   bar closing below its own 50-day mean will very rarely satisfy simultaneously.
2. The live population agrees: **0 of the 42 Minervini signals** shows this shape.

**Net direction: the split is marginally permissive at the funnel boundary, by ~1% of candidate
name-dates, all of them within ~0.7% of the boundary itself.** **[computed]**

---

## 5. What the premise did not contain (the more useful findings)

### 5.1 Both source tables are retro-mutable, in opposite ways

- **`candles`@1d is retroactively REWRITTEN.** `CorporateActionJob` re-fetches through Kite and
  `upsertAuthoritativeAll` REPLACES OHLC. NARMADA's whole July series was rewritten in one shot on
  2026-07-31.
- **`nse_eod_bhavcopy` is retroactively BACKFILLED.** Measured on BHEL: rows for trade dates
  `2026-04-02 … 2026-06-25` were inserted on **2026-08-02 and 2026-08-03** — i.e. *today*, months
  after those sessions and weeks after the screens that read them.

This is the actual mechanism behind §3.4: at screen time the bhavcopy 50-bar window and the candles
50-bar window covered **different sets of sessions**, so the two means differ even though every shared
bar carries the same close. Session-coverage gap, measured 2026-05-01…07-31:

| universe | symbols | candles = bhavcopy | candles fewer | mean diff | worst |
|---|---|---|---|---|---|
| all scanned | 1,812 | 420 | 1,392 | −4.55 | −37 |
| **funnel passers** | **375** | **308** | **67** | **−0.89** | **−5** |

**Measurement corollary, load-bearing for anyone re-running this:** a persisted
`minervini_screen_results` row **cannot be reproduced** from today's tables. Attempting it on BHEL
2026-07-10 gives 396.0402 against a persisted 395.0954, and no window length between 45 and 60
reproduces the persisted value. Any historical plane-A-vs-plane-B comparison must gate on
`fetched_at`, as §3.3 does. **[computed]**

### 5.2 The broad-universe divergence is much larger than the funnel's — and it does not matter

Over all 36,081 comparable scanned name-dates the two planes disagree on `close > sma50` in
**5,479 + 8,959 = 14,438 (40%)** of cases, in both directions. This is *not* a live exposure — those
names never reach the engine — and it is dominated by the coverage gap in the table above (candles is
short a mean of 4.55 sessions per symbol across the broad universe vs 0.89 for the funnel names).
Recorded so nobody re-derives it and mistakes it for a live number. **[computed]**

### 5.3 ⚠️ Latent money-path hazard: percent-stop vs a retroactively rewritten series

All four Minervini strategies carry `stop_loss {basis: percent, value: 8}`.
`ExitEvaluator.java:480-483` computes the distance as `position.entryPrice() × value / 100` — off the
**stored** entry price (`strategy.signals.entry_price`), compared against the **currently-read**
series close.

Those two are on different planes the moment a corporate action lands mid-hold: the series is
retroactively CA-rewritten (§5.1) while the stored entry price is not. A 1:2 split on a held position
would halve the series and leave `entry_price` at its pre-split value, presenting as an instant ~−50%
move and firing the 8% stop on the next batch. Nothing in the chain re-scales `entry_price`.

**Measured live reachability:**

```
    book     | signals | spanned_a_CA_ex_date
-------------+---------+----------------------
 minervini   |      42 |                    0
 manas-arora |      21 |                    0
```

**0 of 63.** The hazard is latent, not active, and this document does not propose a fix — it is
recorded because it is a strictly larger exposure than the 1% the premise was about, and it is
invisible to every existing test (no fixture seeds a corporate action across a held swing position).
**[computed]** / **[sourced]**

### 5.4 `EquitySplitBonusAdjuster`'s source gate covers a case its javadoc does not name

`candles`@1d NSE carries **three** sources: `BACKFILL` (3,146,276 rows, Upstox equity daily,
2015-03-31 → 2026-06-28), `BHAVCOPY` (625,821), `KITE` (454,776). The adjuster skips everything that
is not `BHAVCOPY`; its javadoc justifies the skip **for Kite only** (`EquitySplitBonusAdjuster.java:19-22`)
and never mentions `BACKFILL`.

Measured twice, at two population widths: **0 `BACKFILL` rows are dated before a corporate action
they would need to carry** — once over the funnel universe from 2026-03-01, and once over *every*
symbol carrying an NSE corporate action with `ex_date` in 2025-07-01…2026-06-28 (the full window
where `BACKFILL` and `nse_eod_bhavcopy` coverage overlap). Both return zero rows. The gap is
currently empty. It is nonetheless undocumented and unguarded, and `BACKFILL` rows do sit inside the
50-bar windows the engine reads (43 of the 47 §3.4 exposures have non-`BHAVCOPY` bars in-window), so
it would become live the moment a backfilled name has an action. **[computed]**

### 5.5 Corporate-action remediation health (observed, not investigated)

`marketdata.corporate_action_events`: 448 rows — `DETECTED` 248, `FAILED` 162, `RESOLVED` 25,
`BASE_REBUILT` 13. **410 of 448 (91.5%) never reached `RESOLVED`.** POCL alone shows two consecutive
`FAILED` runs (2026-07-21, 2026-07-22) before a `BASE_REBUILT` on 2026-07-23. This was observed in
passing while tracing the CA path and is **not** analysed here — the age distribution of the
`DETECTED` rows was not examined, and some are presumably simply recent. Flagged as a candidate for
its own look. **[computed]**

---

## 6. Question 5 — same-class neighbours

### 6.1 Is this a bypass of #757 (audit H6)?

**No.** #757 (`2dcc6f16`, merged + deployed 2026-07-12, ledger row B4) routed the **three
`nse_eod_bhavcopy` readers** through one shared plane; #1094 later folded in `EquityReturnsService`.
Its four consumers today are `TrendTemplateService`, `ManasScreenService`, `DailyBarReader`,
`EquityReturnsService` — all on the bhavcopy side.

`AdjustedEquityDailySql`'s own javadoc (`:13-19`) states the goal explicitly: apply
"the exact multiplicative rule the read-time `EquitySplitBonusAdjuster` (the chart-path adjuster)
applies, expressed set-wise". H6 was that the *screener* was raw while the candles plane was
adjusted; the fix made the screener match the candles plane. **The candles read path was never meant
to be a consumer of `AdjustedEquityDailySql` — it is the reference implementation the shared plane
was built to match.**

Two implementations of one rule is a real drift risk on paper, but §3.5 measures them as agreeing to
2 paise. **This is a fresh, different gap (bar coverage), not a #757 bypass** — which is the
materially *less* serious of the two readings the brief asked me to distinguish. **[sourced]** / **[computed]**

### 6.2 `task_53ce441b` (equity breadth over CA-unadjusted closes) — **still open, confirmed**

`EquityBreadthDailyRepository.COMPUTE_SQL` reads `FROM nse_eod_bhavcopy` (line 47) with window
functions straight over raw `close_price` and **no `AdjustedEquityDailySql.factorLateral`**:

```sql
avg(close_price) OVER w50  AS sma50,  count(*) OVER w50  AS n50,
avg(close_price) OVER w200 AS sma200, count(*) OVER w200 AS n200
FROM nse_eod_bhavcopy
WHERE series = 'EQ' AND trade_date > ? AND trade_date <= ?
```

That chip **is** the genuine same-class uncovered consumer of #757 — a bhavcopy reader that the
shared plane never reached. Unchanged since it was chipped. It is a *different and better-founded*
finding than the one this investigation was sent to check. **[sourced]**

---

## 7. Method (so this is reproducible)

1. Plane A recomputed by transcribing `AdjustedEquityDailySql.SCREENER_BASE_CTE` verbatim
   (`series IN ('EQ','BE')`, `round(close × exp(Σ ln ratio), 4)` over ratios with
   `ex_date > trade_date`, `exchange='NSE'`).
2. Plane B recomputed by transcribing `EquitySplitBonusAdjuster` verbatim — same factor, but applied
   **only** where `source = 'BHAVCOPY'`.
3. Both bucketed with `avg(...) OVER (PARTITION BY symbol ORDER BY d ROWS BETWEEN 49 PRECEDING AND
   CURRENT ROW)`, matching both the screener SQL and the engine's `IndicatorBank` SMA(50).
4. IST handled per the house rule: bounds as `timestamptz '…+05:30'` literals; rendering via
   `AT TIME ZONE 'Asia/Kolkata'` (never `'+05:30'`, which inverts).
5. `exchange='NSE'` filtered on every equity candle read.
6. As-of-date honesty gate: plane B restricted to windows whose `max(fetched_at) ≤ screen_date + 2`.

Scripts kept in the session scratchpad; every number above is a direct psql output against the live
`artha` database on 2026-08-03.

---

## 8. Open doubts

1. **The 21-screen-date window is short and July-only.** 5,051 passing name-dates over one month is
   enough to bound the rate but not to characterise it across regimes or across a period with a dense
   corporate-action calendar. Only 20 NSE corporate actions fall anywhere near the measured windows.
   A 12-month version of §3.3 would be a much stronger statement — it is not possible today because
   `minervini_screen_results` only goes back to 2026-07-03.
2. **§3.3's clean subpopulation is 3,814 of 5,037 (76%).** The other 1,223 had their candles window
   rewritten after the screen date, so their as-of-D state is genuinely unknown, not merely
   unmeasured. Their unrestricted rate (9 / 1,223 = 0.74%) is *lower* than the clean rate, which is
   weak evidence they do not hide a worse population — but it is read off rewritten data and should
   not be leaned on.
3. **I did not evaluate the pivot/cheat/w52h crossover, `vol > 1.2` or `px > sma20` on the candles
   plane.** So the 1.0% in §4 is an upper bound on entry-bar exposure, exactly as the M6/M9 doc said,
   and I did not narrow it. Narrowing it needs the full entry gate replayed on plane B, which is a
   harness, not a query.
4. **§5.3's hazard is reasoned from code, not reproduced.** I traced `ExitEvaluator.java:480-483` and
   confirmed 0/63 live signals ever spanned an ex-date, but I did not build a test that seeds a split
   across a held swing position and observes the stop firing. Until someone does, "it would fire" is
   `assumed`, not `computed`. The 0/63 population count is `computed`.
5. **§5.1's BHEL backfill evidence is one symbol.** I confirmed the *pattern* (late inserts for old
   trade dates) on BHEL specifically and confirmed the *consequence* (window-span mismatch on 664 of
   5,037 name-dates) in aggregate, but I did not enumerate how many symbols are still receiving
   backfilled rows, nor whether the backfill is converging.
6. **`fetched_at` is an upsert timestamp, not a first-seen timestamp.** Attempting to reconstruct
   BHEL's plane A as of 2026-07-10 by filtering `fetched_at` gave 392.0176 against the persisted
   395.0954 — so the filter does not recover historical state on the bhavcopy side either. §3.3 uses
   it only on the *candles* side, where it gates "was this row rewritten after D", a strictly weaker
   and safe use. But nobody should read `fetched_at` as "when this row first appeared".
7. **§5.5 is an observation, not a finding.** 91.5% of CA events never reaching `RESOLVED` looks bad,
   but I did not check the age distribution of the 248 `DETECTED` rows, nor whether `DETECTED` is a
   terminal-in-practice state for names outside the Kite-tracked universe. Do not act on that number
   without that check.
8. **I did not verify the deployed jar.** Everything in §1 and §2 is read off `origin/main`
   @ `81b840aa` plus the live `strategy.strategy_versions` rows. The published *configs* are
   authoritative live state; the *Java* citations assume `ay-market-data-service` and
   `ay-strategy-signal-service` are current on `main`, which I did not probe.
