# H6 — `source.optionanalytics`: native vs Upstox, live market-hours comparison

**Date:** 2026-08-11 (Tuesday) · **Window:** 12:16–13:17 IST · **Stack:** LIVE (`SPRING_PROFILES_ACTIVE=live`, DB `artha`)
**Scope:** NIFTY 50 + SENSEX PCR and max-pain · **Nothing was flipped, restarted, or edited** — read-only probes only.

---

## Verdict

**Yes — the Upstox path is as fresh as native, to within ±1 three-minute capture bucket, and neither
side is systematically ahead.** Across 12 paired samples the Upstox insight timestamp was one bucket
*ahead* of the native snapshot bucket 3 times, *equal* 7 times, and one bucket *behind* 2 times. No
sample diverged by more than a single bucket. Strictly, "at least as fresh" is not true on every
sample — but the exceptions are one 3-minute bucket in a pipeline whose capture cadence is itself
3 minutes, i.e. poll-phase noise between two independently-polled sources, not staleness.

Agreement is at the noise floor: **median PCR delta +0.000%, mean −0.034%, worst |delta| 0.356%**,
and **max-pain identical in 12/12 samples**. Upstox read higher 4×, lower 5×, exactly equal 3× — no
direction.

**This confirms and extends the 2026-08-03 measurement rather than changing it. The owner's standing
decision (KEEP `upstox`) is unaffected by anything measured here.**

---

## ⚠️ The premise this run was dispatched on was false

The task brief stated `source.optionanalytics` "is `native` today" and that the live freshness check
"has never once run". Both are wrong, and the ledger already said so:

- `sourced` — `docker inspect ay-market-data-service`: **`ARTHA_MD_SOURCE_OPTIONANALYTICS=upstox`**,
  with `ARTHA_UPSTOX_ANALYTICS_ENABLED=true` and `SPRING_PROFILES_ACTIVE=live`, so all three bean
  conditions at `UpstoxOptionAnalyticsSource.java:32` are satisfied. The Upstox route has been the
  deployed one for ~5 weeks.
- `sourced` — ledger row **H6** already carries `⚠️✅ PREMISE FALSIFIED 2026-08-03 … OWNER DECIDED
  2026-08-03: KEEP upstox. Row CLOSED.`

The brief's own "Config facts, verified 2026-08-10" cite `application.yml:87`'s
`${ARTHA_MD_SOURCE_OPTIONANALYTICS:native}` — **the YAML default read as if it were the deployed
value**, which is the exact trap the brief itself warns about three lines later. Root cause is the
same one the ledger recorded: the row's status token still read `**OPEN.**` while its body said
`CLOSED`, so a top-down status read picks up the stale token.

**What was still genuinely open**, and what this run therefore measured, is the closed row's own
recorded open doubt: *"all agreement numbers are NIFTY 50 / one expiry / one session; SENSEX and
BANKEX untested, and 100% strike coverage was measured today only."* SENSEX and the second session
are now closed. BANKEX remains untested.

---

## Method

Both sides were read **without changing the deployed source**, by exploiting the fact that the two
values are served by different, independently-sourced endpoints:

| Quantity | Endpoint | Source |
|---|---|---|
| Upstox PCR + max-pain | `GET /api/v1/market/options/oi-stats` | Upstox (overridden at `OptionsAnalyticsController.java:341-354`) |
| Native PCR + max-pain | `GET /api/v1/market/context/options-digest` | native fold — never overridden |
| Upstox data timestamp | last insight of `GET /api/v1/market/options/pcr-series` | Upstox `/pcr` insights |
| Native data timestamp | `oi-stats` → `freshness.asOf` / `staleSeconds` | native snapshot bucket |

Probes went **direct to the service on `127.0.0.1:8081` inside the container**, bypassing the gateway
(no auth, no edge caching). Sampler: `scratchpad/h6_sample.sh`, 4 rounds ~20 min apart.

### Two checks that had to run before the numbers meant anything

1. **Is the "upstox" figure actually Upstox, or native wearing its label?** `statsOrNull` returns
   `null` on *any* miss and the caller then silently keeps native — so an exact native/Upstox match is
   indistinguishable from a fallback by response inspection alone. `computed` —
   `docker logs ay-market-data-service --since 90m | grep -c 'keeping native'` = **0** across the whole
   sampling window. No fallback fired; every Upstox figure below is genuinely Upstox-sourced. (This
   mattered: SENSEX matched native *exactly* in 2 of 4 rounds.)
2. **Is `pcr-series` the Upstox path or the native fold?** The native fold emits exactly
   `trendBuckets`=20 points; the observed series carried **61→81 points** growing through the session
   at 3-minute spacing, i.e. the full-day Upstox insight series.

---

## Results — 12 paired samples

`delta% = (upstox − native) / native`. `natBkt` = native snapshot bucket, `upBkt` = last Upstox
insight, both IST.

| Time IST | Pair | native PCR | upstox PCR | delta% | natBkt | upBkt | freshness | max-pain equal |
|---|---|---|---|---|---|---|---|---|
| 12:16 | NIFTY 50 front (**expiry today**) | 0.7039 | 0.7020 | −0.270% | 12:12 | 12:15 | upstox +3m | yes |
| 12:16 | NIFTY 50 next weekly (08-18) | 0.9001 | 0.9008 | +0.078% | 12:15 | 12:15 | equal | yes |
| 12:16 | SENSEX front (08-13) | 0.6548 | 0.6548 | 0.000% | 12:12 | 12:15 | upstox +3m | yes |
| 12:36 | NIFTY 50 front | 0.6954 | 0.6940 | −0.201% | 12:33 | 12:33 | equal | yes |
| 12:36 | NIFTY 50 next weekly | 0.9021 | 0.9023 | +0.022% | 12:33 | 12:36 | upstox +3m | yes |
| 12:36 | SENSEX front | 0.6564 | 0.6541 | −0.350% | 12:33 | 12:33 | equal | yes |
| 12:56 | NIFTY 50 front | 0.7032 | 0.7039 | +0.100% | 12:54 | 12:54 | equal | yes |
| 12:56 | NIFTY 50 next weekly | 0.9078 | 0.9066 | −0.132% | 12:54 | 12:54 | equal | yes |
| 12:57 | SENSEX front | 0.6633 | 0.6633 | 0.000% | 12:54 | 12:54 | equal | yes |
| 13:17 | NIFTY 50 front | 0.6918 | 0.6918 | 0.000% | 13:15 | 13:15 | equal | yes |
| 13:17 | NIFTY 50 next weekly | 0.9050 | 0.9049 | −0.011% | 13:15 | 13:12 | native +3m | yes |
| 13:17 | SENSEX front | 0.6740 | 0.6764 | +0.356% | 13:15 | 13:12 | native +3m | yes |

`computed`, n=12: **median +0.000% · mean −0.034% · max |delta| 0.356%**; upstox higher 4 / lower 5 /
exactly equal 3. **Max-pain identical 12/12** (NIFTY front 24450.00, NIFTY next 24500.00, SENSEX
78200.00 — unchanged all hour on both sides).

Per pair:

| Pair | n | median delta | max abs delta |
|---|---|---|---|
| NIFTY 50 front (expiry today) | 4 | −0.101% | 0.270% |
| NIFTY 50 next weekly | 4 | +0.006% | 0.132% |
| SENSEX front | 4 | +0.000% | 0.356% |

SENSEX shows the widest spread but no bias — its two non-zero samples point in opposite directions.
On this session's evidence SENSEX behaves like NIFTY, closing that half of the recorded open doubt.

---

## Supporting measurements

**Strike coverage — 15/15 expiries at 100%** (`computed`, SQL over `marketdata.instruments` vs
`marketdata.options_chain_snapshots` for today):

| Underlying | expiries | listed strikes | captured | missing |
|---|---|---|---|---|
| NIFTY 50 | 7 (08-11 … 10-27) | 113/105/121/105/73/123/100 | all equal | 0 |
| SENSEX | 8 (08-13 … 10-29) | 144/134/176/130/130/112/160/112 | all equal | 0 |

This reproduces the 2026-08-03 NIFTY result on a second session and extends it to SENSEX. The flip's
original rationale, still written into the code at `UpstoxOptionAnalyticsSource.java:24-27` — *"native
PCR is computed over our captured ATM strike band, which omits deep-OTM strikes Upstox's FULL chain
includes"* — is **obsolete on both indices**: native captures the entire listed strike universe.
(Documentation-only defect; the comment misdescribes why the source exists, it does not misbehave.)

**Upstox replica jitter — reproduced** (`computed`). Five back-to-back `/oi-stats` reads on one
unchanged bucket:

- NIFTY 50 08-18: `0.9008 / 0.9000 / 0.9000 / 0.9008 / 0.9008` — two values ~0.089% apart, alternating.
- SENSEX 08-13: `0.6548` ×5 — stable across the same test.

Native is byte-identical across repeated reads by construction. This matches the 2026-08-03 finding
(two backend replicas) and is the one behavioural cost of the Upstox source: the OI-Statistics header
can flicker between two adjacent values while the underlying bucket has not moved. It is cosmetic —
no consumer arms on it (see below) — but it is real and it is the only respect in which native is
strictly better.

**Consumer surface — unchanged, read-only** (`sourced`): `frontend-react/src/pages/options/OiStatisticsPage.tsx`
and `.../features/MultipleWindowPage.tsx` via `src/api/oiAnalytics.ts` / `oiStatsFold.ts`. The override
writes nothing to the DB; `market_context_days.pcr_eod` / `max_pain_eod` and the scalper OI-confluence
dots are fed by natively-computed paths and are untouched by this flag.

---

## Session representativeness

`sourced` — **today is a NIFTY weekly expiry (Tuesday 2026-08-11), not a monthly** (August monthly is
2026-08-25; the captured NIFTY expiry ladder is 08-11 / 08-18 / 08-25 / 09-01 / 09-08 / 09-29 / 10-27).
The monthly-expiry OI-suppression caveat therefore does **not** apply.

The NIFTY *front* expiry is nonetheless today, so that pair is an expiry-day read with front-expiry OI
decaying through the session — which is why the 08-18 weekly was sampled alongside it as the
representative NIFTY pair. SENSEX front (08-13, Thursday) is a normal non-expiry read. Notably the
expiry-day pair is *not* the widest-diverging one, so expiry does not appear to stress the comparison.

---

## Claims ledger

| Claim | Label | Evidence |
|---|---|---|
| Deployed source is `upstox`, not `native` | `sourced` | `docker inspect ay-market-data-service` env |
| No Upstox fallback fired during sampling | `computed` | 0 matches for `keeping native` in 90 min of container logs |
| `pcr-series` served the Upstox path | `computed` | 61→81 points at 3-min spacing vs native fold's fixed 20 |
| PCR median delta +0.000%, max 0.356%, n=12 | `computed` | table above, `scratchpad/h6_samples.tsv` |
| Max-pain identical 12/12 | `computed` | table above |
| Upstox within ±1 bucket of native, both directions | `computed` | natBkt vs upBkt columns |
| 100% strike coverage, 15/15 expiries | `computed` | SQL, `instruments` vs `options_chain_snapshots` |
| Replica jitter ~0.089% on NIFTY | `computed` | 5 back-to-back reads, same bucket |
| Today is a weekly, not monthly, NIFTY expiry | `sourced` | captured expiry ladder + NSE Tuesday convention |
| H6 owner decision 2026-08-03 was KEEP `upstox` | `sourced` | ledger row H6 |

## Open doubts

- **BANKEX is still untested** — the last surviving fragment of the 2026-08-03 open doubt. It is in
  `INSTRUMENT_KEYS` and was capturing today, so the measurement is cheap; it simply was not in this
  brief's scope (NIFTY + SENSEX).
- **One hour of one session, 4 rounds.** Combined with 2026-08-03 that is two sessions, but both are
  mid-day windows — the open auction (09:15–09:30) and the close (15:15–15:30), where a
  freshness difference would be most likely to appear, remain unsampled on both dates.
- **The freshness proxy is not literally the `oi-stats` call.** `statsOrNull` reads Upstox at
  `BUCKET_MINUTES=60` and takes the day-level `data.*`; the timestamp column above comes from the same
  Upstox `/pcr` endpoint bucketed at 3 min. It measures the Upstox *pipeline's* freshness faithfully,
  but the two calls are not byte-identical requests.
- **One pre-round outlier, reported for completeness.** An ad-hoc SENSEX probe at ~12:14 IST, before
  the structured rounds began, read upstox `0.6480` vs native `0.6548` (−1.04%) — 3× the worst
  structured sample. It was a single unpaired read and is excluded from the statistics above; it was
  not reproduced in any of the 4 rounds.

## Recommendation

**No action.** Do not flip. The 2026-08-03 owner decision stands on stronger evidence than before:
agreement now holds on a second session, on a second index, and across the full listed strike
universe of 15 expiries. If anything is worth doing it is a **docs-only** correction of the obsolete
rationale comment at `UpstoxOptionAnalyticsSource.java:24-27`, which now states a reason for the
source that the data contradicts — filed as a follow-up, not done here.
