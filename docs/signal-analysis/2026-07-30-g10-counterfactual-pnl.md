# G10 — §4.2 counterfactual P&L: the profile is correct, and it must NOT be armed

**Written 2026-07-30.** Run against the committed implementation (PR #1119, `3a8c63ce`), not a
reconstruction. **Nothing armed. Recommendation: keep the build default-OFF.**

---

## Verdict

**The time-of-day profile does exactly what it was built to do — and the entries it unlocks lose
money.** It admits 265 additional legs across 10 sessions; gross they return **+324.87 points**, but
that is **carried entirely by 5 legs of 265**, the **median leg loses 1.65 points**, and a 1%
round-trip cost turns the whole thing into **−590.95**. Gross edge is **+1.23/leg** against a
realistic cost of **~3.5/leg** on a 345-point average premium.

**Do not arm it.** The restrictiveness bias was real and the fix is correct engineering; the entries
behind that bias are simply not profitable.

---

## 1. Method — exact, not reconstructed

The floor the engine actually applied is stored: every one of the 7,471 `volume-floor` blocks carries
`diagnostic.operand` (the bar volume) and `diagnostic.threshold` (the live floor), so the current
side needs no modelling at all. 5,035 of them also carry a `wouldBeLeg` and are priceable.

The counterfactual floor replicates the committed code: median of the same IST bucket time over the
prior 3 sessions × 1.5, falling back to the live floor below 2 samples.

Exit model identical to T1 / G11 / G13: **TP +35%, SL −25%, else the 30-minute time stop.**
Deduped by `(bar_time, tradingsymbol)` — the slug fan-out correction.

## 2. Size — and why this is not G13

| | G13 (IV bloc) | **G10 (volume floor)** |
|---|---|---|
| rows newly passing | 620 | 1,556 |
| **distinct legs after dedupe** | **6** | **265** |
| priced (snapshot coverage) | 6/6 | **265/265** |

G13 collapsed to an anecdote because the composite binds on 0.9% of blocks. **`volume-floor` binds on
88%**, so here the leverage is real and the sample is large enough to decide on.

## 3. Result

**117 W / 148 L — a 44% win rate — and +324.87 points gross.**

| metric | value |
|---|---|
| net | +324.87 |
| **net excluding the top 5 legs** | **−305.88** |
| **median leg** | **−1.65** |
| best / worst leg | +156.35 / −120.25 |
| average entry premium | 345.6 |
| TP touches / SL touches | 6 / 12 |
| **net after 1% round-trip cost** | **−590.95** |

Three readings, and they agree:

1. **Concentration.** Removing 5 legs of 265 (1.9%) flips the sign. A result that fragile is not an
   edge, it is a handful of lucky legs.
2. **The typical leg loses.** Median −1.65 with a 44% win rate: the distribution is a majority of
   small losers paid for by a few large winners, and there are not enough of the latter.
3. **Costs bury it.** +1.23/leg gross against ~3.5/leg at 1% on a 345 premium. The edge is well
   inside the cost band before slippage is even considered.

## 4. This is now the FOURTH independent test saying the same thing

| test | knob | result |
|---|---|---|
| **T1** | relative-floor MULTIPLIER 1.5 → 1.2 | REJECTED — would-have-fired set 2W/9L, −121.95 pts |
| **T7** | composite THRESHOLD 0.600 → 0.55 | REJECTED — `composite-055` worst book, −₹321/close |
| **G13** | IV bloc dead weight | undecidable — 6 legs, sign flips on one |
| **G10** | volume-floor time-of-day profile | **REJECTED — 265 legs, negative after costs** |

**Every measured attempt to loosen this entry gate has lost money.** That is now a pattern with four
independent observations across three different knobs, and it should be the prior for any future
loosening proposal on this track.

⚠️ It also reframes G10's own finding. The opening window IS systematically restrictive — that part
stands, measured over 21 sessions. But the trades behind that restriction are unprofitable, so the
bias was, in effect, **protecting** the book. The defect is real and the money says leave it alone.

## 5. What to do with the build

**Keep it, default-OFF.** It is correct, reviewed, red-proofed, and byte-identical while unarmed. It
is the instrument that made this measurement exact, and it makes the answer re-testable when the exit
model changes — which matters, because **all four rejections above were measured under the 30-minute
time stop that G11 says is itself the dominant term in scalper P&L.** If G11 changes the exit, every
entry-gate rejection here is worth re-running against the new one.

---

## Claim labels

- §2, §3 tables: **computed** this session from live `artha`
  (`strategy.signal_rejections`, `marketdata.candles`, `marketdata.options_chain_snapshots`),
  2026-07-15 → 07-29.
- The 1% cost figure: **assumed** — a round-trip options cost band (brokerage + STT + slippage), not
  measured against this book's actual fills. The sign flips at roughly 0.35%, so the conclusion is
  not sensitive to the exact figure, only to it being above ~0.35%.
- T1 / T7 / G13 rows in §4: **sourced** from their ledger rows, not re-derived.
- Exit model TP +35% / SL −25% / 30-min: **sourced** from the T1 and G11 counterfactual method.
