# OSPL Signal — `/app/advance-chart` (indicator)

**What this is.** OI Pulse's flagship **proprietary AI signal**, rendered as an indicator on the Advance Chart. Sourced from the Manual (V10) Addendum 8 (PDF p266–p273) and logged from [MANUAL-V10-GAP-ANALYSIS.md](../MANUAL-V10-GAP-ANALYSIS.md) §A5. Previously flagged unobserved in [NOT-CAPTURED.md](../NOT-CAPTURED.md) item 4 — this fills that gap from the manual.

> **Not live-verified** (Annual-plan-gated paid feature). Methodology paraphrased for replication, not trading advice. Builds on [oi-interpretation-method.md](../oi-interpretation-method.md).

## What it is

An in-house AI signal — "the crux of the trading philosophy." AI monitors the market continuously and fires when a clearly-defined **Trend AND Momentum** in one direction is detected with high probability; it also signals when that trend/momentum is **fading** (an exit condition).

## Access / gating

Added like any indicator: **Indicators → search "ospl" → "OSPL Signal"** (note the picker shows two separate studies: **OSPL Signal** and **OSPL Volume** — different things). Available to **Go Annual** users only.

## Inputs (what the AI watches)

Price, Volume, Open Interest, India VIX, Global markets (and SuperTrend), all live.

## On-chart display

- A **directional arrow** at the signal candle: **green up = look long, red down = look short.**
- Paired **"In:" / "Out:"** price labels (e.g. "In: 37405.1 / Out: 37589"). Multiple In/Out pairs through the session.
- **In = conditions triggered** (start considering entry). **Out = momentum has ended** (take no new trades in that direction).
- Critical: **In/Out are condition flags, NOT literal entry/exit prices.** Direction is "likely," never certain.

## Playbook (manual's recommendation)

- Green → buy CE (or long future); red → buy PE / short.
- Enter after the signal candle closes; phased averaging as price moves favourably.
- ~1%-of-capital target; trail once in decent profit.
- **Place SL near the "Out" level.**
- Be aggressive on the first (highest-probability) signal; reduce size on later ones while still "In."
- When "Out" appears, momentum has dried up — stop.

> The averaging/Martingale and 1%-target guidance is OI Pulse's own risk method — recorded as *their* recommendation, not an ArthaYantra endorsement.

## Audio Alert

The Advance Chart's **"Audio Alerts"** toolbar button is specifically the **OSPL-Signal** sound alert (Yes/No enable dialog) — it plays a sound when the signal fires, for hands-off monitoring. (Correct the scope note in [advance-chart.md](advance-chart.md), which calls it a generic price alert.)

## Replication notes (→ ArthaYantra)

- The signal-generation model is proprietary. For replication the *display schema* (arrow + In/Out price labels) and the *input feature set* (Price/Volume/OI/VIX/Global/SuperTrend) are the spec for any equivalent we might build.
- This is an Advance-Chart overlay, so it lives alongside [advance-chart.md](advance-chart.md) and [multiframe-chart.md](multiframe-chart.md).
