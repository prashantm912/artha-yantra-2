# `crossover(px, pivot)` — the entry-trigger plane split, measured (2026-08-03)

**Scope:** read-only investigation of the one clause `docs/signal-analysis/2026-08-03-minervini-live-plane-split.md`
(#1243) named but did not measure — §1.3, "the pivot is the one genuinely cross-plane *comparison*".
Unlike the 0.996% #1243 measured and the owner accepted, this sits inside the **entry trigger**, so it
can move an actual entry price and timing, not merely which names are eligible.

No production code, config, YAML, migration or `.env` touched. Nothing armed. All DB access read-only
against the LIVE `artha` database, 2026-08-03.

---

## 0. Verdict first

| clause | verdict |
|---|---|
| `crossover(px, pivot)` is genuinely cross-plane | **TRUE.** `px` is a `candles`@1d close; `pivot`/`cheat` are `nse_eod_bhavcopy` bar *highs*. Confirmed against the four LIVE PUBLISHED configs, not just the YAML |
| it is the sharpest cross-plane comparison in the chain | **TRUE for 3 of the 4 strategies.** `minervini-primary-base` is NOT cross-plane — its `w52h` is computed in-engine on the candles plane |
| the two planes differ at the pivot often enough to matter | **FALSE as measured.** The pivot source bar is present on the candles plane in **2,220 / 2,220** cases and its high is byte-identical in **2,220 / 2,220** as-of-honest cases |
| the split moved a live entry | **NO. 0 of 28** live Minervini entries; **0 of 2,121** as-of-honest candidate name-dates |
| #1243's coverage-gap mechanism reaches the crossover | **NO.** The crossover needs 2 bars, not 50. The candles previous-bar date equals the bhavcopy previous-session date in **2,221 / 2,221** candidate name-dates |
| worth fixing | **Not as a plane unification.** But the investigation found the *real* defect one layer down — see below |

**One-line summary:** the comparison is genuinely cross-plane and the premise is correct, but the
measured exposure at the entry trigger is **zero** — smaller than the 1% the owner already accepted
next door, and for a structural reason (the pivot is a 2-day-old level, and the crossover reads 2 bars,
so neither of #1243's mechanisms can reach it).

**The finding worth keeping is not the plane split.** It is §5: **`candles`@1d is dividend-back-adjusted
and `nse_eod_bhavcopy` is not, by two deliberate and opposite design decisions**, and the platform
already holds the dividend amounts it would need to reconcile them. **32 of 1,813** screened symbols
sit in that divergent state today (2–12%, not paise). Every apparent flip in this investigation traced
to it, and every one dissolved once the as-of gate was applied — which is precisely why it should be
recorded now rather than after it lands on a name that matters.

---

## 1. Question 1 — is the comparison genuinely cross-plane?

### 1.1 Where `px` comes from

`px` is declared as `SMA(period: 1)` on the primary 1d series — i.e. the primary bar's close:

| step | file:line |
|---|---|
| YAML / published config | `services/strategy-signal-service/src/main/resources/minervini-strategies/minervini-vcp.yaml` → `{ name: SMA, alias: px, timeframe: 1d, params: { period: 1 } }` |
| the primary series it evaluates on | `SwingBatchEngine.java:1089-1092` (`buildBank` → `primary` built from `series`) |
| the series fetch | `SwingBatchEngine.java:1116-1127` → `candles.fetch(EX="NSE", symbol, IV="1d", now−warmupDays, now)` |
| the HTTP read | `MarketDataCandlesClient.java:78` (`fetch`) → `:88` `GET /api/v1/market/candles`, **no `adjust` param** |
| controller default | `CandlesController.java:61` (`adjust=back`) → `:77-80` routes 1d through `splitBonusAdjuster.adjust(...)` |
| what the query reads | `CandleQueryService.java:103` → `repository.range(...)` → `CandleRepository.java:249-272` = `SELECT * FROM candles WHERE … "interval"='1d'` |
| the adjustment | `EquitySplitBonusAdjuster.java:34-53` — factor applied **only where `source='BHAVCOPY'`** (`:41`) |

**Ultimate table: `marketdata.candles` @ `interval='1d'`, `exchange='NSE'`.** **[sourced]**

### 1.2 Where `pivot` / `cheat` come from

| step | file:line |
|---|---|
| seeded as a flat context series | `MinerviniDoctrine.java:136-140` (`seeds.put(PIVOT, c.pivot())`) |
| turned into a 1-bar `EngineSeries` | `SwingBatchEngine.java:1109-1114` (`flat(...)`) |
| read by the gate | `IndicatorRegistry.java:168-171` (`VCP_PIVOT` → `SessionIndicators.contextLevel`), `:184-186` (`CHEAT_PIVOT`) |
| the funnel row it came from | `MinerviniFunnelService.java:67-76` — `LEFT JOIN minervini_setups s … s.pivot, s.cheat_pivot` |
| how that row was computed | `VcpDetector.detect(...)` — `pivot = last.peak()` (**the HIGH of the final contraction's peak bar**); `cheatPivot = last.trough() + cheatFraction × (last.peak() − last.trough())` |
| the bars it ran over | `DailyBarReader.java:21` → `AdjustedEquityDailySql.GEOMETRY_SYMBOL_SQL` |
| what that reads | `FROM nse_eod_bhavcopy … series IN ('EQ','BE')`, CA-adjusted by the SQL `factorLateral` over `eod_corporate_actions` |

**Ultimate table: `marketdata.nse_eod_bhavcopy`.** **[sourced]**

### 1.3 So the gate compares a candles CLOSE against a bhavcopy HIGH

Verified against LIVE published configs, not the YAML files:

```
          slug          | enabled | version |  status   |                    published gate
------------------------+---------+---------+-----------+------------------------------------------------------
 minervini-cheat-3c     | t       | 1.0.1   | published | crossover(px, cheat)  AND vol > 1.2
 minervini-power-play   | t       | 1.0.1   | published | crossover(px, pivot)  AND vol > 1.2 AND thrust > 0
 minervini-primary-base | t       | 1.0.1   | published | crossover(px, w52h)   AND vol > 1.2
 minervini-vcp          | t       | 1.0.1   | published | crossover(px, pivot)  AND vol > 1.2 AND px > sma20
```

**[computed]**

**Correction to #1243 §1.3:** it is not uniformly "the" cross-plane comparison —
`minervini-primary-base` is **not** cross-plane. `WEEK52_HIGH` is computed in-engine over the primary
series (`IndicatorRegistry.java:174-176` → `SessionIndicators.week52High`), so its level and its `px`
are both candles-plane. Of the **28** live Minervini entries, **21 are cross-plane** (12 `minervini-vcp`
+ 9 `minervini-cheat-3c`), **7 are same-plane** (`minervini-primary-base`), and `minervini-power-play`
has never fired. **[computed]**

**Premise verdict: TRUE, with the one strategy-level correction above.**

---

## 2. Question 2 — how often do the two planes differ *at the pivot*?

The brief asked whether a pivot behaves like #1243's 50-bar mean. **It does not, and the reason is
structural.** A pivot is one bar's high, and it is a *fresh* one.

### 2.1 The population

| quantity | value |
|---|---|
| screen dates persisted | **21** (2026-07-03 → 2026-08-03) |
| funnel candidates the doctrine actually serves (`passes_all` ∧ `is_vcp` ∧ pivot ∧ close ∈ [0.90·p, 1.05·p]) | **2,222** name-dates, **357** symbols |
| with a bar on **both** planes at the screen date + a previous bar on both | **2,221** |

`bucket(...)` is `MinerviniFunnelService.java:144-159`; the bands are the live defaults
(`buyable-low 0.98` / `buyable-high 1.05` / `on-deck-floor 0.90`), so the served set is
`close ∈ [0.90·pivot, 1.05·pivot]`. **[computed]** / **[sourced]**

### 2.2 The pivot is a 2-day-old level

Locating each pivot's source bar (the latest bhavcopy bar ≤ screen date whose adjusted high equals the
persisted pivot to < 0.5 paise):

| quantity | value |
|---|---|
| pivot source bar located | **2,220 / 2,222** (99.91%) |
| median age of the pivot bar | **2 calendar days** |
| age ≤ 3 days | 1,558 / 2,220 (70.2%) |
| age > 14 days | **8 / 2,220 (0.36%)** |
| max age | 24 days |

The 2 unlocated rows are both NARMADA (2026-07-17, 2026-07-20, pivot 38.50) and are a *retro-mutability*
artifact, not a defect: NARMADA's `SPLIT 1:2 ex 2026-07-31` row was `detected_at 2026-07-31`, so the
screens on 07-17/07-20 saw factor = 1 and persisted the raw high; today's recompute halves it to 19.25
and no longer matches. **This is the trap the brief warned about, caught in the act.** **[computed]**

### 2.3 The planes agree at the pivot bar

| measure | result |
|---|---|
| candles plane carries the pivot source bar | **2,220 / 2,220 (100%)** |
| pivot high identical (< 0.5 paise) on today's data | 2,211 / 2,220 (**99.59%**) |
| the 9 that differ | **all ABSLAMC (8) + INDOBORAX (1)**, and **every one** has `fetched_at` AFTER its screen date |
| pivot high identical, **as-of-honest** (window never rewritten after D) | **2,220 / 2,220 (100%)** |

**[computed]**

### 2.4 Why the 50-bar mechanism cannot reach a crossover

#1243's headline divergence was a **session-coverage** gap: the candles 50-bar window covered a
different set of sessions than the bhavcopy one. A crossover reads **two** bars. Measured over the
operative population:

| measure | result |
|---|---|
| candidate name-dates with a candles bar at D | 2,221 / 2,222 |
| candidate name-dates where the candles **previous bar date** ≠ the bhavcopy previous session | **0 / 2,221** |

The coverage gap is real in the wide window (260 bar-dates present in bhavcopy and absent from candles
over 2026-04-01…08-03, 357 symbols, 29,972 bhavcopy bars = 0.87%) but it **never lands on the
crossover's 2-bar window**. **[computed]**

---

## 3. Question 3 — what is the effect when they *do* differ?

### 3.1 Direction, derived from the gate

`GateEvaluator.java:58-77`: `crossover(fast, slow)` passes iff `fastNow > slowNow ∧ fastPrev ≤ slowPrev`
— i.e. the level `P` must lie in `[prev, close)`. `px` supplies both legs from the candles plane; `P` is
the bhavcopy-plane pivot.

Every observed divergence is the candles plane reading **LOWER** than bhavcopy (dividend back-adjustment,
§5 — ratio always < 1; measured `max_ratio` across all 357 candidate symbols is 1.000308, i.e. rounding
only). A lower `prev` **widens** the qualifying interval `[prev, close)`. So:

> **The cross-plane exposure is one-directional PERMISSIVE: the live gate can fire an entry the
> same-plane replay would not, never the reverse.**

It is confined to **exactly one session per event** — the ex-date session, the only bar at which `D` is
post-boundary (planes agree) while `D−1` is pre-boundary (planes diverge). From `D+1` on, both bars are
post-boundary and the planes re-converge. **[computed]** / **[sourced]**

### 3.2 In bars and in price

- **In bars:** ≥ 1 session early. The same-plane replay cannot fire on the ex-date session at all when
  `P ∈ [prev_adj, prev_raw)`; it would fire only on some later bar whose own previous close has fallen
  to or below `P`.
- **In price:** the permissive band is `[prev_adj, min(close_D, prev_raw))`, width bounded by the
  dividend. Measured on all 16 vulnerable sessions (§4.2), the band is **non-empty on only 4**:

```
   symbol   | ex_session | close_D_candles | prev_ADJUSTED | prev_RAW  | dividend | band_width_₹
------------+------------+-----------------+---------------+-----------+----------+--------------
 BFINVEST   | 2026-07-03 |        450.8500 |      450.7500 |  460.7500 |    10.00 |         0.10
 EXPLEOSOL  | 2026-07-31 |        802.8500 |      796.6000 |  906.6000 |   110.00 |         6.25
 IMPAL      | 2026-07-14 |       1144.9000 |     1130.5000 | 1153.5000 |    23.00 |        14.40
 ULTRACEMCO | 2026-07-30 |      11847.0000 |    11758.0000 | 11998.0000|   240.00 |        89.00
```

= 0.02% / 0.78% / 1.27% / 0.76% of price. On the other 12 the stock closed the ex-date session *below*
its dividend-adjusted prior close, so `[prev, close)` is empty on **both** planes and the divergence is
unreachable. **[computed]**

### 3.3 The opposite (restrictive) direction exists but is bounded by §2.2

For a screen date `D > ex_date` whose pivot source bar `P < ex_date`, the pivot is a cum-dividend
bhavcopy high compared against an ex-dividend price — the pivot is inflated, so the breakout
**under**-fires. This is a genuine second direction, but §2.2 bounds it hard: the median pivot is 2 days
old and only 0.36% exceed 14 days, so the ex-date must fall inside a ~2-session window. **Observed
occurrences: 0.** Both divergent candidate symbols were checked bar by bar — ABSLAMC's candidate dates
(07-03 … 07-20) all precede its 07-22 ex-date, and INDOBORAX's post-ex candidate dates (07-28/29/30)
carry pivots 426.00 and 398.90, which are the **07-27 and 07-29 highs** — both post-ex-date.
**[computed]**

---

## 4. Question 4 — population and materiality

### 4.1 The replay: 0 flips on the as-of-honest population

Replaying `crossover(px, level)` on both planes with the level held fixed (this isolates exactly the
cross-plane axis: only the `px` plane varies):

| population | n | fires live (candles px) | fires counterfactual (bhavcopy px) | **flips** |
|---|---|---|---|---|
| all evaluable, **today's data** — pivot | 2,221 | 353 | 354 | **1** |
| all evaluable, **today's data** — cheat | 2,221 | 427 | 428 | **3** |
| **as-of-honest** (both bars `fetched_at ≤ D`) — pivot | **2,121** | 338 | 338 | **0** |
| **as-of-honest** — cheat | **2,121** | 419 | 419 | **0** |

All 4 apparent flips are ABSLAMC on 2026-07-10 / 07-13 / 07-17, and all three windows carry
`fetched_at = 2026-07-23` — **10 to 13 days after the evaluation**. They are measurement artifacts of
the retro-rewrite, exactly the failure mode the brief flagged. On the clean subpopulation the two
planes' closes are identical at **both** bars in **2,121 / 2,121** cases. **[computed]**

### 4.2 The vulnerable-session census

The exposure needs three things to coincide: (a) a symbol whose candles history has been
dividend-rewritten, (b) the rewrite landing **before** the 20:00 IST batch
(`MinerviniSwingScheduler.java:48`, `cron = 0 0 20 * * MON-FRI`) on the ex-date session, and (c) a
funnel candidate on that session.

| stage | count |
|---|---|
| screened symbols | 1,813 |
| symbols with a **material** live plane divergence (≥ 0.5% on any bar, 2026-04-01…08-03) | **32 (1.76%)** |
| of those, the rewrite landed on the ex-date session **before 20:00 IST** → a vulnerable session existed | **16** |
| of those 16, symbols that have **ever** passed the Minervini screen | **1** (INDOBORAX; the other 15 have 0 screen passes ever) |
| of those, a funnel **candidate** on the vulnerable session | **0** — INDOBORAX was `passes_all=t` but `is_vcp=f` on 2026-07-21, so it carried no pivot |
| **live entries affected** | **0 of 28** |

INDOBORAX 2026-07-21 is the single genuinely live cross-plane crossover window in the whole 21-screen
history: the series was rewritten at **16:59:03 IST**, three hours before that evening's batch, so the
batch read `close(07-20) = 441.05` (dividend-adjusted) against a bhavcopy plane holding `481.05` — a
**9.3%** divergence on the crossover's previous-bar leg. It missed a candidate by one boolean.
**[computed]**

### 4.3 The live entries, for completeness

All 21 cross-plane entries, candles close at D minus bhavcopy close at D:

| measure | result |
|---|---|
| entries where the two planes' close at D differs | **0 / 21** (every row exactly `0.0000`) |
| tightest crossover margin | **0.311%** (PNBHOUSING 2026-07-03, entry 1064.40 vs pivot 1061.10 = ₹3.30) |
| median margin | ~2.3% |
| widest | 4.999% (MENONBE) |

So a plane divergence would have to exceed 0.311% to have flipped even the tightest live entry. The
measured divergence on those entries was zero — but the dividend divergences of §5 are 2.4% and 8.3%,
comfortably past that threshold. The margin is thin enough that this is a *luck* result, not a
*safety* result. **[computed]**

---

## 5. The actual defect: two planes hold opposite dividend doctrines

Every divergence in this investigation traces to one mechanism, and it is not corporate actions in the
split/bonus sense #1243 ruled out.

### 5.1 The two decisions

- **The bhavcopy plane deliberately ignores dividends.** `BhavcopyBackfillService.java:474-486`: when
  the NSE CA subject does not parse as a split/bonus it records the amount in `marketdata.dividends`
  and then `continue; // dividend / buyback / AGM — not a price adjustment`. `eod_corporate_actions`
  — the **sole** input to *both* `AdjustedEquityDailySql.factorLateral` and `EquitySplitBonusAdjuster`
  — therefore contains only `BONUS` (186) and `SPLIT` (149) rows and **zero dividends**.
- **The candles plane silently acquires them.** `CorporateActionJob` (the 16:30 IST Kite-diff anchor
  integrity job) detects *any* uniform-ratio divergence between Kite's closes and ours, purges, and
  re-fetches the whole series from Kite. Kite's history **is** dividend-back-adjusted. The job records
  a `corporate_action_events` row and **never writes `eod_corporate_actions`** — that table is written
  only by `BhavcopyBackfillService` (`source = 'NSE_CA_API'`).

Net: from the moment `CorporateActionJob` re-fetches, `candles` carries Kite's dividend adjustment and
the bhavcopy plane cannot ever learn about it. **[sourced]**

### 5.2 Proof, not inference

The `marketdata.dividends` table (4,743 rows, 1,664 symbols) holds the exact events, and they reproduce
the observed ratios to the paisa:

| symbol | dividend row | prior close | implied adjusted close | **observed candles close** |
|---|---|---|---|---|
| INDOBORAX | `Rs 10 Per Share / Special Dividend - Rs 30 Per Share`, ex 2026-07-21 | 481.05 (07-20) | 481.05 − 40.00 = **441.05** | **441.05** |
| ABSLAMC | `Dividend - Rs 25.50 Per Share`, ex 2026-07-22 | 1043.10 (07-21) | 1043.10 − 25.50 = **1017.60** | **1017.60** |

Confirming it is not a missed split/bonus: NSE bhavcopy's own `prev_close` on both ex-date rows equals
the **raw** prior close (no ratio restatement), and the `corporate_action_events` rows carry the
matching ratios (ABSLAMC 1.02508 = 1/0.97555, INDOBORAX 1.09069 = 1/0.91685). **[computed]**

### 5.3 The population in that state today

**32 of 1,813** screened symbols (1.76%); 1,956 of 143,119 shared bar-dates diverge ≥ 0.5% (1.37%).
**31 of the 32 carry a `corporate_action_events` row and 31 of 32 have zero `eod_corporate_actions`
rows.** The list is exactly what dividend adjustment predicts — ITC, INFY, RELIANCE, ULTRACEMCO, LICI,
GLAXO, ABBOTINDIA, ABSLAMC, UTIAMC — with ratios clustered at 0.97–0.98 and special-dividend outliers
at 0.872 (JAGRAN), 0.879 (EXPLEOSOL), 0.917 (INDOBORAX). **[computed]**

This is #1243 §5.5's observation ("410 of 448 CA events never reached `RESOLVED`") turning out to have a
concrete consequence: 31 of these 32 sit at `FAILED` / `REFRESH_FAILED` / `DETECTED`. But note the
divergence is **not** caused by the failure — a `RESOLVED` run rewrites the series just the same. The
failure statuses are a symptom of the same job, not the mechanism.

### 5.4 Why this is bigger than the crossover

The dividend split is **plane-wide, not gate-specific.** Anything that compares a bhavcopy-derived
number against a candles-derived number on one of these 32 symbols is exposed:
`TrendTemplateService`'s RS legs and 52-week high, `ManasScreenService`, `EquityReturnsService`,
`DailyBarReader`'s whole VCP geometry, and the funnel's own `pctToPivot`. The crossover is merely the
surface where it would move money first. **This document measures only the crossover; the rest is
unmeasured.** **[assumed]** for the breadth claim, **[sourced]** for the shared-reader list
(`AdjustedEquityDailySql`'s four consumers).

---

## 6. Question 5 — recommendation

### 6.1 On the plane split itself: **do not fix. Close it.**

Measured live exposure is **0 of 2,121** as-of-honest candidate name-dates and **0 of 28** entries —
strictly *smaller* than the 0.996% the owner already accepted at the selection boundary, and structurally
so: a 2-bar crossover against a 2-day-old level cannot be reached by either of #1243's mechanisms.
Unifying the planes is also not cheaply available in the obvious direction: `DailyBarReader`'s javadoc
states the geometry reads bhavcopy precisely because the candle store is sparse over the broad
universe, so moving the VCP geometry to `candles` would trade a zero-exposure inconsistency for a
coverage regression.

### 6.2 On the dividend doctrine split: **worth a decision, HOLD tier**

Not a code fix to schedule off this document — a doctrine question for the owner, because both halves
are deliberate:

- **Option A — teach the bhavcopy plane about dividends.** `marketdata.dividends` already holds
  amount + ex-date for 1,664 symbols, so the ratio is computable. Makes both planes dividend-adjusted.
  Touches every screener read path and would restate historical RS ranks — **HOLD tier, money/parity
  surface, needs a full Golden+Parity rerun and a backtest A/B.**
- **Option B — stop the candles plane acquiring them.** Refuse the `CorporateActionJob` re-fetch when
  the detected ratio matches a known dividend rather than a `SPLIT`/`BONUS` row. Smaller blast radius,
  keeps both planes dividend-*un*adjusted (the current bhavcopy doctrine), and would have prevented all
  32 divergences. Also HOLD — it changes what the live engine reads.
- **Option C — accept and instrument.** Neither plane changes; add a divergence probe so the 32 (and
  the ~4/month arrival rate) are visible rather than discovered by investigation.

**My recommendation is C now, then A or B as a deliberate owner decision.** The exposure is real but has
not fired in 21 screen dates, and the two fixes are both larger than the measured harm. What is missing
today is not a fix, it is *visibility*: nothing in the platform would have told anyone that 32 symbols
are being read two different ways.

### 6.3 Correction to file for #1243

#1243 §1.3 says the pivot crossover "is the sharpest cross-plane surface in the chain" and would "break
first under a corporate action landing between the screen run and the evening batch." Both halves need
narrowing: it is not a surface for `minervini-primary-base` at all, and the event class that actually
breaks it is a **dividend**, which is not a corporate action on either plane's definition — the split /
bonus case it names is the one case both planes handle identically.

---

## 7. Method

1. Plane A recomputed by transcribing `AdjustedEquityDailySql.SCREENER_BASE_CTE` /
   `GEOMETRY_SYMBOL_SQL` verbatim (`series IN ('EQ','BE')`, `round(px × exp(Σ ln ratio), 4)` over
   ratios with `ex_date > trade_date`, `exchange='NSE'`).
2. Plane B recomputed by transcribing `EquitySplitBonusAdjuster` verbatim — same factor, applied
   **only** where `source='BHAVCOPY'` — over `candles` @ `interval='1d'`, `exchange='NSE'`
   (the table `CandleRepository.range` reads; **not** the `candles_1d` cagg).
3. Crossover replayed as `GateEvaluator.cross` defines it: `close(D) > P ∧ close(D−1) ≤ P`, with
   `D−1` taken as **each plane's own previous row** (`lag(...) OVER (PARTITION BY symbol ORDER BY d)`)
   — the point of the exercise, since a plane that is missing a session has a different "previous bar".
4. As-of honesty gate: plane B restricted to windows where **both** bars carry
   `fetched_at ≤ screen_date`; the vulnerable-session census additionally compares the rewrite's IST
   **timestamp** against the 20:00 IST batch cron.
5. IST per the house rule: bounds as `timestamptz '…+05:30'` literals, rendering via
   `AT TIME ZONE 'Asia/Kolkata'` (never `'+05:30'`, which inverts). `bucket::date` never used.
6. `exchange='NSE'` filtered on every equity candle read.

Scripts in the session scratchpad; every number is a direct psql output against the live `artha`
database on 2026-08-03.

---

## 8. Open doubts

1. **The window is 21 screen dates, all July.** Same limitation as #1243 — `minervini_screen_results`
   starts 2026-07-03. 2,222 candidate name-dates bound the rate but cannot characterise it across a
   dense-dividend calendar (Indian dividend season peaks Jun–Aug, so July is arguably the *worst*
   month, which cuts in the finding's favour — but that is an argument, not a measurement).
2. **"0 flips" is measured on 2,121 of 2,221 (95.5%).** The other 100 windows were rewritten after
   their screen date and their as-of-D state is genuinely unknown, not merely unmeasured. All 100
   belong to the two divergent symbols plus routine re-fetches; I did not decompose them.
3. **The as-of gate uses `fetched_at ≤ D` at DATE granularity for the replay** and only tightens to a
   timestamp for the 16-session census. A bar rewritten late on D *after* the 20:00 batch would count
   as clean in §4.1. Given the observed rewrite times cluster at 16:50–17:15 IST this is unlikely to
   matter, but it is a real hole in that table.
4. **I did not replay the full entry gate.** `vol > 1.2` (and `px > sma20` for `minervini-vcp`) were not
   evaluated on either plane, so "fires" above means *the crossover leg alone*. That makes the flip
   counts an **upper** bound on entry differences — which is the safe direction for a zero result, but
   it means the 353/427 "fires" figures are not entry counts.
5. **§5.4's breadth claim is reasoned, not measured.** I traced which readers share
   `AdjustedEquityDailySql` and asserted they carry the same dividend exposure; I did not measure it on
   the RS legs, `ManasScreenService`, or `EquityReturnsService`. Someone should, before Option A or B
   is scoped.
6. **"Kite dividend-adjusts" is inferred from data, not from Kite documentation.** The ratio, the
   ex-date, the `marketdata.dividends` amount and the resulting close all agree to the paisa on both
   worked examples, and the 32-symbol list is composed of exactly the names one would predict — but I
   did not consult Kite's spec, and I did not verify all 32 individually (2 of 32 worked end to end).
7. **`fetched_at` is an upsert timestamp, not first-seen.** Used here only to answer "was this row
   rewritten after D", which is a sound use of it. It cannot recover historical state, and §2.2's two
   unlocated NARMADA pivots are a live demonstration of that on the *bhavcopy* side.
8. **I did not verify the deployed jar.** §1's Java citations are read off `origin/main` @ `23f92ba0`;
   the published *configs* and every DB number are authoritative live state, but whether
   `ay-strategy-signal-service` and `ay-market-data-service` run current `main` was not probed.
