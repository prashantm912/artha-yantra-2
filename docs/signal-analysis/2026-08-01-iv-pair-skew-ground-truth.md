# R1 wing-skew — ground truth before any code (G13 successor)

**Written 2026-08-01. MEASUREMENT ONLY — no production code changed, nothing armed, no dot redefined.**

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
