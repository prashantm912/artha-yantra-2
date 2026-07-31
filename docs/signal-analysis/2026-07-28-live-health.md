# Live in-session data-health check — 2026-07-28 (data date)

Analysis date: 2026-07-28, ran 09:43–09:57 IST (scheduled `session-analysis live`).
Analyst: Claude (scheduled live-data-health task). **Read-only run** — no restarts, no writes, no
deploys during market hours.
Verdict: **GREEN.**

Scope note: this is the mid-session §4.1 data-health watch.
[`2026-07-28-open-gate.md`](2026-07-28-open-gate.md) covered the open gate; the evening
`session-analysis post` run owns `2026-07-28-session-findings.md` and should fold this in.

⚠️ **2026-07-28 is an NSE MONTHLY index-expiry day** (last Tuesday). That single fact explains most
of what looks anomalous below and is the reason this file exists as more than a GREEN stamp.

---

## 0 Verdict

**GREEN.** Both machine canaries healthy, the engine is receiving *and* evaluating bars, capture is
full, and no data-integrity probe failed. Nothing for the owner to act on.

Two things worth carrying, one operational and one shipped:

1. **Every OI-derived dot is dead today, and all of it is BY DESIGN** (§6). `MarketOiClient.oi()`
   skips the entire OI block on a monthly index expiry (S24). The composite is therefore
   structurally starved — **max 0.3457 against a 0.600 threshold** — so zero fires today is the
   expected mechanical outcome and **no gate-calibration conclusion may be drawn from this session.**
2. **A real diagnostic defect fell out of chasing #1, and is already fixed and merged** — the
   canary's expiry exemption was both too narrow (missed `oi_spurt_price`) and mis-keyed (a
   calendar-OR where the suppression is per OI root). [#1073](https://github.com/prashantm912/artha-yantra-2/pull/1073),
   merged `edd2a2b1`; deploy deliberately deferred to after the close — **since deployed 16:43:29 IST
   and verified PASS on the live endpoint**, see `2026-07-28-session-findings.md` §6.3.1.

## 1 Preconditions

| check | result |
|---|---|
| Host-clock guard (B8) | Host `[DateTime]::UtcNow` = **2026-07-28 04:13:11**, container `now()` = **04:13:11.718**. **<1 s** apart — no ⚠ CLOCK-DRIFT line, container time not substituted. |
| Trading day / in-session | Tue 2026-07-28, **09:43 IST**, inside 09:15–15:30. **NSE monthly index expiry** (BSE monthly is Thu the 30th — the two do NOT coincide today, which matters in §6.2). |
| Stack | **11/11** `ay-*` containers healthy. strategy-signal up ~7 h, no restart today. |

## 2 Check 1 — engine liveness (counters, never `signal_rejections`)

| sample (IST) | `chart-gate-failed` | `composite-below-threshold` | `confluence-blocked` | **Σ** | `..._received_age_s` | `..._evaluated_age_s` |
|---|---|---|---|---|---|---|
| ~09:46 | 30 | 24 | 5 | **59** | 13.065 | 73.306 |
| 09:48:11 | 36 | 30 | 6 | **72** | 10.540 | 10.531 |

`ay_signal_eval_failures_total` = **0** at both samples. `fired`, `discipline-paused`,
`confluence-gate-absent`, `unscoreable-indicators-warming` = 0 throughout.

**Σ ADVANCING 59 → 72 ⇒ engine alive.** Both gauges fresh and non-negative at both samples (no `-1`
"no bar this boot", no sub-`-1` clock fault) — README §4.3's *received fresh + evaluated fresh* row.
The 73 s evaluated-age in the first sample is inside a 3m bar interval, not a stall; the second
sample shows the two ages converged.

Corroborator — `strategy.signal_eval_outcomes`, latest bucket only (never a session-wide `sum()`):
**09:48 → 36**, 09:45 → 36, 09:42 → 4, 09:39 → 4. Fresh, non-zero, landing every 3 min. The 4 → 36
step at 09:45 is the same trade-window ramp documented in README §4.1 on 07-27, reproduced exactly.

Context (not the liveness signal): `strategy.signal_rejections` since 09:15 went **10 rows / 4 slugs
(max 09:52:14)** → **26 rows / 16 context-bearing (09:56)**.

## 3 Check 2 — shadow book + variants

**Zero shadow positions opened today, and that is correct.** No rejection passed composite — see
§6.1 for *why* the composite could not get there today.

| slug family | side | rows | composite | threshold | passed |
|---|---|---|---|---|---|
| `scalp-morning-trade-{nifty,sensex-niftyoi}` | CE | 4 | 0.266–0.346 | 0.600 | 0 |
| `scalp-golden-crossover-{nifty,sensex-niftyoi}` | PE | 6 | *(blocked at `option-side-constraint`, no composite scored)* | — | 0 |

Across all 16 context-bearing rows: composite **min 0.1961 / max 0.3457**, threshold 0.600.

League (`GET /api/v1/signal-rejections/shadow-summary`, all-time, unchanged today):

| variant | closed | wins | pnl points | **pnl NET ₹** | unpriced |
|---|---|---|---|---|---|
| `champion` | 219 | 92 | −246.50 | **−35,153.63** | 0 |
| `vol-off` | 48 | 14 | −388.90 | **−17,014.10** | 0 |
| `vol-12k5` | 38 | 12 | −218.30 | **−9,331.31** | 0 |
| `composite-055` | 11 | 3 | −10.20 | **−1,542.15** | 0 |

`unpriced = 0` across all four books ⇒ no NFO lot-size lookup failures. No close carries a null
`pnl_net`. Zero OPEN positions in any book.

## 4 Machine canaries (STEP 0)

| canary | result |
|---|---|
| `GET /api/v1/market/health/data` | `{"status":"GREEN","marketOpen":true,"asOf":"2026-07-28T04:15:00Z","tickedTokens":93,"problems":[]}` — data plane machine-verified, `tickedTokens > 0`. |
| `GET /api/v1/signal-rejections/dot-health` | 26 scanned / 16 context-bearing. **alive:** `breadth`\*, `fii`, `vix`. **dead:** `iv_rank`, `dow`, `oi_spurt_price`, `futures_oi`\*, `underlying_oi`\*. (\* = required) |

Both GREEN ⇒ per the task contract, only checks 1, 2 and 5 were run in depth; §6 was added because
the dot set moved against the ledger in **both** directions.

### 4.1 `fii` came back ALIVE — the 07-27 post-close fix landing

The 07-24/07-27 ledger carries `fiiLongPct` NULL 100%, dead-data since 07-02. Today it reads
**alive** on all 16 context-bearing rows. This is [#1050](https://github.com/prashantm912/artha-yantra-2/pull/1050)
(FII EOD read repair, deployed 17:50–18:01 IST on 07-27) working on its first live session. **Update
the dead-dot ledger** — the standing dead trio is now a dead pair (`ivRank`, `dowUp`).

### 4.2 `oi_spurt_price` read dead — by design, see §6

07-27 had it alive at 9.9% support after #991. Today dead. This is *not* a regression of #991 — see
§6.1. It is the expiry-day suppression, and the canary's failure to *label* it as such is §6.2.

## 5 Check 5 — EXT-02 Upstox rate budget

`docker logs ay-market-data-service --since 2026-07-28T03:40:00Z` → **85 lines, zero WARN/ERROR**,
zero matches for `budget` / `unpriced` / `rate-limit` / `UDAPI` / `Too Many`. Log stream verified
live, so the empty match set is a real absence, not a dead pipe.

No rate-starvation of the armed F9 margin path. The open−30min batch-lane pause is holding.

## 6 NEW — monthly-expiry OI suppression, and a canary that mislabelled it

### 6.1 Every OI dot is inert today, by design (promoted to README §3.19)

`MarketOiClient.oi()` (`MarketOiClient.java:287-295`) tests
`ScalperCalendars.forUnderlying(underlying).isMonthlyIndexExpiryDay(tradeDate)` and, on a hit,
**skips the entire OI block**, returning an inert record:

```java
new Oi(OiQuadrant.NEUTRAL, OiQuadrant.NEUTRAL, null, null, futuresBasis, null, null, null,
       false, false, null, null, null);
```

Against the field order at `ScalperGateContext.java:56-72` that NEUTRALs both quadrants and nulls
the sentiment, trending and spurt magnitudes, **keeping only `futuresBasis`** (price-derived).
Rationale in the javadoc: the expiring series' writers are unwinding, so chain OI is corrupted.

Measured today, and the numbers match the code exactly:

| probe | 2026-07-28 | 2026-07-27 | 2026-07-24 |
|---|---|---|---|
| `spurtPricePct` NULL | **26/26** | 0/909 | 0/1100 |
| `spurtOiPct` NULL | **26/26** | 0/909 | 0/1100 |
| `futuresQuadrant` NEUTRAL | **16/16** | 0 | 0 |
| `underlyingQuadrant` NEUTRAL | **16/16** | 0 | 0 |
| `futuresBasis` live | **16/16** | — | — |

**`futuresBasis` staying live while the quadrants go NEUTRAL is the discriminator** between S24
suppression and a genuine OI outage. Second discriminator: `marketdata.futures_oi_snapshots` kept
full cadence — **2,760 snapshots / 40 distinct minutes by 09:55**, tracking minutes-since-open
(07-27: 25,668 / 372 over the full session). Capture is healthy; the gate is choosing not to read it.

⚠️ This means README §3.12 ("a high NEUTRAL share is a defect signal, never a flat-market artifact")
has a **second** exemption beyond a real outage: a monthly index expiry. 16/16 NEUTRAL today is not
the 2026-07-20 defect.

Dot support rates, all 16 context-bearing rows — the whole OI bloc is zero:

| dot | supports | dot | supports |
|---|---|---|---|
| `supertrend` | 16/16 | `futures_oi` | **0/16** |
| `vwma` | 16/16 | `underlying_oi` | **0/16** |
| `basis` | 14/16 | `oi_spurt` | **0/16** |
| `vix` | 12/16 | `drastic_oi` | **0/16** |
| `psar` | 4/16 | `sentiment` | **0/16** |
| `rsi` | 4/16 | `sentiment_slope` | **0/16** |
| `vwap` | 2/16 | `trending_cross` | **0/16** |
| `breadth` | 0/16 | `iv_rank` / `iv_pair` | 0/16 |

`basis` at 14/16 is the positive control — the one OI-adjacent input S24 deliberately keeps.

**Consequence for tuning: composite max was 0.3457 against a 0.600 threshold.** Per README §3.12,
NEUTRAL dots are added with `absent=false`, so they stay in the denominator and score zero — they
actively drag the composite rather than being withheld. **No entry-gate calibration conclusion may
be drawn from an expiry session**, and any rollup must exclude it or flag it as a REGIME row.

### 6.2 The canary mislabelled it — fixed and merged as #1073

`GET /api/v1/signal-rejections/dot-health` gave `futures_oi` / `underlying_oi` the correct
by-design detail while `oi_spurt_price` — fed by the *same* skipped read — returned
`"alive":false,"detail":"input dead across 16 context-bearing rejections"`. The exemption set at
`DotHealthCanary.java:105` held only the two quadrant dots.

Chasing that surfaced a second, worse and **pre-existing** defect, found by cross-vendor review
(gpt-5.6-sol) rather than by the tests: the exemption keyed on `nse.isMonthlyIndexExpiryDay() ||
bse.isMonthlyIndexExpiryDay()`, but the suppression itself keys on **the row's own OI root**
(`ScalperCalendars.forUnderlying` — BSE Thursday monthly for SENSEX, NSE monthly otherwise). Today
is precisely the asymmetric case: **NSE is expiring, BSE is not.** A genuine SENSEX-rooted OI outage
today would have been labelled by-design and would never have paged — and that applied to
`futures_oi` / `underlying_oi`, **both in the default `required-dots`**, so the silence was
reachable this session.

It did not manifest today only because every live row is NIFTY-rooted: `/context/underlying` reads
`NIFTY 50` on **1,069 of 1,069** context-bearing rejections across 07-25…07-28. The
`sensex-niftyoi` variants read **NIFTY** OI by design (the niftyoi-vs-sensexoi A/B), so the slug
name does not tell you the root — the field does.

Both fixed in [#1073](https://github.com/prashantm912/artha-yantra-2/pull/1073) (merged `edd2a2b1`):
`oi_spurt_price` added to the set, suppression now stands a probe down only when **every** sampled
row is from a suppressed root, and `sweep()` reads the endpoint's own `required` decision instead of
recomputing the expiry test. Detail wording is now `inert by design` (the quadrants degrade to
NEUTRAL, the spurt magnitudes to NULL). `DotHealthCanaryTest` 9 → 12, each new behaviour verified
RED before its fix. **Deploy deferred to after 15:30 IST** — diagnostic labelling only, no reason to
recreate the container mid-session.

**Deployed 16:43:29 IST and verified PASS**: `oi_spurt_price` now reads
`inert by design — monthly index-expiry day, OI reads S24-suppressed (not an outage)` on the live
endpoint, with `breadth`/`fii`/`vix` alive so the window was context-bearing (not the T17
all-`UNINFORMATIVE` tail). The EOD run captured the pre-fix reading at 16:07 on the same 40-row window
first, so the before/after pair is directly comparable — both in `2026-07-28-session-findings.md` §6.3
and §6.3.1. Half (b), the per-root keying, executed but is **not discriminated** by an NSE-only expiry.

## 7 Data-integrity probes

| probe | result |
|---|---|
| §3.15 misaligned (phantom) 1m candles since 09:15 | **0 rows**. No feed outage, so no phantom-bar inflation of the `volume-floor` operand. |
| §3.12 OI quadrant liveness | 16/16 NEUTRAL — **by design today**, see §6.1. Not the 07-20 defect. |
| Capture freshness | `NIFTY26AUGFUT` **38** 1m bars since 09:15, max bucket **09:53 IST** at wall-clock 09:54:19 (= now−1m). Full coverage, no interior gap. |
| Futures OI capture cadence | 2,760 snapshots / **40 distinct minutes**, last 09:55 — tracking minutes-since-open. |

## 8 Honesty notes / gaps in this run

- **The signal contract was assumed, not derived (§3.18).** Queries used `NIFTY26AUGFUT` on the
  strength of the 07-27 findings file recording the roll, and it returned a bar count consistent with
  minutes-since-open. That is *consistent with* AUGFUT, not a §3.18 derivation from
  `context.chart.close`. No volume-percentile or floor-threshold claim is made in this file, so
  nothing here depends on it — but the EOD run should derive it properly before any ground-truth work.
- **No counterfactual (§4.2) was run.** Zero rejections passed composite, so the would-have-fired set
  is empty. Nothing to resolve.
- The two engine-counter samples were **~2 min apart, not the ≥3 min** the task specifies. Σ advanced
  59 → 72 across them and both gauges read fresh, so liveness is settled positively; the short spacing
  would only matter had the counter read flat.

## 9 Carry into the evening `post` run

1. **Update the dead-dot ledger: `fii` is ALIVE** (§4.1) — the standing dead set is now
   `ivRank` + `dowUp`, not the trio. Confirm it held all session.
2. **Mark the whole session REGIME, not STRUCTURAL, for anything OI- or composite-related** (§6.1).
   Do not let an expiry session enter the rollup as evidence for or against any entry-gate tune.
3. Re-check `oi_spurt` support at EOD **against 07-27's 9.9%**, and only after the next
   non-expiry session — today says nothing about whether #991's floors still hold.
4. Re-run the §3.15 phantom-candle probe at EOD — zero at 09:57 only proves no outage yet.
5. Derive the signal contract properly per §3.18 before any ground-truth query (§8).
6. Confirm the post-close deploy of #1073 ran and that `oi_spurt_price` reports `inert by design`
   (scheduled task `deploy-dot-canary-s24-fix-post-close`, 16:00 IST). **Today is the only day this
   is directly observable until the next monthly expiry** — if the post-14:45 context-less tail makes
   every dot read `UNINFORMATIVE`, record it as UNOBSERVABLE rather than as a pass.
   ✅ **DISCHARGED — PASS.** Deployed 16:43:29 IST, label observed live at 16:44 on a context-bearing
   window (§6.2 above, `2026-07-28-session-findings.md` §6.3.1). The tail did *not* degenerate.
7. Re-check the host-clock guard at EOD — <1 s this run. B8 stays a free-running-CMOS watch item.
