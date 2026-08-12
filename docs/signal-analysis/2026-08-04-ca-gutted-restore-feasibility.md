# CA-gutted symbols — restore feasibility

**Date:** 2026-08-04 · **Type:** investigation only (no production code, no live mutation)
**Question:** can the corporate-action-gutted symbols be restored, and does it matter?

---

## Verdict

**Deep restore is possible — Kite still serves 1-minute history back to 2015-02-02 — but it barely
matters: nothing that currently screens or trades reads the destroyed data, and the one real
consumer (the swing *backtest* plane) needs only the daily bars, not the ~47 M minute bars.**

---

## 1. Premise check (STEP 0)

| Premise as briefed | Verdict | Measured |
|---|---|---|
| 45 symbols hold only `source='BHAVCOPY'` 1d bars, `base_1m = 0` | **CONFIRMED** | exactly 45, under the precise definition *zero 1m bars AND no `KITE` 1d bars* `[computed]` |
| 41 are in the screened universe | **CONFIRMED but inverted** | 41 are in the Minervini screen — and **all 41 are in it right now**, not missing `[computed]` |
| ~12 years of 1-minute data destroyed each | **CONFIRMED** | comparable intact symbols hold ~1,052,800 1m bars from 2015-02-02 `[computed]` |
| Bhavcopy projection reaches back only to 2025-10-21 | **TRUE of `candles`, MISLEADING** | the projection into `candles` is 44 *sparse* bars, but raw `nse_eod_bhavcopy` holds **276 complete sessions** per symbol back to **2025-06-20** `[computed]` |
| Job is DISARMED | **CONFIRMED by probe** | see below `[computed]` |
| — | **PREMISE INCOMPLETE** | a **further 14 symbols** have zero 1m but *intact* `KITE` 1d — a partial-damage tier the brief does not mention (59 total zero-1m) `[computed]` |

### Disarm confirmed by probe, not by reading `.env`

`.env` and `docker inspect` both show `ARTHA_CORPORATE_ACTIONS_ENABLED=false`, but per the house rule
a config value is not a deployed behaviour. `CorporateActionJob`'s constructor registers the counter
`ay_corporate_action_anchor_noise_total`; `@ConditionalOnProperty` un-registers the whole bean when
disabled. Live metrics:

```
ay_corporate_action_anchor_noise_total  -> 0 occurrences
jvm_memory_used_bytes (control)         -> 10 occurrences
```

The counter is absent while a control metric is present ⇒ **the bean was never constructed**. Last CA
event of any kind is `2026-07-31 17:16:04 IST`; nothing since. `[computed]`

> The `/actuator/beans` route 404s on this service — the first bean probe returned "no match" for that
> reason, not because the bean was absent. The counter/control pair is the distinguishing check.

---

## 2. The Kite depth measurement — the deliverable

**Kite serves complete 1-minute equity history back to 2015-02-02, measured as of 2026-07-31
17:05 IST (four days before this investigation).** `[computed]`

### Method — and why it is not a live API call

A live probe was **deliberately not made**. Two blockers, both of which would have violated the brief:

1. The Kite access token is **encrypted at rest** (`marketdata.kite_session` holds only
   `token_ciphertext` + `nonce`), so a direct call would require extracting and decrypting a secret.
2. Both service-mediated routes **write**: `GET /api/v1/market/candles` is cache-first (it upserts
   whatever it fetches) and `POST /api/v1/market/candles/refresh` forces a re-fetch. Either would
   mutate live data, which the brief forbids.

Instead the measurement uses **restores that the system already performed**, which is strictly
stronger evidence than a probe: it is the full-scale restore operation itself, completed.

### Evidence A — 18 symbols were purged and fully restored from Kite on 2026-06-23

The 25 `RESOLVED` corporate-action events are symbols `CorporateActionJob` purged and successfully
re-backfilled. Their current 1m series:

```
 tradingsymbol | bars_1m | oldest_1m_bucket | min_fetched | max_fetched
 ADANIENT      | 1052748 | 2015-02-02       | 2026-06-23  | 2026-06-23
 ASHOKLEY      | 1052806 | 2015-02-02       | 2026-06-23  | 2026-06-23
 BAJFINANCE    | 1052802 | 2015-02-02       | 2026-06-23  | 2026-06-23
 CONCOR        | 1052819 | 2015-02-02       | 2026-06-23  | 2026-06-23
```

`min(fetched_at) == max(fetched_at) == 2026-06-23` across **all ~1.05 M bars including the
2015-02-02 bar** ⇒ the entire 11.4-year 1-minute series was written in a single re-fetch on that
date. Kite served it. `[computed]`

### Evidence B — the deepest fetch is only four days old

Chunk-pruned probe of the 2015-02-02 → 2015-02-06 window (5 trading days):

```
 bars_feb2015 | symbols | oldest_fetch | newest_fetch
       140625 |      75 | 2026-06-23   | 2026-07-31
```

The most recent deep fetches, all `source='KITE'`:

```
 tradingsymbol | bars |        fetched_ist
 EXPLEOSOL     | 1875 | 2026-07-31 17:05:29
 ALLDIGI       | 1875 | 2026-07-31 16:56:52
 ULTRACEMCO    | 1875 | 2026-07-30 17:16:02
 ABBOTINDIA    | 1875 | 2026-07-24 16:52:31
```

**1,875 bars = 5 sessions × 375 minutes — complete coverage, no thinning.** These land at 16:5x–17:0x
IST, the `CorporateActionJob` 16:30 sweep window. `[computed]`

**Conclusion:** the `CorporateActionJob.java:141` comment ("defaults exceed Kite's serving depth
(~2015 for 1m)") is **accurate**, and the 4400-day `rebackfill-days-1m` default does cover it. The
brokers-cap-intraday-at-60-days concern does **not** apply to this account. `[computed]`

---

## 3. Per-symbol loss table (all 45)

Every one of the 45 is NSE. 44 of 45 are byte-identical in shape: **44** 1d bars spanning
2025-10-21 → 2026-08-03 (sparse, not contiguous), **0** 1m bars, **0** KITE 1d bars, and **276**
complete rows in raw `nse_eod_bhavcopy` from 2025-06-20. `M&MFIN` is the single exception. `[computed]`

| Symbol | 1d bars in `candles` | 1d earliest | 1d latest | raw bhavcopy rows | bhavcopy earliest |
|---|---|---|---|---|---|
| M&MFIN | 232 | 2025-08-22 | 2026-08-03 | 276 | 2025-06-20 |
| MCX, MONTECARLO, MPHASIS, MRPL, MSUMI, MUFTI | 44 | 2025-10-21 | 2026-08-03 | 276 | 2025-06-20 |
| NACLIND, NAUKRI, NAZARA, NDTV, NMDC | 44 | 2025-10-21 | 2026-08-03 | 276 | 2025-06-20 |
| ORIENTTECH, PATANJALI, PATELENG, PETRONET, PGHL, PIDILITIND, PTL | 44 | 2025-10-21 | 2026-08-03 | 276 | 2025-06-20 |
| RADIANTCMS, SHARDAMOTR, SIGMA, SIKKO, SILVERTUC, SKFINDIA, SMCGLOBAL, SPANDANA, STARPAPER | 44 | 2025-10-21 | 2026-08-03 | 276 | 2025-06-20 |
| TATAINVEST, TATASTEEL, TECHM, TIMETECHNO, TRENT, TRIDENT | 44 | 2025-10-21 | 2026-08-03 | 276 | 2025-06-20 |
| UGROCAP, UNIONBANK, UTKARSHBNK | 44 | 2025-10-21 | 2026-08-03 | 276 | 2025-06-20 |
| VARDHACRLC, VESUVIUS, VIMTALABS, VMART, VRLLOG | 44 | 2025-10-21 | 2026-08-03 | 276 | 2025-06-20 |
| WIPRO, ZEEL, ZFCVINDIA | 44 | 2025-10-21 | 2026-08-03 | 276 | 2025-06-20 |

Note the population skews to **liquid large/mid-caps** — WIPRO, TECHM, TATASTEEL, TRENT, NMDC,
PIDILITIND, NAUKRI, MPHASIS, PETRONET, UNIONBANK, MCX. 45 of a 2,596-symbol NSE-EQ candle universe is
1.7% by count, but far more than 1.7% by tradeability. `[computed]`

### How they died, and why nothing retried

All 45 carry exactly one `FAILED` event (latest detected 2026-07-15). Per
`CorporateActionJob:449-459`, `FAILED` means **the failure happened before the base committed** — the
purge ran, the Kite re-backfill did not finish. That class is deliberately terminal and never
resumed, so nothing has re-fired for them. `[sourced: CorporateActionJob.java:437-441]` `[computed: status counts]`

Worked example (WIPRO): detected `2026-06-30 17:10:20` → `FAILED` at `17:25:34` → the nightly
bhavcopy projection ran at `19:30` and wrote back the 44 sparse 1d bars. That is why the surviving
bars post-date the purge. `[computed]`

---

## 4. How many of the 41 would bhavcopy re-projection return to the screen?

**Zero — because none of them ever left.** `[computed]`

The live screen does **not** read `candles`. Both `ManasScreenService:211` and Minervini's
`TrendTemplateService:114` build their base CTE from `AdjustedEquityDailySql.SCREENER_BASE_CTE`,
which selects from **`nse_eod_bhavcopy`** directly (the VCP geometry reader does too). The gutting of
`candles` is invisible to it. `[sourced: AdjustedEquityDailySql.java:64-81]`

Applying the screen's own gate (≥252 sessions in the trailing 420 days, series `EQ`/`BE`) to all 45:

```
 gutted | min_sess | max_sess | meets_252_gate
     45 |      276 |      276 |             45
```

Confirmed empirically against the 2026-08-03 screens:

```
 manas_universe_rows | gutted_in_manas | gutted_in_minervini
                2262 |              45 |                  41
```

**All 45 are in the Manas screen and 41 in the Minervini screen today.** The brief's "41 in the
screened universe" is exactly this number — and they are present, not missing. `[computed]`

⚠️ One genuine fragility, unrelated to the gutting: `nse_eod_bhavcopy` spans only 2025-06-20 →
2026-08-03 = **276 sessions inside a 420-day window that requires 252**. The live screen has just
**24 sessions of slack**. It is not at risk today, but the daily plane has far less headroom than the
"12 years of history" framing suggests. `[computed]`

---

## 5. Does anything actually read equity 1-minute data?

**No armed consumer does.** `[computed]`

- **All 38 enabled scalpers** are `NIFTY 50` (NSE) or `SENSEX` (BSE) — index futures for signal,
  options for execution. Not one names an equity. `[computed: published configs of 48 enabled strategies]`
- **The 6 enabled equity strategies** (`minervini-*` ×4, `manas-arora-*` ×2) carry no
  `universe.underlying` — they are the daily-plane screens. `[computed]`
- **The 3m rollup** `CandleRepository.rangeRolledFromOneMinute` has exactly one production caller,
  `CandleQueryService:105`, serving the scalper 3m-primary path — i.e. index/options, not equities.
  `[sourced]`
- Equity 1m remains reachable only through the on-demand chart endpoint.

**So the ~47 M destroyed minute bars have no armed reader.** That is the single most important
finding for sizing the response.

### What *is* harmed: the swing backtest plane

`MinerviniBacktestService` and `ManasAroraBacktestService` read **`candles` @ `interval='1d'`**
directly (`MinerviniBacktestService.java:864,900`), and both swing backtests gate at
`MIN_BARS = 260` (`MinerviniSwingBacktest.java:78`, `ManasAroraSwingBacktest.java:118`). A 44-bar
series cannot clear it, so **all 45 are silently dropped from every swing backtest while being fully
present in the live screen.** `[sourced + computed]`

That is a live-vs-backtest plane divergence in the same family the plane-split docs track: backtests
are being run on a universe that excludes 45 liquid names the live funnel actually surfaces. It
biases results in an undeclared direction and is invisible in the output.

---

## 6. Cost and risk of a staged restore

Measured from the batch that actually succeeded (2026-06-23): **18 symbols with full 2015-depth 1m
restored between 16:48:35 and 20:43:21 IST = 3 h 54 m 46 s ≈ 13 min/symbol** (serial single-thread
executor + Kite rate limiter). Extrapolated to 45 symbols: **≈ 9.8 hours** of wall clock. `[computed]`

| Resource | State | Constraint? |
|---|---|---|
| Disk | 891 GB available, 7% used; DB 45 GB | **No** |
| DB RAM | **2.01 GiB / 4 GiB (50.3%)** — the brief's 77% is not the current figure | Yes, the live one |
| Rows added by full 1m restore | ~47 M (45 × ~1.05 M) | Moderate |
| Rows added by 1d-only restore | ~225 K (45 × ~5,000) | Negligible |

**The OOM risk is not in the fetch — it is in the cagg refresh.** The three historical OOMs and the
`ca-rebuild-wipes-caggs` topic all sit at `refreshDerivedAggregatesForRebuild`, and
`CorporateActionJob:313-315` already defers it behind a `BASE_REBUILT` checkpoint for exactly this
reason. A restore that fetches **1d only** never touches the 1m-derived caggs at all, which removes
the entire OOM surface. `[sourced + computed]`

---

## 7. Recommendation, sized to what was measured

**Do the cheap daily restore; do not restore 1-minute; do not re-arm the job.**

1. **Restore 1d from Kite for the 45 — ~225 K rows, no cagg refresh, no OOM surface.** This is the
   only change that fixes a real defect (the backtest plane excluding 45 liquid names). Kite serves
   1d back to ~2006, so this restores the *full* daily depth, not the 276 bhavcopy sessions.
   Bounded per symbol with a memory check between slices; no need for `CorporateActionJob`.
2. **Do NOT restore the 1-minute history.** ~47 M rows, ~9.8 h, and the OOM-prone cagg refresh — to
   serve **zero armed consumers**. Revisit only if an equity-intraday strategy is ever proposed.
   Kite's depth has been measured as available, so this decision is reversible; it is not a
   now-or-never window.
3. **Bhavcopy re-projection is a viable fallback but is not needed for the screen.** `refetchDate`
   (`BhavcopyBackfillService:200`) re-projects per trade date DO-NOTHING, and could restore 276
   sessions/symbol. It buys nothing for the live screen (already unaffected) and only 276 of the
   ~5,000 daily bars option 1 would restore.
4. **Leave the job disarmed** until the terminal-`FAILED`-never-retried behaviour is addressed — a
   purge that fails before the base commits currently leaves the symbol permanently worse off with
   nothing that will ever retry it. That is the actual bug; the 45 are its output.
5. **"Do nothing" is defensible for the 1m question and only that.** Doing nothing about the *1d*
   gap leaves the backtest plane quietly wrong, which is the one thing here with a live consequence.

---

## Open doubts

1. **The Kite depth measurement is an observation of past fetches, not a live probe.** It is four
   days stale (2026-07-31). If Kite changed its serving depth in the last four days, this would not
   show it. I judged that acceptable against the brief's "mutate no live data" and the encrypted
   token, but a live probe remains the only way to make it *today's* fact. It is also possible the
   depth is account/plan-scoped in a way that could change without notice.
2. **`fetched_at` is an UPSERT timestamp and bounds rather than pins.** The claim "the entire series
   was written on 2026-06-23" rests on `min == max` across ~1.05 M rows, which is strong, but a
   re-write that touched every row identically would look the same. The corroborating 2026-07-31
   evidence is independent of this.
3. **I did not verify the 45 have no *other* difference from the 14-symbol partial tier.** I
   classified on two predicates (zero 1m, no KITE 1d). There may be a third relevant axis.
4. **Why the bhavcopy projection wrote exactly 44 sparse, irregularly-spaced bars is unexplained.**
   Raw bhavcopy has 276 complete sessions for these symbols, so something restricts the projection.
   I did not trace it. It does not change any conclusion (the screen reads raw bhavcopy) but it means
   my "276 sessions restorable by re-projection" figure is a *ceiling* I did not demonstrate.
5. **The 2,596-symbol universe figure is bounded to symbols with a 1d bar since 2026-07-01**, not the
   exact `eqSymbols()` population, which I avoided running (unbounded `DISTINCT` over a 22 GB
   hypertable). The 1.7% proportion is therefore approximate.
6. **`resolved_at` has a max of 2026-07-31 while every `status='RESOLVED'` row shows 2026-06-23** —
   the column is evidently set on non-RESOLVED transitions too. I did not chase this; it does not
   affect the restore question but it means `resolved_at` should not be read as "when it resolved".
7. **I did not measure whether restoring the 45 would actually change any backtest verdict.** The
   claim is that their exclusion is an undeclared bias, not that correcting it flips a result.
