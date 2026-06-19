# OI Interpretation Method — how to read the OI suite to trade

**What this is.** The shared trading-interpretation layer behind the OI pages. Our per-page docs capture *what each screen shows and what API feeds it*; this doc captures *how a trader reads those columns into a directional view and a trade*. Sourced from the OI Pulse Manual (V10), Section B (PDF p022–p073), and logged additively from [MANUAL-V10-GAP-ANALYSIS.md](MANUAL-V10-GAP-ANALYSIS.md) (§A1, §B, §E).

Per-page docs link here from their `## Interpretation` sections rather than repeat this. None of this removes or changes existing UI/API documentation — it is the methodology that sits on top of it.

> The trading rules below are the manual's documented method, paraphrased as engineering notes for replication. They are a *reference for the analytics we surface*, not trading advice.

---

## 1. The four OI states (the core primitive)

This is the `inOiInterpretation` enum already documented in [00-global-shell.md](00-global-shell.md) and [futures/oi-analysis.md](futures/oi-analysis.md), here with its *meaning*:

| Price | OI | State (enum) | Who is acting | Conviction |
|---|---|---|---|---|
| ↑ | ↑ | **Long Build-Up** (1) | new longs entering | **fresh position → strong** |
| ↓ | ↑ | **Short Build-Up** (3) | new shorts entering | **fresh position → strong** |
| ↑ | ↓ | **Short Covering** (4) | trapped shorts buying back | position-closing → **weak** (no fresh money) |
| ↓ | ↓ | **Long Unwinding** (2) | longs booking/exiting | position-closing → **weak** |

Key read the UI badge alone doesn't convey: **Build-Ups are fresh-money moves (reliable); Covering/Unwinding are exhaustion/closing moves (weaker).** A rally on falling OI (Short Covering) is not the same quality as a rally on rising OI (Long Build-Up).

## 2. Signal-strength grading (Volume + Level-Break + significance)

A state label is only *actionable* when it is **strong**. Grade each row:

- **STRONGEST** — high Volume **and** the matching Level-Break **and** a significant ΔLTP **and** a significant ΔOI.
- **STRONG** — high Volume, no level break.
- **WEAK** — low Volume, or insignificant ΔLTP / ΔOI (discount these).

The matching Level-Break is **asymmetric**: bullish states (Long Build-Up, Short Covering) pair with a **Day-High-Break (`isDayHighBrake`)**; bearish states (Short Build-Up, Long Unwinding) pair with a **Day-Low-Break (`isDayLowBrake`)**. The `isDayHighVolume` flag already in our capture is the "high Volume" input.

> Replication idea: a derived "Signal Strength" annotation/column computed from `{inOiInterpretation, isDayHighVolume, isDayHighBrake/isDayLowBrake, |inLtpDiff|, |inOiChange|}`. Additive — does not change existing columns.

## 3. Scenario dependence (regime flips the weak states)

Don't read Short Covering / Long Unwinding context-free. The prevailing day matters:
- **Short Covering** is "strongest **during a bullish scenario**."
- **Long Unwinding** is "strongest **during a bearish scenario**."
Same label, opposite meaning depending on whether the day is broadly up or down.

## 4. OI vs Volume fundamentals

- OI = count of *distinct outstanding* contracts; Volume = count of *times traded*. So **Volume ≥ OI, always**.
- OI **rises** when sellers write fresh contracts that buyers take; OI **falls** when those contracts are bought back.
- **Volume = conviction.** High-volume regimes strengthen whatever state is showing. High-volume asymmetry: longs dominate → Long Build-Up / Short Covering; sellers dominate → Short Build-Up / Long Unwinding.

## 5. Options OI — the four-quadrant model + the 50% strength filter

Options OI is the same primitive applied to **CE and PE mirrored** (see [options/oi-spurt.md](options/oi-spurt.md) for the quadrant Vue keys). The manual adds the **trade layer**:

| Quadrant | OI / Price | State | Trade role | Action |
|---|---|---|---|---|
| **Q1** | OI↑ / Price↑ | Long Build-Up | buyer focus | candidate buy (if dots agree) |
| **Q2** | OI↑ / Price↓ | Short Build-Up | writer focus | candidate write/sell |
| **Q3** | OI↓ / Price↑ | Short Covering | buyer, but **short-lived** | scalp only, precise timing |
| **Q4** | OI↓ / Price↓ | Long Unwinding | hedging/deep-OTM | **retail: avoid** |

Two rules that gate everything:
1. **Strength filter:** a strike *appearing* in a quadrant is not a signal. It qualifies only when **%ΔLTP > 50% AND %ΔOI > 50%** (the `inLtpChangeInPercentage` / `inOiChangeInPercentage` columns are the decision metrics).
2. **Calls and Puts in the same quadrant imply opposite market direction.** Read all four quadrants together (e.g. Puts-strong-in-Q1 + Calls-strong-in-Q2 + OTM-Puts-in-Q3 ⇒ market falling).

## 6. OI/LTP "X-crossover" signal

On a strike's dual-axis chart ([options/oi-chart.md](options/oi-chart.md)): plot OI and premium (LTP) on the two Y-axes against time. When the two lines **cross at a steep angle (an "X")**, momentum is building:
- X with **price↓ / OI↑** = **Strong Short Build-Up**.
- X with **price↑ / OI↓** = **Strong Short Covering**.
- The **opposite-type option mirrors** it (a CE Short-Build-Up X coincides with a PE Short-Covering X).
- Short-covering moves are powerful but **not long-lasting** — must be caught at the cross.
- **Best observed on the 15-min timeframe.**

> Replication: derivable as an overlay/marker on the existing dual-axis OI-vs-premium chart.

## 7. Support / resistance / range from OI (C1)

From total CE/PE OI ([options/oi-statistics.md](options/oi-statistics.md)):
- **Strike with max Put OI = support**; **strike with max Call OI = resistance**.
- The band between them = the **probable day range**.
- On the Individual-OI bar chart: green (Call OI) bars **right of ATM** = resistance walls; red (Put OI) bars **left of ATM** = support walls.
- **Breach of a high-OI wall** traps those writers → strong Short Covering → a buy-the-option moment.
- The **cumulative-OI** bar is a **writer-dominance** gauge (are Call or Put writers more active overall).

## 8. Timeframe roles

- **60-min** — the day's major trend / overnight context. Unreliable in the *first half* of the day (not enough intervals yet).
- **15-min** — the intraday trend (reliable through the first half).
- **5-min** — entry timing (noisy alone).
- Always confirm a small-timeframe entry against the larger-timeframe trend.

## 9. Strike selection (C5)

For **buying** options: **avoid OTM** (no intrinsic value, premium is fragile); **prefer ATM/ITM**. Use [options/options-premium.md](options/options-premium.md) (which plots **extrinsic value** = `LTP − intrinsic`) to find an ITM strike that is relatively *cheap* vs its neighbours — the higher-leverage buy.

## 10. Connect-the-dots confluence (the decision rule)

The actual method is **multi-signal confluence**: take a trade only when the *majority* of independent "dots" align; discount dissenting dots on the day. The dots:

Global markets (Dow → Crude → USD-INR → SGX/GIFT Nifty → Europe) · Futures OI · Options OI · India VIX · Implied Volatility (the [CE–PE ~10-pt spread rule](options/active-strikes-iv.md)) · Price Action.

- **Price-action volume gate (final confirmation):** two consecutive same-colour volume candles **≥ ~50K** on 1-/3-min futures before entry. *(exact 50K figure → Phase B verify, see [PHASE-B-PLAN.md](PHASE-B-PLAN.md))*
- Trading is **probability** — "no holy grail"; judge over a *series* of trades, aligning with dominant participants.
- The manual's worked examples (PDF p057–p072) and the [expiry](strategies/expiry-day-trading-plan.md) / [morning](strategies/morning-trade.md) / [3:20](strategies/3-20-strategy.md) plans are the end-to-end templates of this confluence.

---

## Where each input is rendered (cross-links)

| Method input | Page doc |
|---|---|
| 4-state OI table / strength / level-break | [futures/oi-analysis.md](futures/oi-analysis.md), [options/oi-analysis.md](options/oi-analysis.md) |
| OI/LTP crossover, dual-axis | [futures/oi-chart.md](futures/oi-chart.md), [options/oi-chart.md](options/oi-chart.md) |
| Quadrant scanner + 50% filter | [futures/oi-spurt.md](futures/oi-spurt.md), [options/oi-spurt.md](options/oi-spurt.md) |
| Support/resistance/range, writer dominance | [options/oi-statistics.md](options/oi-statistics.md) |
| Strike selection / extrinsic premium | [options/options-premium.md](options/options-premium.md) |
| IV spread / regimes | [options/active-strikes-iv.md](options/active-strikes-iv.md) |
| VIX semantics | [features/vix-index.md](features/vix-index.md) |
| Global confluence | [features/connecting-dots.md](features/connecting-dots.md), [features/world-indices.md](features/world-indices.md) |

*Items needing live confirmation (strength thresholds, the 50K figure, Trending-OI sign conventions) are tracked in [PHASE-B-PLAN.md](PHASE-B-PLAN.md) §Verify.*
