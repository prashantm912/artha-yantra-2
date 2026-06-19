# Expiry-Day Trading Plan (methodology)

**What this is.** OI Pulse's discretionary expiry-day system, sourced from the Manual (V10) Addendum 5 (PDF p161–p190) and logged from [MANUAL-V10-GAP-ANALYSIS.md](../MANUAL-V10-GAP-ANALYSIS.md) §A2. This is a **methodology**, distinct from the *feature* [oi-expiry-strategy.md](oi-expiry-strategy.md) ("Options EOD Oi Analysis", the last-N-day per-strike OI+premium table) which this plan *consumes*. Additive; nothing here changes existing docs.

> Documented method paraphrased for replication context, not trading advice. See [oi-interpretation-method.md](../oi-interpretation-method.md) for the shared OI primitives this builds on.

The expiry day is split into two independent plans.

## A. Morning plan (first ~1 hour)

Read the day off six "connecting dots", in order:
1. **Price action & Volume** — mark Support/Resistance on a higher timeframe; was yesterday's move on high volume; is today's counter-move on *equal* volume? A move on *lower* volume than the prior opposing move = weak.
2. **Open Interest** — per-strike OI + premium over the last N sessions (via the OI-Expiry feature).
3. **Global markets** — Dow as primary; where Dow sits at our next-morning read vs our prior close.
4. **FII/DII** — net positioning; weight FII over DII on disagreement.
5. **Crude & USD-INR.**
6. **Immediate domestic news.**

**Strike rule:** round prev-day spot close to nearest 500 = ATM; analyse ±5–6 strikes, all multiples of 500 (CE and PE).

**Decision thresholds (from the worked BankNifty 04-Mar-2021 example):**
- Call covering of only ~40% = capped upside (weak); a strong bullish signal needs **~55–60% Call covering** plus Long Build-Up.
- Check whether premiums **closed near day-high vs day-low** (bullish vs bearish bias).
- Up-move on lower volume than the prior down-move ⇒ rally likely capped until the resistance is taken out *with volume*.

**Execution tools:** VWAP, RSI (the example opened with RSI<20), opening-candle behaviour. "Sell on rise / don't chase the gap" until the support breaks with volume + global confirmation.

## B. Closing plan (last 1–1.5 hr before 15:30)

Wild final-hour swings driven by option market-movers (predominantly sellers on expiry). Only **two factors**:
1. **Day's price action** — did support hold / resistance reject; if neither side has conviction, expiry likely *between* the levels.
2. **Last-hour ΔOI** — read per-15-min OI-interpretation badges on the high-ΔOI strikes (multiples of 500). E.g. after ~14:30, Short Covering on PE + Short Build-Up on CE ⇒ bearish into close. Enter on a **VWAP cross**.

## Risk framing

Always trade a pre-made plan ("an idiot with a plan beats a genius without one"). The plan *decodes* the day; it is not a guarantee — size and manage accordingly.

## Replication notes (→ ArthaYantra)

- This is a checklist/playbook doc, not a screen. It references the OI-Expiry feature, [futures/oi-analysis.md](../futures/oi-analysis.md), [fii-dii/](../fii-dii/) and pre-open data.
- If we surface an "expiry-day" guided view, the strike-set rule (ATM rounded to 500, ±5–6) and the two-session structure are the spec.
