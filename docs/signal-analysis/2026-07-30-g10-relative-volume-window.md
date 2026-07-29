# G10 — the relative volume floor's opening window: code read + reconciliation

**Written 2026-07-30.** Discharges the code-read step the ledger row **G10** made a precondition.
**No code changed** — the fix is HOLD tier (it changes which signals fire) and is not proposed here.

---

## Verdict

**The floor's opening-window defect is a LEVEL SHIFT, not an outlier problem — which is a different
diagnosis from the one the row carried, and it eliminates one of the three candidate fixes.** The
row attributed the inflated floor to "4 of the first ten buckets ≥100,000" against a session median
of 15,015. Those four bars are not the cause: on 2026-07-29 the **minimum** of the first ten 3m bars
was **32,760 — already 2.2× the whole session's median**. Every bar in the opening window is
elevated, so the median of that window is elevated. A median is the correct tool against outliers
and the wrong tool against a level shift; no amount of robustness rescues it, because there is
nothing anomalous to reject.

The exact live parameters are now pinned, and one observed threshold reconciles **to the paisa**.

---

## 1. The window, resolved (all `sourced`, read 2026-07-30)

| site | what it establishes |
|---|---|
| `ScalperConfluenceGate.java:423` | the floor is called with `priorVolumes(bank, index, oiProps.relativeVolumeWindow())` |
| `ScalperConfluenceGate.java:1213-1219` | window = bars `index-1 … index-window`, deploy bar EXCLUDED, read from `bank.builtin("volume", …)`; the loop guard is `index - j >= 0`, so it **truncates at the series start** |
| `ScalperGates.java:188-202` | nulls filtered, sorted; `< max(minBars,1)` non-null ⇒ **absolute fallback**, else `median × multiplier` |
| `ScalperOiProps.java:102-104` | defaults **multiplier 1.5 · window 20 · minBars 10** |
| `ScalperGates.java:173-175` | absolute fallback = `VOL_FLOOR` — **NIFTY 125,000**, other indices 50,000 |

**These defaults ARE the live values.** No scalper YAML carries a numeric override (the 21 armed
strategies carry only the `relative-volume-floor` tag), `application.yml` has no
`artha.scalper.oi.relativeVolume*` key, and `env | grep -i RELATIVE` inside `ay-strategy-signal-service`
returns nothing. Verified this session, all three.

## 2. The reconciliation the row could not do from SQL

The row recorded 87,799 as an observed live threshold and matched it to "1.5 × median of a 10-bar DB
window", while noting the window length was not established. The code explains it exactly: **the
window is 20 bars but truncates at the series start**, so at deploy bar 09:45 — the 11th bar of the
session — only 10 prior bars exist.

Bars 0–9 of 2026-07-29 `NIFTY26AUGFUT` 3m (live `artha`, 1m rolled to 3m):

```
09:15 476840   09:27  53040   09:39 105560
09:18 124410   09:30  49010   09:42  32760
09:21 153075   09:33  46995
09:24  64025   09:36  52325
```

Sorted, n = 10 (even) ⇒ `median = (vols[4] + vols[5]) / 2 = (53040 + 64025) / 2 = 58532.5`.
`58532.5 × 1.5 = ` **87,798.75**, i.e. the observed **87,799**.

Independently confirmed by aggregate: `percentile_cont(0.5)` over the first ten bars returns
**58532.5**. The window length, the minBars gate, the multiplier and the truncation behaviour are
all pinned by that single exact match.

⚠️ The row's other two thresholds still do not reconcile against DB-rolled bars. **That is expected
and was already documented** — the engine reads its in-memory `LiveSeriesStore`, which
`PartialBucketCanary` proves diverges from the DB rollup. It is not pursued: the finding never
rested on those two, and one exact match is sufficient to pin the parameters.

## 3. What the numbers actually say (2026-07-29, 125 bars)

| quantity | value |
|---|---|
| session median, 3m | **15,015** |
| median of the first 10 bars | **58,532.5** (3.9× session) |
| **minimum** of the first 10 bars | **32,760** (2.2× session) |
| bars in the whole session clearing the 09:45 floor of 87,799 | **5 of 125** (≈ p96) |

The third row is the finding. There is no subset of the opening window that looks like the rest of
the session, so the median has nothing normal to fall back on.

**And the pre-09:45 regime is worse still.** Below `minBars = 10` the floor is not a median at all —
it is the **absolute 125,000** NIFTY fallback, which only 2 of the session's 125 bars ever clear. So
the first half-hour runs a floor stricter than the surge-inflated median that follows it, and the
floor *drops* at 09:45 (125,000 → 87,799) before decaying. Any account of the 43%-of-blocks-before-11:00
figure has to attribute part of it to the absolute fallback, not to the relative floor at all.

**Decay is mechanical and datable.** With a 20-bar window on a 3m primary, bar 0 (09:15, 476,840)
leaves at deploy bar 21 = **10:18**, and the last ≥100k bar (09:39) leaves at deploy bar 29 =
**10:42**. After 10:42 the window holds only regular-session bars. That matches the row's observed
monotonic decay to ~26,000 by 11:21.

## 4. Consequence for the three candidate fixes

The row listed three, to be picked after the code read. The code read decides two of them.

- **"Exclude the opening ~30 min from the window" — REJECTED on its own.** Excluding those bars
  leaves fewer than `minBars` prior bars during the warmup, so the gate falls through to the
  **125,000 absolute floor** — stricter than the inflated median it was meant to relieve. It makes
  the row worse unless paired with a seed.
- **"Seed the window from the PRIOR session's median" — the strongest candidate.** It is the only
  option that addresses *both* defects: the window is full at 09:15 (no truncation, no
  `minBars` cliff, no absolute-fallback regime) and it is filled with normal-regime bars (no level
  shift). It also needs no new concept — the prior session's median is one query.
- **"Normalise the baseline by time-of-day" — correct but heaviest.** It is the only one that
  survives a day whose open is genuinely different from the prior session's, but it needs a
  time-of-day volume profile with its own ground-truth distribution before any constant is chosen.

**Recommendation: seed from the prior session.** ⚠️ **NOT BUILT AND NOT PROPOSED AS A KNOB TURN.**
This is HOLD tier — it changes which signals fire — and it lands with the entry-gate track as one
owner decision, per the row.

⚠️ **Still do not conflate with T1.** T1 is the MULTIPLIER (1.5) and is REJECTED on the 07-29
counterfactual (2W/9L). This row is the WINDOW. Lowering the multiplier would lower the floor
uniformly, including the 10:42-onward regime where it is already correct — it makes this row worse.

---

## Claim labels

- Every table in §1, and the three "no override" checks: **sourced** (file:line and live container
  env, read 2026-07-30).
- §2 and §3 numbers: **computed** this session from `marketdata.candles` on live `artha`.
- §4's rejection of the exclude-the-open fix: **computed** — it follows from `minBars` + the
  absolute fallback, both sourced above.
- The relative ranking of "seed" vs "time-of-day": **assumed** on a single session's tape. One
  session establishes the mechanism, not that the prior session's median is a good predictor of the
  next open — that wants a multi-session check before anyone builds it.
