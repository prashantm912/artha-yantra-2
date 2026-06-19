# 3:20 Strategy (overnight signal + methodology)

**What this is.** OI Pulse's end-of-day **overnight/positional** strategy — the EOD counterpart to [morning-trade.md](morning-trade.md). Sourced from the Manual (V10) Addendum 8 (PDF p290–p296), logged from [MANUAL-V10-GAP-ANALYSIS.md](../MANUAL-V10-GAP-ANALYSIS.md) §A4. Live app route under **Strategies** as **"3:20 Strategy"** (add to README menu map).

> Paid/AI signal feature; **not yet live-verified** ([NOT-CAPTURED.md](../NOT-CAPTURED.md)). Methodology paraphrased for replication, not trading advice. Builds on [oi-interpretation-method.md](../oi-interpretation-method.md).

## Premise

A strong trend into the close has a high probability of continuing at the next open; the overnight move should cover theta decay. Decision is made just before close, ~**15:20**.

## 3-factor pre-close checklist

1. **Day's price action / chart** — consolidation, fall/rise with volume, close location.
2. **OI analysis** — Total OI + ΔOI (heavy Call writing / PE unwinding ⇒ bearish, etc.).
3. **Broader markets** — Nifty trend, support breaks, gaps to fill.

## Overnight risks (explicit caveats)

- **Global markets** turning overnight.
- **Domestic news** (e.g. a sudden policy/tax announcement).

## Trade rules

- **Do not risk capital** — use only **10% of the day's profit**.
- Next day, once the option price is above the buy price, **start trailing**.
- **SL = 50% of premium** (or your risk appetite, whichever is lower).

## Signal output

At 15:20, if a signal exists: **option type, strike, buy level**. **Call/Put Signal Entry** tables + a **Chart** column (candles of the option with the signal marked). The result chart overlays **Candles + OI** with Entry / Out / Stop-Loss / Opening-Performance markers.

## Worked example

13-Dec BANKNIFTY **37100 PE** @ ~355 (signal at 15:20); next day the 37100 PE first candle made a high of **594** — enough for a ~1% day target.

## Replication notes (→ ArthaYantra)

- Consumes [futures/oi-analysis.md](../futures/oi-analysis.md) and the day's price/broader-market read.
- Output schema (type+strike+level, Call/Put Signal Entry, Candles+OI result chart) is the spec; the AI generation is out of scope unless we build an equivalent.
