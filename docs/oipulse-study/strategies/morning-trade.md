# Morning Trade (signal + methodology)

**What this is.** OI Pulse's signal-assisted **opening scalp**, sourced from the Manual (V10) Addendum 8 (PDF p279–p289) and logged from [MANUAL-V10-GAP-ANALYSIS.md](../MANUAL-V10-GAP-ANALYSIS.md) §A3. The live app exposes it under the **Strategies** menu as **"Morning Trade"** (route not yet in the README menu map — add).

> Paid/AI signal feature; **not yet live-verified** (see [NOT-CAPTURED.md](../NOT-CAPTURED.md) — same tier as OSPL/Qwik-Scalp). Methodology paraphrased for replication, not trading advice. Builds on [oi-interpretation-method.md](../oi-interpretation-method.md).

## Premise

Markets are most volatile at the open; the AI crunches pre-open data and emits a signal. You can make ~1% right at the open on good days.

## 5-factor pre-open checklist (plan made the prior evening for the next open)

1. **Prev-day price action / chart** — trend, structure, close location.
2. **OI analysis** — Total OI + ΔOI (heavy Call OI / Call writing / PE unwinding ⇒ bearish, etc.).
3. **Broader markets** — Nifty futures chart + FII data.
4. **Overnight global** — Dow (futures), volume.
5. **Pre-open data (~09:08)** — Indice/stock advances–declines + Prev-Day-Break badges.

## Gap-size decision tree (judged against an important support/resistance)

- Gap **too big** ⇒ do not chase (no trade).
- Three gap-down cases by where price opens vs support: (1) **above** support ⇒ most favourable to buy PE at open; (2) **slightly below**; (3) **well below** ⇒ **no trade**.
- Mirror for a gap-up (chase only with moderate quantity).

## Signal output

A table with **Call Signal Entry / Put Signal Entry** columns: Date, symbol, Expiry, **Strike, CE/PE, buy price-range** (e.g. "36900 PE, 296–396"). Note: "signal updates after 09:11 if and only if a signal is available." Enter within ~10% of the strike's premium.

## Exit & sizing rules (two exits, because the open is risky)

- **Trail** as soon as average price reaches the cost price.
- **Exit within the first 3 minutes.**
- Deploy only **10% of the previous day's profit**; do not risk capital.

## Worked example

14-Dec BANKNIFTY **36900 PE** (16-Dec expiry), buy range 296–396; first candle opened 306 → high 462. (No Call signal fired that day.)

## Replication notes (→ ArthaYantra)

- Consumes [futures/pre-open-market.md](../futures/pre-open-market.md), [futures/oi-analysis.md](../futures/oi-analysis.md), [fii-dii/capital-market.md](../fii-dii/capital-market.md).
- Signal-generation logic is proprietary/AI — for replication, the *output schema* (type+strike+range, "after 09:11") and the *exit rules* are the spec; the model itself is out of scope unless we build an equivalent.
