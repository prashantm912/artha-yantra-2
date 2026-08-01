# R1 wing-skew — ground truth before any code (G13 successor)

**Written 2026-08-01. MEASUREMENT ONLY — no production code changed, nothing armed, no dot redefined.**

> **§12 addendum (same day)** measures the §9 change/deviation operand (**R2**) that this document
> flagged as unmeasured. It is a **second DO-NOT-BUILD**, and it also **red-proofs the kill criteria
> used here** — a pure random walk passes them. Read §12 before proposing any successor operand.

---

## Verdict

**DO NOT BUILD.** R1 wing skew is not pinned — its median per-bar magnitude is **15.0x** the current
operand's at the same bar (NIFTY 50: medians 0.00538 vs 0.00033) — but it fails the pre-committed kill
criterion on constancy: **on non-expiry sessions the operand is 95.7% single-signed (NIFTY 50) and
never changes sign at all on 15 of 19 sessions.** At every candidate threshold the CE side supports
≤3.1% of bars while the PE side supports 55–94%. That is a per-session *bias*, not a per-bar
discriminator — the `breadth` failure mode named in ledger row G16, which is exactly what this step
existed to catch.

Two secondary criteria also fail, independently:

- **`max < ~3x p50`**: NIFTY 50 non-expiry **max/p50(|R1|) = 3.05x** — at the kill line.
- **`p90 < 0.005`**: SENSEX non-expiry **p90(|R1|) = 0.00488** — under the kill line.

Widening the wing does not rescue it and makes it worse: at N=5 the NIFTY 50 operand is **100.0%
negative with ZERO sign flips across 9 sessions**, and max/p50 falls to 2.98x (§7).

**The G13 diagnosis is confirmed and the redefinition works as designed — the parity pin is genuinely
broken. The resulting quantity is simply the wrong kind of thing:** index wing skew is a persistent
market constant (puts structurally richer than calls), so R1 measures a *level* that barely moves
within a session, not a *per-bar directional edge*.

---

## 1. What was measured, and the validation that it is the right thing

**R1 operand** = `mean(CE iv over the 3 strikes above ATM)` − `mean(PE iv over the 3 strikes below
ATM)`, per snapshot bar, near expiry, ATM = strike nearest `spot_price`. Different strikes per side,
so put-call parity does not pin them. Row is **null** unless all 3 strikes on *both* wings carry a
non-null iv (honesty rule; null rate reported in §3).

**Control** = the current `iv_pair` operand, recomputed on the same bars: `ceIvAvg6 − peIvAvg6` over
the *same* 6 strikes (3 below + 3 above ATM, ATM excluded) — the exact shape of
`MarketOiClient.deriveIvPair`, `MarketOiClient.java:863-912`.

**Validation.** At `2026-07-30 11:00 IST`, NIFTY 50, expiry 2026-08-04, spot 24256.90, ATM 24250 —
hand-computed from the raw chain and matched to the query output digit-for-digit:

| quantity | strikes | hand | query |
|---|---|---|---|
| ceWingIvAvg3 | 24300 / 24350 / 24400 CE | 0.0946063 | 0.0946063 |
| peWingIvAvg3 | 24200 / 24150 / 24100 PE | 0.0996530 | 0.0996530 |
| **R1** | | **−0.0050467** | **−0.0050467** |
| current (`ce6−pe6`) | the 6 as one set | +0.0008667 | +0.0008667 |

At that one bar R1 is **5.8x** the current operand. The control's magnitude also reproduces the G13
finding (p50 0.0001 / max 0.0007 order) on an independently-built sample, which is why the extraction
is trusted.

## 2. Sample — smaller than the calendar suggests

Range swept: every weekday `2026-06-15 → 2026-07-31`, 09:15–15:30 IST, `NIFTY 50` and `SENSEX`,
near expiry only, non-quarantined.

**Days with NO usable data (computed, independently confirmed):**

| day | what is actually there |
|---|---|
| 06-17, 06-18, 06-25, **06-26**, 07-08, 07-09, 07-13 | zero snapshots for both underlyings |
| 06-19 | NIFTY 50: 16,007 rows, **0 non-null iv**; SENSEX: zero rows |
| 06-22 | NIFTY 50 11,958 + SENSEX 18,317 rows, **0 non-null iv** on both |

⚠️ **06-26 was not on the pre-briefed gap list** — the briefed set was 8 days, the real set is 9.

**Usable sessions (≥1 non-null operand):**

| underlying | usable | non-expiry | weekly expiry | monthly expiry |
|---|---|---|---|---|
| NIFTY 50 | 26 | **19** | 5 | 2 (06-30, 07-28) |
| SENSEX | 26 | **22** | 3 | 1 (07-30) |

Capture cadence is ~2 min, so a full session is ~187 bars, not 375.

⚠️ **Percentile precision caveat, stated up front.** The bars are strongly autocorrelated
(lag-1 = 0.78 NIFTY / 0.73 SENSEX at 2-min cadence, §5), so the *effective* sample size for the
pooled percentiles is far closer to the session count (19 / 22) than to the bar count (3,116 /
3,625). **Read the p10/p50/p90 figures as indicative to ~1 significant figure, not as precise.**
The verdict does not rest on them — it rests on the session-level sign structure in §4, which is
counted per session and needs no percentile precision.

## 3. Null / incomplete-wing rate (computed)

| underlying | scope | bars | with operand | **null** |
|---|---|---|---|---|
| NIFTY 50 | all | 4,551 | 4,397 | **3.38%** |
| NIFTY 50 | non-expiry | 3,266 | 3,116 | **4.59%** |
| SENSEX | all | 4,446 | 4,346 | **2.25%** |
| SENSEX | non-expiry | 3,722 | 3,625 | **2.61%** |

This is the *armed denominator*: even on a BUILD the dot would be absent on ~3–5% of bars. Low
enough not to be a blocker on its own.

## 4. Distribution — the deciding table

### 4.1 R1, signed and absolute

| underlying / scope | n | p10 | p50 | p90 | min | max | **neg%** |
|---|---|---|---|---|---|---|---|
| NIFTY 50 / non-expiry | 3116 | −0.00795 | −0.00535 | −0.00185 | −0.01644 | +0.00710 | **95.7%** |
| NIFTY 50 / weekly expiry | 946 | −0.02845 | −0.00324 | +0.02703 | −0.47729 | +0.13366 | 57.4% |
| NIFTY 50 / monthly expiry | 335 | −0.02316 | +0.00186 | +0.02152 | −0.10871 | +0.33662 | 46.0% |
| SENSEX / non-expiry | 3625 | −0.00487 | −0.00263 | −0.00028 | −0.01638 | +0.00745 | **91.6%** |
| SENSEX / weekly expiry | 535 | −0.01960 | −0.00575 | +0.00647 | −0.19196 | +0.11178 | 70.1% |
| SENSEX / monthly expiry | 186 | −0.01422 | −0.00592 | +0.00596 | −0.08278 | +0.16870 | 81.7% |

`|R1|`, and the kill criterion applied:

| underlying / scope | p10 | p50 | **p70** | **p75** | **p85** | p90 | max | **max/p50** |
|---|---|---|---|---|---|---|---|---|
| NIFTY 50 / non-expiry | 0.00209 | 0.00538 | 0.00633 | 0.00669 | 0.00740 | 0.00795 | 0.01644 | **3.05x** ❌ |
| SENSEX / non-expiry | 0.00089 | 0.00267 | 0.00349 | 0.00380 | 0.00450 | **0.00488** ❌ | 0.01638 | 6.13x |
| NIFTY 50 / weekly expiry | 0.00228 | 0.01273 | 0.02184 | 0.02484 | 0.03299 | 0.03897 | 0.47729 | 37.50x |
| SENSEX / weekly expiry | 0.00135 | 0.00749 | 0.01110 | 0.01310 | 0.01851 | 0.02117 | 0.19196 | 25.64x |

⚠️ **Do not read the expiry rows as life.** The 8 largest |R1| values in the whole sample are all in
the last 10 minutes of an expiry session (07-07 15:23/15:25/15:27, 06-30 15:22/15:24/15:26/15:29,
06-23 15:25 IST). A 48-vol-point wing spread is near-zero-time-value IV degeneracy, not a tradeable
skew reading. The `ALL`-scope max/p50 of 81.88x is that artifact and must not be used to argue BUILD.
Monthly-expiry sessions carry the S24 OI-block suppression on top (06-30, 07-28 NSE; 07-30 BSE).

### 4.2 Control — the current operand on the identical bars

| underlying / non-expiry | n | p50 (signed) | p50 \|x\| | p90 \|x\| | max \|x\| |
|---|---|---|---|---|---|
| NIFTY 50 | 3116 | −0.00013 | 0.00033 | 0.00108 | 0.00578 |
| SENSEX | 3625 | −0.00007 | 0.00023 | 0.00067 | 0.01214 |

Paired, same bar, non-expiry: **the median per-bar ratio `|R1| / |current|` is 15.0x (NIFTY 50) and
11.8x (SENSEX)** (ratio of the medians: 16.3x / 11.6x).

**This is the one thing R1 succeeds at.** Against `ivPairMinGap = 0.02` the current operand's NIFTY
non-expiry p90 sits **18.5x** below the threshold and even its max sits 3.5x below; R1's p90 sits
**2.5x** below and its max is within 1.2x of it. Parity is genuinely broken — R1 is the first version
of this operand whose distribution reaches the same order of magnitude as a plausible threshold. That
is necessary and, per §5, nowhere near sufficient.

## 5. Within-session behaviour — the kill

### 5.1 Sign flips per session

| underlying | non-expiry sessions | **sessions with ZERO sign flips** | total flips |
|---|---|---|---|
| NIFTY 50 | 19 | **15 (79%)** | 18 |
| SENSEX | 22 | **13 (59%)** | 114 |

Per-session detail, NIFTY 50 non-expiry (`pos%` = share of bars with the call wing richer):

| session | n | min | p50 | max | range | sd | flips | pos% |
|---|---|---|---|---|---|---|---|---|
| 2026-06-15 | 98 | −0.00234 | +0.00365 | +0.00682 | 0.00916 | 0.00196 | 5 | 93.9% |
| 2026-06-24 | 147 | −0.00597 | −0.00389 | −0.00093 | 0.00503 | 0.00102 | **0** | 0.0% |
| 2026-06-29 | 49 | −0.00609 | −0.00260 | +0.00710 | 0.01319 | 0.00261 | 5 | 20.4% |
| 2026-07-01 | 68 | −0.00547 | −0.00318 | −0.00246 | 0.00301 | 0.00071 | **0** | 0.0% |
| 2026-07-02 | 167 | −0.00857 | −0.00562 | −0.00375 | 0.00482 | 0.00119 | **0** | 0.0% |
| 2026-07-03 | 187 | −0.00847 | −0.00632 | −0.00436 | 0.00411 | 0.00069 | **0** | 0.0% |
| 2026-07-06 | 187 | −0.01514 | −0.01062 | −0.00247 | 0.01267 | 0.00211 | **0** | 0.0% |
| 2026-07-10 | 187 | −0.00810 | −0.00519 | −0.00336 | 0.00474 | 0.00085 | **0** | 0.0% |
| 2026-07-15 | 174 | −0.00602 | −0.00425 | +0.00018 | 0.00619 | 0.00108 | 2 | 0.6% |
| 2026-07-16 | 187 | −0.00805 | −0.00593 | −0.00140 | 0.00665 | 0.00180 | **0** | 0.0% |
| 2026-07-17 | 185 | −0.01035 | −0.00725 | −0.00347 | 0.00688 | 0.00087 | **0** | 0.0% |
| 2026-07-20 | 183 | −0.01644 | −0.00380 | −0.00041 | 0.01602 | 0.00326 | **0** | 0.0% |
| 2026-07-22 | 185 | −0.00427 | −0.00229 | −0.00059 | 0.00368 | 0.00044 | **0** | 0.0% |
| 2026-07-23 | 185 | −0.00348 | −0.00129 | +0.00175 | 0.00523 | 0.00103 | 6 | 16.8% |
| 2026-07-24 | 187 | −0.00566 | −0.00402 | −0.00089 | 0.00478 | 0.00091 | **0** | 0.0% |
| 2026-07-27 | 183 | −0.01260 | −0.00738 | −0.00020 | 0.01240 | 0.00264 | **0** | 0.0% |
| 2026-07-29 | 187 | −0.00703 | −0.00576 | −0.00498 | 0.00205 | 0.00033 | **0** | 0.0% |
| 2026-07-30 | 187 | −0.00801 | −0.00570 | −0.00380 | 0.00422 | 0.00064 | **0** | 0.0% |
| 2026-07-31 | 183 | −0.00889 | −0.00714 | −0.00565 | 0.00324 | 0.00070 | **0** | 0.0% |

Only 2026-06-15 (the first capture day, partial) is call-wing-rich. Every full session since is a
one-signed put-skew reading for its entire duration.

### 5.2 What movement there is, is slow drift

| underlying (non-expiry) | median sd(bar-to-bar change) / sd(level) | median lag-1 autocorrelation |
|---|---|---|
| NIFTY 50 | 0.63 | **0.783** |
| SENSEX | 0.70 | **0.731** |

At a 2-minute cadence the series is a smooth ramp. On the 30-minute scalping horizon the operand is
effectively whatever it already was.

### 5.3 Armed-rate simulation — the dot is one-sided

Non-expiry bars only. The dot supports CE when `R1 ≥ G`, PE when `−R1 ≥ G`:

| G | NIFTY CE | NIFTY PE | SENSEX CE | SENSEX PE |
|---|---|---|---|---|
| 0.0010 | 3.1% | 93.9% | 4.4% | 84.3% |
| 0.0020 | 2.6% | 88.4% | 1.8% | 70.0% |
| 0.0030 | 2.2% | 77.0% | 0.7% | 40.8% |
| 0.0040 | 1.3% | 67.1% | 0.2% | 21.7% |
| 0.0050 | 0.6% | 54.8% | 0.1% | 8.2% |
| 0.0075 | **0.0%** | 14.0% | **0.0%** | 0.8% |
| 0.0100 | **0.0%** | 5.0% | 0.0% | 0.1% |

**There is no threshold at which both sides are alive.** Every gap that leaves the PE side selective
leaves the CE side at literally zero. R1 would not fix the dead dot — it would move the deadness onto
every CE leg and hand the PE legs 0.8 weight for a market constant, systematically biasing the fleet's
composite toward PE entries. Against the standing prior that **every measured loosening of the scalper
entry gate has lost money (T1/T7/G13/G10)**, a one-sided free 0.8 is a worse outcome than the current
symmetric zero.

### 5.4 At a p70–p85 gap the dot is a DAY flag

| underlying | G | sessions | ON all day (>95%) | OFF all day (<5%) | **pure day-flag** | median ON/OFF transitions |
|---|---|---|---|---|---|---|
| NIFTY 50 | 0.0040 | 19 | 6 | 3 | 47% | 4 |
| NIFTY 50 | 0.0067 (p75) | 19 | 0 | 10 | **53%** | 5 |
| SENSEX | 0.0030 (p70) | 22 | 3 | 8 | 50% | 6 |
| SENSEX | 0.0038 (p75) | 22 | 1 | 13 | **64%** | 4 |

Half to two-thirds of sessions resolve to a single all-day state, with 4–6 transitions across ~185
bars on the rest. That is a day-level regime label wearing a per-bar dot's clothing.

## 6. Recommended gap — reported as asked, but NOT recommended

Had the verdict been BUILD, `ivPairSkewMinGap` at p70–p85 of |R1| on non-expiry bars would be:

| underlying | p70 | **p75** | p85 |
|---|---|---|---|
| NIFTY 50 | 0.00633 | **0.00669** | 0.00740 |
| SENSEX | 0.00349 | **0.00380** | 0.00450 |

⚠️ **A third independent problem this exposes: the two roots need different gaps** (0.0067 vs 0.0038,
1.8x apart), and the scalper fleet spans both NSE and BSE roots. A single fleet-wide
`ivPairSkewMinGap` is mis-set for one root by construction; per-root gaps would be new config surface
for a dot that §5 already shows cannot discriminate.

## 7. Robustness — a wider wing makes it worse, not better

The obvious rescue ("N=3 is too close to ATM, widen it") was tested at **N=5** over 9 dense sessions
(2026-07-15 → 07-30), same pipeline:

| underlying | N | n | sessions | p50 | \|x\| p50 | \|x\| p90 | max | **max/p50** | **neg%** | **sign flips** |
|---|---|---|---|---|---|---|---|---|---|---|
| NIFTY 50 | 3 | 1658 | 9 | −0.00538 | 0.00538 | 0.00770 | 0.01644 | 3.06x | 99.9% | 2 |
| NIFTY 50 | **5** | 1658 | 9 | −0.00812 | 0.00812 | 0.01133 | 0.02421 | **2.98x** | **100.0%** | **0** |
| SENSEX | 3 | 1273 | 7 | −0.00331 | 0.00334 | 0.00514 | 0.01638 | 4.91x | 94.8% | 54 |
| SENSEX | **5** | 1273 | 7 | −0.00503 | 0.00506 | 0.00751 | 0.01871 | **3.70x** | 90.3% | 53 |

Widening raises the magnitude ~1.5x but pushes **every** kill metric the wrong way: max/p50 falls,
neg% rises to 100.0%, and NIFTY 50 sign flips go to **zero across 9 sessions**. This is structural,
not a tuning accident — a wider wing samples more of the same monotone skew slope, so it reads the
persistent level more purely. **No wing width fixes this.**

## 8. Why — the structural reason, so this is not re-proposed

Equity-index IV surfaces carry persistent negative skew: OTM puts are structurally richer than OTM
calls because index hedging demand is one-directional. The raw near-ATM surface from the validation
bar shows it plainly (NIFTY 50, 2026-07-30 11:00 IST, spot 24256.90):

| strike | 24100 | 24150 | 24200 | **24250 (ATM)** | 24300 | 24350 | 24400 |
|---|---|---|---|---|---|---|---|
| CE iv | 0.1021 | 0.1009 | 0.0988 | 0.0970 | 0.0963 | 0.0944 | 0.0931 |
| PE iv | 0.1010 | 0.0996 | 0.0983 | 0.0970 | 0.0953 | 0.0941 | 0.0920 |

Two facts fall straight out:

1. **Vertically** (CE vs PE at one strike) the columns agree to ~0.001 — put-call parity, the G13
   root cause, reconfirmed on raw data.
2. **Horizontally** the curve is monotone decreasing in strike. So R1 is essentially
   `slope × strike-distance` — it measures the *local skew slope*, a slowly-varying property of the
   surface, whose sign is fixed by market structure rather than by today's order flow.

G13 asked the operand to stop being pinned. R1 does that. But it replaced a quantity with no variance
with a quantity whose variance is real and whose **sign is a market constant** — and a Connecting-Dots
dot is a *directional* per-bar boolean. The measurement says the surface's skew level is not the
carrier of a per-bar directional edge.

## 9. If the item is kept alive — what would have to change (NOT a recommendation)

Recorded so the next reader does not re-derive it. **None of this is proposed, none is scoped, and
each would need its own ground-truth pass before code:**

- **A change/deviation operand, not a level.** `R1(t) − baseline(t)`, baseline being a session-open or
  rolling-median skew. §5.2's lag-1 of 0.78 means most of the level is stale by construction; the
  residual is what could conceivably carry information. Whether that residual discriminates is
  **unmeasured** and would need this same pass re-run.
- **Retire rather than replace.** G13 already established `iv_pair` is pure drag: removing it from the
  denominator raised composite pass rates on all 10 sessions and never lowered one. Retirement needs
  no new operand and no new ground truth — but it is a loosening, and §5.3's prior applies.

Both are owner decisions. Neither is unblocked by this document.

## 10. Re-runnable SQL

Per-day, per-underlying wing extraction. `:d`, `:lo`, `:hi` are psql variables; run one day at a time
(a whole-range scan of this hypertable is exactly the shape that killed the live backend twice).

```sql
-- docker exec -i ay-timescaledb psql -U artha -d artha --csv \
--   -v d=2026-07-30 -v lo='2026-07-30T09:15:00+05:30' -v hi='2026-07-30T15:31:00+05:30' -f wing.sql
SET statement_timeout='120s';
WITH b AS (
  SELECT timestamptz :'lo' AS lo, timestamptz :'hi' AS hi, date :'d' AS d
),
near AS (                                    -- near expiry per underlying for this day
  SELECT s.underlying, min(s.expiry) AS expiry
  FROM marketdata.options_chain_snapshots s, b
  WHERE s.ts >= b.lo AND s.ts < b.hi
    AND s.underlying IN ('NIFTY 50','SENSEX')
    AND s.expiry >= b.d
  GROUP BY 1
),
raw AS (                                     -- near-ATM band only; the row's own spot bounds it
  SELECT s.ts, s.underlying, s.expiry, s.strike, s.option_type, s.iv, s.spot_price
  FROM marketdata.options_chain_snapshots s
  JOIN near n ON n.underlying = s.underlying AND n.expiry = s.expiry
  CROSS JOIN b
  WHERE s.ts >= b.lo AND s.ts < b.hi
    AND s.underlying IN ('NIFTY 50','SENSEX')
    AND COALESCE(s.quarantined, false) = false
    AND s.spot_price IS NOT NULL
    AND s.strike BETWEEN s.spot_price * 0.96 AND s.spot_price * 1.04
),
grid AS (                                    -- one row per strike, CE and PE side by side
  SELECT ts, underlying, expiry, strike,
         max(spot_price) AS spot,
         max(iv) FILTER (WHERE option_type='CE') AS ce_iv,
         max(iv) FILTER (WHERE option_type='PE') AS pe_iv
  FROM raw GROUP BY 1,2,3,4
),
atm AS (                                     -- strike nearest spot (row_number, never DISTINCT ON+LIMIT)
  SELECT ts, underlying, expiry, strike AS atm_strike, spot FROM (
    SELECT g.*, row_number() OVER (PARTITION BY ts, underlying
             ORDER BY abs(strike - spot) ASC, strike ASC) AS rn
    FROM grid g
  ) z WHERE rn = 1
),
w AS (                                       -- rank outward from ATM on each side
  SELECT g.ts, g.underlying, g.strike, g.ce_iv, g.pe_iv,
         (g.strike > a.atm_strike) AS above,
         row_number() OVER (
           PARTITION BY g.ts, g.underlying, (g.strike > a.atm_strike)
           ORDER BY abs(g.strike - a.atm_strike) ASC) AS rn
  FROM grid g
  JOIN atm a ON a.ts = g.ts AND a.underlying = g.underlying
  WHERE g.strike <> a.atm_strike
),
w3 AS (SELECT * FROM w WHERE rn <= 3)        -- N=3; set rn <= 5 for the §7 robustness run
SELECT a.ts, a.underlying, a.expiry, a.atm_strike, a.spot,
       count(w3.ce_iv) FILTER (WHERE w3.above)     AS n_ce_above,  -- must be 3, else operand is NULL
       count(w3.pe_iv) FILTER (WHERE NOT w3.above) AS n_pe_below,  -- must be 3, else operand is NULL
       count(w3.ce_iv)                             AS n_ce6,       -- control: must be 6
       count(w3.pe_iv)                             AS n_pe6,
       avg(w3.ce_iv) FILTER (WHERE w3.above)       AS ce_wing3,    -- R1 call wing
       avg(w3.pe_iv) FILTER (WHERE NOT w3.above)   AS pe_wing3,    -- R1 put wing
       avg(w3.ce_iv)                               AS ce_avg6,     -- control (current iv_pair)
       avg(w3.pe_iv)                               AS pe_avg6
FROM atm a
LEFT JOIN w3 ON w3.ts = a.ts AND w3.underlying = a.underlying
GROUP BY 1,2,3,4,5
ORDER BY a.ts, a.underlying;
```

`R1 = ce_wing3 − pe_wing3` **iff** `n_ce_above = 3 AND n_pe_below = 3`, else NULL.
`current = ce_avg6 − pe_avg6` **iff** `n_ce6 = 6 AND n_pe6 = 6`, else NULL.
Expiry-day = `expiry = (ts AT TIME ZONE 'Asia/Kolkata')::date`; monthly = no same-weekday expiry
later in that month (NSE last-Tue / BSE last-Thu). Data-gap inventory (§2):

```sql
SET statement_timeout='120s';
SELECT (ts AT TIME ZONE 'Asia/Kolkata')::date AS ist_day, underlying,
       count(*) AS rows, count(iv) AS iv_rows
FROM marketdata.options_chain_snapshots
WHERE ts >= timestamptz '2026-06-15T00:00:00+05:30'
  AND ts <  timestamptz '2026-06-27T00:00:00+05:30'   -- walk the range in ~10-day windows
  AND underlying IN ('NIFTY 50','SENSEX')
GROUP BY 1,2 ORDER BY 1,2;
```

All queries above are READ-ONLY. No writes, no DDL, no live-DB state was changed.

## 11. Claim labels

- **computed** this session against live `artha` `marketdata.options_chain_snapshots`,
  2026-06-15 → 2026-07-31: every figure in §2–§7 (8,997 extracted bar-rows over 34 weekday
  extractions; N=5 robustness over 9 sessions). Extraction SQL is §10 verbatim.
- **computed** and cross-checked by hand: the §1 validation bar (2026-07-30 11:00 IST) and the §8
  raw surface — both read directly off the snapshot rows, matched digit-for-digit to the query.
- **sourced**, read 2026-08-01: `MarketOiClient.deriveIvPair` at `MarketOiClient.java:863-912`
  (the 3-below + 3-above, ATM-excluded, 6-strike shape and its all-or-nothing null rule);
  `ConnectTheDotsScorer.ivPair` at `ConnectTheDotsScorer.java:317-324` (the directional
  `gap >= ivPairMinGap` test); `ScalperOiProps.java:21,56,125` (`ivPairMinGap`,
  `DEFAULT_IV_PAIR_MIN_GAP = 0.02`, documented at :52-55 as the 0..1 fraction scale — the E8/F3.3
  units refutation reconfirmed at the source).
- **sourced**, not re-derived here: G13's p50 0.0001 / max 0.0007 and its 10-session drop-`iv_pair`
  counterfactual (`docs/signal-analysis/2026-07-29-g13-iv-bloc-counterfactual.md`); the
  T1/T7/G13/G10 "every measured loosening lost money" prior; the G16 `breadth`
  per-session-constant precedent; the S24 monthly-expiry OI suppression.
- **assumed** (stated, not proven): that `min(expiry) >= session date` in the snapshot table is the
  same expiry the live `MarketOiClient` chain resolves. The live chain is fetched from the OI
  provider, not from this table, so the two were not byte-compared — see open doubts.

---

## Open doubts

1. **The live chain was not byte-compared to the snapshot table.** R1 was computed from
   `marketdata.options_chain_snapshots`; the live dot consumes `MarketOiClient`'s provider chain.
   The strike grid, ATM pick and near-expiry choice are reconstructed to match
   `deriveIvPair`'s documented shape and validated on one hand-checked bar, but if the provider chain
   ever ships a different strike window or a different expiry, the absolute percentiles shift. The
   *sign structure* — the thing the verdict rests on — is a property of the market's skew, not of the
   grid, so it would survive; the recommended-gap numbers in §6 would not.
2. **Effective sample is ~19–22 sessions, not 3,116 bars** (lag-1 autocorr 0.78). The percentiles are
   indicative to about one significant figure. Stated in §2; flagged again because a future reader
   quoting "p75 = 0.00669" as a precise number would be over-reading it.
3. **19 non-expiry NIFTY sessions is a narrow regime window.** All of 2026-06/07 sat in one broad
   volatility regime. A genuine volatility-regime shift (a crash, a vol spike) could in principle
   flip index skew positive for a stretch. I have no observation of that in this sample and cannot
   exclude it — but a dot that only comes alive in a regime absent from 7 weeks of capture is not a
   per-bar discriminator either, so I do not think this changes the verdict.
4. **The §9 change/deviation operand is genuinely unmeasured.** I asserted the level is stale
   (autocorr, computed) but did **not** measure whether the residual discriminates. If the owner
   wants that door left open, it needs its own ground-truth pass — do not read §9 as a soft BUILD.
5. **06-26 was missing from the briefed gap list**, which suggests the gap inventory was assembled
   from the iv-rollup rather than from raw snapshot counts. My §2 numbers come from raw counts. If any
   *other* consumer relies on that rollup-derived gap list, it may be under-counting outage days by
   at least one. I did not chase this — it is out of scope, but it is a loose thread.
6. **Quarantined rows were excluded** (`COALESCE(quarantined,false) = false`). Zero rows were
   quarantined in the spot-checked day, and I did not verify the count across the whole range, so the
   filter is untested rather than known-inert. It can only have removed data flagged bad, so it
   cannot manufacture the observed constancy.

---

# §12 ADDENDUM (2026-08-01) — R2, the residual operand from §9

**MEASUREMENT ONLY. Same read-only harness, same sample, same live `artha`. No code, nothing armed.**

## 12.0 Verdict

**DO NOT BUILD.** R2 **passes all three pre-committed kill criteria** — and that fact carries no
information, because **a pure random walk, detrended identically, passes them too** (§12.4 red-proof:
0/19 zero-flip sessions, max/p50 6.3x, CE/PE balance 0.85–0.98). The only test with discriminating
power is the forward-outcome test, and under a **block-preserving null** the directional edge is
**not separable from chance at the thresholds where the dot would actually arm**: p75 gives
**p = 0.056 (NIFTY 50)** and **p = 0.082 (SENSEX)**, one-sided, uncorrected.

Supporting, each independently sufficient to stop a build:

- **Session-level sign robustness fails on SENSEX: the CE−PE spread is positive on 8 of 14 sessions
  (57%)** — a coin flip — with a per-session range of [−10.3, +22.5] bps. NIFTY is 12 of 15 (80%),
  range [−17.0, +25.0]. This is the G10 lesson (a sign that flipped on 5 of 265 legs) repeating.
- **Effect size is immaterial through this dot.** At p75 the spread is **+4.3 NIFTY index points over
  30 minutes**, delivered through a dot worth 0.8 of an 18.80 denominator (**4.3% of the composite**),
  on a gate where G13 measured the composite as the binding rail on **0.9%** of blocks.
- **The warm-up costs the open.** A 15-bar trailing baseline nulls the operand for the first ~30
  minutes of every session — null rate rises from 3–5% (R1) to **11.95% / 11.02%** — and the open is
  prime scalping time.

Unlike R1, **R2 is not structurally dead** — it is *indistinguishable from chance* at armable
thresholds, which is a different and more dangerous failure: it looks alive on every distributional
check. Against the standing prior that every measured loosening of the scalper entry gate has lost
money (T1/T7/G13/G10), an operand that cannot be shown to beat a coin is a loosening with no
demonstrated edge.

**I endorse the coordinator's pre-stated conclusion for G13:** `iv_pair` is unfixable as a per-bar
dot. It stays dead-but-symmetric, withholding support from both sides equally, which leans the gate
slightly stricter — the historically safe direction.

## 12.1 Windows — pre-declared before any result was inspected

Cadence is **2.0 min/bar** (computed: median inter-bar gap, 187 bars on 2026-07-30).

| id | definition | justification |
|---|---|---|
| **B1 `R2-TRAIL`** | `R1(t) − median(R1[t−15 … t−1])`, trailing, **excluding** the current bar | 15 bars = ~30 min = the **G11 `time_stop` horizon** the scalper actually trades. Chosen from the trade horizon, not from a result. Exclusive so a bar cannot contaminate its own baseline. |
| **B2 `R2-OPEN`** | `R1(t) − median(first 5 valid bars of the session)` | The session-open anchor named in the brief. |

Both are reported in full below. Neither was tuned; no other window was tried.

## 12.2 Distribution (computed)

`R2-TRAIL`:

| underlying / scope | n | p10 | p50 | p90 | max\|x\| | p50\|x\| | p90\|x\| | max/p50 | neg% |
|---|---|---|---|---|---|---|---|---|---|
| NIFTY 50 / non-expiry | 2831 | −0.00092 | +0.00002 | +0.00099 | 0.00762 | 0.00039 | 0.00145 | 19.38x | 48.1% |
| NIFTY 50 / expiry | 1176 | −0.01932 | +0.00052 | +0.02104 | 0.43377 | 0.00752 | 0.03220 | 57.70x | 48.6% |
| SENSEX / non-expiry | 3295 | −0.00071 | +0.00001 | +0.00075 | 0.00743 | 0.00032 | 0.00106 | 22.85x | 49.1% |
| SENSEX / expiry | 661 | −0.01252 | −0.00017 | +0.01476 | 0.18382 | 0.00495 | 0.02019 | 37.12x | 51.6% |

`R2-OPEN`:

| underlying / scope | n | p10 | p50 | p90 | max\|x\| | p50\|x\| | p90\|x\| | max/p50 | neg% |
|---|---|---|---|---|---|---|---|---|---|
| NIFTY 50 / non-expiry | 3021 | −0.00201 | +0.00009 | +0.00245 | 0.01155 | 0.00097 | 0.00312 | 11.88x | 46.4% |
| NIFTY 50 / expiry | 1246 | −0.02170 | +0.00241 | +0.03007 | 0.46156 | 0.01319 | 0.03799 | 34.99x | 45.1% |
| SENSEX / non-expiry | 3515 | −0.00140 | −0.00005 | +0.00215 | 0.00784 | 0.00079 | 0.00251 | 9.96x | 51.3% |
| SENSEX / expiry | 701 | −0.01587 | −0.00205 | +0.00949 | 0.18328 | 0.00542 | 0.02028 | 33.80x | 60.9% |

**Null rate incl. warm-up:** `R2-TRAIL` 11.95% (NIFTY) / 11.02% (SENSEX); `R2-OPEN` 6.24% / 5.17%.
Against R1's 3.38% / 2.25% — the trailing baseline costs ~8 points of coverage, all of it at the open.

## 12.3 The three pre-committed criteria — all PASS

**KILL#1, near-constant within a session — PASS.**

| operand | underlying | non-expiry sessions | zero-flip sessions | median flips/session | pos% (min / median / max) |
|---|---|---|---|---|---|
| R2-TRAIL | NIFTY 50 | 19 | **0** | 42.9 | 42 / 52 / 79 |
| R2-TRAIL | SENSEX | 22 | **0** | 45.6 | 33 / 52 / 79 |
| R2-OPEN | NIFTY 50 | 19 | **0** | 18.7 | 2 / 57 / 97 |
| R2-OPEN | SENSEX | 22 | 1 | 19.5 | 6 / 49 / 100 |

**KILL#2, a threshold where BOTH sides are alive — PASS**, and this is the criterion R1 died on.
`R2-TRAIL`, non-expiry (`balance` = min(CE,PE)/max(CE,PE)):

| G | NIFTY CE | NIFTY PE | balance | SENSEX CE | SENSEX PE | balance |
|---|---|---|---|---|---|---|
| p50 | 26.4% | 23.6% | 0.90 | 25.7% | 24.3% | 0.94 |
| p70 | 15.3% | 14.8% | **0.97** | 15.4% | 14.6% | **0.95** |
| p75 | 13.0% | 12.0% | 0.93 | 12.9% | 12.1% | 0.93 |
| p85 | 7.8% | 7.2% | 0.91 | 7.7% | 7.3% | 0.94 |
| p90 | 5.3% | 4.8% | 0.91 | 5.5% | 4.5% | 0.81 |

(`R2-OPEN` is weaker and degrades with threshold — NIFTY balance 0.73 → 0.42 from p50 to p90, SENSEX
0.97 → 0.20, i.e. one-sided by p90, because a fixed open anchor re-inherits the day's drift.)

**KILL#3, discrimination confined to expiry / final minutes — PASS.** `R2-TRAIL` non-expiry p50|x| is
0.00039 (NIFTY) / 0.00032 (SENSEX) against 0.00061 / 0.00063 for non-expiry bars after 15:00 — only
~1.6–2.0x, so the operand is not a closing-bell artifact. The five largest |R2| bars remain expiry-day
final-minutes (07-07 15:23/15:25/15:27, 06-30 15:24/15:26 and the SENSEX equivalents) — the same
near-zero-time-value degeneracy §4.1 flagged — but the non-expiry body stands on its own.

**Root threshold split — NOT disqualifying this time.** `R2-TRAIL` p75|x| is 0.00080 (NIFTY) vs
0.00063 (SENSEX) = **1.26x** (`R2-OPEN`: 1.35x), against R1's 1.8x. A single fleet-wide knob is far
more defensible for a residual than for a level. Recorded as asked; it is not what kills R2.

## 12.4 Red-proof — the criteria cannot discriminate a residual

A synthetic random walk sized to the observed session (187 bars, `sd(Δ) = 0.00048`, starting at the
observed −0.005 put-skew level), detrended with the identical 15-bar trailing median, over 19
synthetic sessions:

| criterion | random walk | verdict |
|---|---|---|
| zero-flip sessions | **0 / 19** | passes KILL#1 |
| median flips/session | 26.7 | passes KILL#1 |
| max/p50(\|x\|) | **6.3x** | passes (line is ~3x) |
| neg% | 47.0% | passes |
| CE/PE balance @ p75 / p90 | **0.85 / 0.98** | passes KILL#2 |

**A series containing zero information passes every pre-committed criterion.** This is mechanical:
subtracting a trailing median forces a zero-median, sign-symmetric, oscillating series regardless of
what the input is. The criteria were built to catch a *level* that does not move; they are **blind by
construction to a residual**. R2's PASS in §12.3 is therefore not evidence of anything.

This is the reusable lesson: **a distribution-shape gate is the right test for a level operand and
the wrong test for a deviation operand.** Any future residual/deviation proposal must be gated on a
forward-outcome test with a block-preserving null, not on percentiles and sign flips.

## 12.5 The test that does discriminate — forward spot at the scalper's horizon

The dot's claim is directional: *call wing bid up relative to its recent level → bullish → supports
CE*. If that is true, `R2 > 0` must precede spot **rising** over the next ~30 min. `spot_price` is
already in the §10 extraction, so this is exact and needs no pricing model. Non-expiry only, forward
window +15 bars, within-session.

`R2-TRAIL`, NIFTY 50 (n=2546; unconditional +0.56 bps, up% 51.1%):

| G | n CE | CE fwd mean | CE up% | n PE | PE fwd mean | PE up% | CE−PE |
|---|---|---|---|---|---|---|---|
| p50 | 671 | +1.53 bps | 56.9% | 602 | +0.77 bps | 49.5% | +0.77 |
| p75 | 320 | +2.54 bps | **61.9%** | 317 | +0.73 bps | 49.5% | +1.81 |
| p90 | 128 | +2.93 bps | 60.9% | 127 | −0.73 bps | 44.9% | +3.65 |

`R2-TRAIL`, SENSEX (n=2965; unconditional −0.08 bps, up% 50.2%):

| G | n CE | CE fwd mean | CE up% | n PE | PE fwd mean | PE up% | CE−PE |
|---|---|---|---|---|---|---|---|
| p50 | 768 | +0.77 bps | 52.5% | 715 | −1.14 bps | 46.0% | +1.91 |
| p75 | 380 | +0.59 bps | 52.6% | 362 | −1.02 bps | 46.1% | +1.60 |
| p90 | 150 | +2.22 bps | 56.7% | 147 | −2.48 bps | 45.6% | +4.70 |

The separation is **correctly signed and monotone in threshold on both roots** — the signature of a
real effect rather than noise. It looked, at this stage, like a BUILD. It is not, for two reasons.

### 12.5.1 Session-level sign robustness

| operand / root | sessions | CE−PE spread POSITIVE on | median | range |
|---|---|---|---|---|
| R2-TRAIL / NIFTY 50 @ p75 | 15 | **12 (80%)** | +3.22 bps | [−17.0, +25.0] |
| R2-TRAIL / SENSEX @ p75 | 14 | **8 (57%)** | +1.39 bps | [−10.3, +22.5] |

**SENSEX is a coin flip at the session level**, and both roots span zero by a wide margin. With an
effective sample of ~14–15 sessions, a pooled average is exactly the statistic the
`sampling-window-bugs-invisible-to-tests` topic warns about.

### 12.5.2 Permutation test — and why the first one was wrong

| root / G | naive shuffle (WRONG) | circular shift (CORRECT) |
|---|---|---|
| NIFTY p75 | z=+3.26, p=0.0000 | **z=+1.55, p=0.0560 — NOT separable** |
| NIFTY p90 | z=+3.40, p=0.0005 | z=+1.79, p=0.0395 |
| SENSEX p75 | z=+2.69, p=0.0035 | **z=+1.40, p=0.0820 — NOT separable** |
| SENSEX p90 | z=+3.91, p=0.0000 | z=+1.97, p=0.0245 |

⚠️ **I ran the wrong null first and it would have produced a BUILD.** A naive within-session shuffle
destroys R2's own autocorrelation (lag-1 **+0.611** NIFTY / **+0.543** SENSEX), so shuffled CE
selections are *scattered* while real ones arrive in contiguous **runs**. Overlapping 15-bar forward
windows inside a run carry far fewer independent observations than scattered ones, so the naive null
understates variability and inflates z. A **circular shift** of R2 within the session preserves its
autocorrelation and run structure exactly and destroys only the alignment with the forward return.
It roughly **doubles the null sd** (0.83→1.79, 1.32→2.50, 0.89→1.77, 1.51→2.92) and the significance
collapses.

At **p75 — the only band with enough observations to arm a dot on — neither root separates.** The two
p90 cells sit at p=0.0395 / 0.0245 one-sided, uncorrected, on a test chosen after seeing the data,
across 4 cells; Bonferroni gives 0.158 / 0.098 and **neither survives**. Those cells are also the
thinnest (128/127 and 150/147 observations, arriving in runs).

### 12.5.3 Effect size, if one ignored all of the above

| root | G | spread | in index points over ~30 min |
|---|---|---|---|
| NIFTY 50 | p75 | +1.78 bps | **+4.3 pts** |
| NIFTY 50 | p90 | +3.63 bps | +8.8 pts |
| SENSEX | p75 | +1.58 bps | +12.3 pts |
| SENSEX | p90 | +4.41 bps | +34.3 pts |

⚠️ **Do not read the points column as SENSEX being the better root** — the two roots are near-identical
in bps (1.78 vs 1.58); SENSEX's larger point counts are purely its 3.2x higher index level. bps is the
comparable unit.

Even taking +4.3 NIFTY points at face value: it arrives through a dot worth **0.8 / 18.80 = 4.3%** of
the composite, on a gate where the composite is the binding rail on **0.9%** of blocks (G13 §3,
sourced). The realistic delta on bars that actually fire is negligible.

## 12.6 Re-runnable derivation

**The SQL is unchanged — §10 verbatim.** R2 is a deterministic post-processing of that extraction; no
new query, no new DB access. Given the §10 CSV (one row per `ts` × underlying, ordered):

1. Keep rows where `n_ce_above = 3 AND n_pe_below = 3`; `R1 = ce_wing3 − pe_wing3`.
2. Partition by `(underlying, IST session date)`, order by `ts`, drop null-R1 bars.
3. `R2-TRAIL(i) = R1(i) − median(R1[i−15 … i−1])` for `i ≥ 15`, else null.
   `R2-OPEN(i)  = R1(i) − median(R1[0 … 4])` for `i ≥ 5`, else null.
4. Expiry labelling and the monthly rule are §10's.
5. Forward test: `fwd(i) = spot(i+15)/spot(i) − 1`, within session, `i+15 < n`.
6. Null: **circular shift** of the R2 array within each session (`x[k:] + x[:k]`, `k` uniform),
   forward returns held in place, 2000 draws. A plain shuffle is invalid — §12.5.2.

Session inventory, gap days and the `+05:30` bounding discipline are §2 and §10. Everything here is
read-only; no writes, no DDL, no live-DB state changed.

## 12.7 Claim labels

- **computed** this session from the §10 extraction (8,997 bar-rows, live `artha`, 2026-06-15 →
  07-31): every figure in §12.1–§12.5, including both permutation tests and the §12.4 random-walk
  red-proof (seed 20260801, 2000 draws each).
- **computed**: the 2.0 min/bar cadence (median inter-bar gap, 187 bars, 2026-07-30) that sizes the
  15-bar window.
- **sourced**, not re-derived: the 18.80 composite denominator and `W_IV = 0.8`, and the "composite
  binds on 0.9% of blocks / volume-floor on 88%" rail distribution — both from
  `docs/signal-analysis/2026-07-29-g13-iv-bloc-counterfactual.md` §1 and §3; the G11 30-minute
  `time_stop`; the T1/T7/G13/G10 loosening prior; the G10 "sign flipped on 5 of 265 legs" precedent.
- **assumed**: that forward *spot* direction is the right proxy for the dot's directional claim. A
  long option leg's P&L is not linear in spot (theta, vega, the premium exits), so this is a
  necessary-not-sufficient proxy — see open doubts.

## 12.8 Open doubts

1. **Forward spot is a proxy, not the leg P&L.** The dot claims direction; I tested direction. A real
   leg carries theta and vega over the same 30 minutes, and G11's exit doctrine resolves most legs at
   the time stop. A spot-direction edge that fails to separate from chance cannot become a P&L edge,
   so the verdict direction is safe — but a *passing* spot test would not have been sufficient either.
2. **The p90 cells are not cleanly dead.** p=0.0395 / 0.0245 one-sided survive as raw numbers and die
   only on multiple-comparison correction and thin-cell/run-structure grounds. If the owner wants this
   door genuinely shut rather than "not shown open", the honest instrument is forward paper on real
   captured OI, not more retrospective slicing of the same 15–22 sessions.
3. **Effective sample is unchanged from §2** — ~15–22 sessions after the warm-up and forward-window
   losses. All percentile and bps figures in §12 inherit the same ~1-significant-figure caveat.
4. **I chose the forward-outcome test after the pre-committed criteria had already passed.** That is
   the correct scientific move (the criteria were proven blind), but it is post-hoc, and I did not
   pre-register the threshold set, the horizon, or the null. Recorded so it is not later read as a
   pre-registered result.
5. **Only two baseline windows were tried, both pre-declared.** I did not sweep the window, deliberately
   — sweeping would manufacture a passing result. It follows that I have **not** shown that *no* window
   works, only that the two principled ones do not.
6. **The §12.4 red-proof used one synthetic parameterisation** (gaussian increments at the observed
   `sd(Δ)`). Real R1 has fatter tails than a gaussian walk. A fatter-tailed synthetic would pass the
   criteria at least as easily, so the conclusion is conservative in the right direction.
