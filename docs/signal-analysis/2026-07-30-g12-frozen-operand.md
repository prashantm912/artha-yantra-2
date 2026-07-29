# G12 — the frozen `atmIv` operand: cause established, probe built

**Written 2026-07-30.** Closes the diagnosis half of ledger row **G12** and ships the probe half.

---

## Verdict

**The `atmIv` freeze is CORRECT BEHAVIOUR of the feed and a DEFECT of the dot — established by a
code read of all five links in the chain and confirmed against four sessions of live data, not
inferred.** `iv_abs_band` scores a number that is (a) computed once a day at 16:00 IST, (b) therefore
always the *previous* trading day's, and (c) not actually an ATM IV. No intraday refresh is possible
without changing what the field means, so **there is nothing to fix on the producer side** — which is
the opposite of the working hypothesis the row carried.

The **frozen-operand probe** is the deliverable that survives, and it was always the more valuable
of the two: every alive/dead probe in the `DotHealthCanary` registry shares the blind spot, and the
probe closes it for all of them at once.

---

## 1. The chain, link by link (all `sourced`, read 2026-07-30)

| # | site | what it does |
|---|---|---|
| 1 | `ConnectTheDotsScorer.java:210-212` | `ivAbsOk = atmIv != null && atmIv >= ivAbsBandLow && atmIv <= ivAbsBandHigh` (0.10–0.12, w 0.8) |
| 2 | `MarketOiClient.java:508-517` | `atmIv = decimal(ivHistory.path("currentIv"))` from `GET /api/v1/market/options/iv-history` |
| 3 | `IvAnalyticsService.java:104-133` | `currentIv = rankStat(ivs, …).current() = ivs.get(n-1)` — the LAST element of the daily series |
| 4 | `IvDailySummaryRepository.java:119-123` | `SELECT … FROM iv_daily_summary WHERE underlying = ? ORDER BY summary_date` — **one row per DAY** |
| 5 | `IvRollupJob.java:43` | `@Scheduled(cron = "0 0 16 * * MON-FRI", zone = "Asia/Kolkata")` — written **once, after the close** |

Link 4 is the whole answer. The series the gate reads has **one row per trading day**, so "current"
means *most recent day*, never *most recent bar*. Intraday there is no row for today yet — link 5
writes it at 16:00 — so every evaluation from 09:15 to 15:30 reads the same previous-session scalar.
**One distinct value per session is not a bug; it is the only value that exists.**

## 2. Live confirmation — four sessions, exact

`iv_daily_summary` for `NIFTY 50` (live `artha`, read 2026-07-30) against the four `atmIv` values
recorded in the 07-29 findings:

| session observed | gate `atmIv` | `summary_date` | column | lag |
|---|---|---|---|---|
| 2026-07-24 | 0.130859 | 2026-07-23 | `iv_30d` | 1 trading day |
| 2026-07-27 | 0.135577 | 2026-07-24 | `iv_30d` | 1 trading day |
| 2026-07-28 | 0.121736 | 2026-07-27 | `iv_30d` | 1 trading day |
| 2026-07-29 | 0.118781 | 2026-07-28 | `iv_30d` | 1 trading day |

Four for four. `computed_at` on every row is 15:59:59–16:00:01 IST, matching link 5's cron.

## 3. The finding the row did not have: it is not the ATM IV

The matches above land on **`iv_30d`**, not `atm_iv`. `IvAnalyticsService.java:117` is explicit:

```java
BigDecimal iv = s.iv30d() != null ? s.iv30d() : s.atmIv();
```

`iv_30d` is the **30-day interpolated** IV (`IvAnalyticsService.interpolate`, straddling the two
expiries around the 30-day point), not the ATM IV of the traded expiry. The `atm_iv` column is
outright **NULL on 2026-07-28 and 2026-07-21**, and the 07-29 session still read 0.118781 — which is
07-28's `iv_30d`, proving the fallback order empirically rather than only from the source.

So the dot named `iv_abs_band`, testing a band called `atmIv`, is scoring **yesterday's 30-day
interpolated IV**. Three separate mismatches — stale by a day, wrong tenor, wrong name — on a
w 0.8 term of an intraday 3m confluence composite.

⚠️ This does NOT mean the band is wrong. A daily IV-regime filter ("only scalp when IV sits in a
band") is a legitimate design, and if that is the intent then the operand is defensible and only the
NAME is misleading. What is not defensible is leaving it ambiguous: the term currently contributes a
**per-day constant** to a per-bar score, so on 2026-07-28 it withheld 0.8 of weight from all 180
evaluated rows and on 2026-07-29 it granted 0.8 to all 133. That is a composite-level offset
masquerading as a signal.

**Which of those two readings is correct changes which signals fire, so it is HOLD/owner and it is
NOT actioned here.** It folds into G13's IV-bloc decision — three of the composite's IV dots
(`iv_rank` NULL, `iv_pair` parity-pinned, `iv_abs_band` frozen-and-mistenored) are now each
degenerate for a different reason, and they want one coordinated read, not three patches.

## 4. What shipped: the frozen-operand probe

`DotHealthCanary` gains a second liveness dimension. Every probe in the registry now carries an
`operand` extractor alongside its `alive` predicate; the sweep counts **distinct operand values over
distinct bars** and flags `frozen` when ≥ `MIN_FROZEN_BARS` (8) bars carry the operand and they all
agree. Surfaced on `DotState.frozen` (`GET /api/v1/signal-rejections/dot-health`) and as a chip on
the rejections page.

Three design decisions worth keeping:

1. **Counted per BAR, never per row.** The engine fans one 3m bar across ~63 scalpers, so the 40-row
   window can be a *single* bar whose macro context is identical by construction — counting rows
   would call a perfectly live input frozen. This is the same fan-out inflation that made the
   champion shadow book's 24 rows read as 24 independent observations when the effective sample was
   ~6. Guarded by `oneBarFannedOutAcrossStrategiesIsNeverFrozen`, which was **red-proofed**: with the
   counter switched to rows, that test and only that test fails.
2. **Frozen reports, never pages.** `iv_abs_band` freezes legitimately every single session, so a
   paging frozen-probe would emit a guaranteed daily false alarm. Paging still keys on `alive` alone.
   `Probe.dailyByDesign` marks the by-design case so its detail line says so instead of crying wolf.
3. **Appended to the detail, never substituted.** A dot can be dead *and* frozen — an all-`NEUTRAL`
   quadrant window is both — and the liveness half is the half that pages, so it must not be
   overwritten. An S24-suppressed read stands the frozen flag down entirely: on a monthly index
   expiry the OI block is skipped by design, and its single inert value is inertness, not a freeze.

`iv_abs_band` also gains a probe — **it had none at all**, which is why nothing ever reported on it.

---

## Claim labels

- Links 1–5, the `iv30d`/`atmIv` fallback, and the NULL `atm_iv` days: **sourced** (file:line read
  2026-07-30; SQL against live `artha` the same day).
- The four-session lag table: **computed** this session by joining the 07-29 findings' recorded
  values against `iv_daily_summary`.
- "A daily IV-regime filter may be the intended design": **assumed** — no design doc states the
  intent for this dot, which is exactly why the disposition is owner's and not mine.
