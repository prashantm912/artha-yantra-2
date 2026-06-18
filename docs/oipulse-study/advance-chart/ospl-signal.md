# OSPL Signal — `/app/advance-chart` (indicator)

**What this is.** OI Pulse's flagship **proprietary AI signal**, rendered as an indicator on the Advance Chart. Sourced from the Manual (V10) Addendum 8 (PDF p266–p273) and logged from [MANUAL-V10-GAP-ANALYSIS.md](../MANUAL-V10-GAP-ANALYSIS.md) §A5. Previously flagged unobserved in [NOT-CAPTURED.md](../NOT-CAPTURED.md) item 4 — this fills that gap from the manual.

> **Live-confirmed 2026-06-18** (accessible on the owner's Annual plan). Methodology paraphrased for replication, not trading advice. Builds on [oi-interpretation-method.md](../oi-interpretation-method.md).

> **Live-confirmed structure (2026-06-18).** OSPL Signal, OSPL Qwik scalp and OSPL Volume are **TradingView
> Pine custom studies on the Advance Chart** — added via the Indicators dialog, accessible on the owner's
> Annual plan — **NOT separate pages or endpoints**. Specifics:
> - **OSPL Signal** uses params **(10, 2)** — identical to SuperTrend(10,2); it renders a SuperTrend-style
>   trend line + stop level (signal = trend flip), i.e. a SuperTrend-derived directional buy/sell.
>   **Source-level cross-check (2026-06-18):** the public community OSPL template (Pine v4, Siva Sir —
>   reproduced in [ospl-community-pine.md](ospl-community-pine.md)) carries SuperTrend exactly
>   `Factor=2, Pd=10` and a High-Volume flag at `volume > 50K`, matching the (10,2) signal core and the
>   ~50K dark-bar threshold observed here. (The proprietary studies stay closed-source; the community
>   template only confirms the shared building blocks, not the Gen/Void scalp layer.)
> - **OSPL Qwik scalp** = faster scalp variant (same study family).
> - **OSPL Volume** Inputs = MA Length **20** + a "Color based on previous close" toggle; the dark-bar
>   threshold (50K BankNifty / 125K Nifty) is **hardcoded in the Pine script, NOT a user input**.
>   - **vs standard Volume (live-proven 2026-06-18, NIFTY-I 3m):** same volume value, but the **hue rule
>     differs** — standard Volume colors by `sign(close − open)` (candle body); OSPL Volume colors by
>     `sign(close − previous close)` (bar-to-bar). They disagree on reversal bars. Proof: the 09:15
>     opening bar `O 24105.10 / C 24098.40 (+8.50 vs prev close 24089.90)` is **RED in standard**
>     (close < open) but **GREEN in OSPL** (close > prev close). OSPL palette is pastel; standard is vivid;
>     OSPL MA line = black(20), standard = blue MA20 + SMA9. OSPL adds a latent **dark-bar** flag (bar
>     darkens once volume > the hardcoded threshold) — flags abnormal/institutional volume.
> - The Pine source is server-protected, so the exact logic cannot be extracted.
>
> See [PHASE-B-FINDINGS.md](../PHASE-B-FINDINGS.md) §6.

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

## OSPL Qwik scalp — live reverse-engineered (2026-06-18, NIFTY-I 3m)

Added live (Indicators → "qwik" → "OSPL Qwik scalp"). It is a **price-pane overlay** (not a
separate sub-pane), distinct from OSPL Signal's In/Out model. Observed behaviour:

- **Discrete entry signals** drawn as arrows at swing extremes:
  - **Green ↑ at a swing low = bullish** (look long).
  - **Red ↓ at a swing high = bearish** (look short).
- Each signal carries two price labels: **`Gen. At:` = trigger/entry price** (the close that fired
  the signal) and **`Void. At:` = the invalidation level** (the protected swing extreme).
  - Bullish: `Void` is BELOW `Gen` (≈25–40 pts on NIFTY 3m, e.g. Gen 23982.2 / Void 23957.6;
    Gen 24075 / Void 24044; Gen 24063.9 / Void 24023).
  - Bearish: `Void` is ABOVE `Gen` (e.g. Gen 24064 / Void 24081; Gen 24053 / Void 24081).
- A single yellow **"Void Line"** horizontal is plotted at the `Void. At` level and **persists to the
  right until invalidated or flipped** — it is the structure-based trailing stop.
- **Consecutive same-direction signals can share one Void Line** (re-entry/add while the protected
  swing is unbroken — two bearish signals both voided at 24081).
- Signals are **sparse / event-driven** (a handful per session), not per-bar.

**Settings.** Style tab = a SINGLE plotted series ("OSPL Qwik scalp", yellow — the Void Line); the
arrows + Gen/Void labels are plotshape/label calls, not separately styleable. **There is NO Inputs
tab** → the study is **fully automatic, zero user params** (unlike OSPL Signal's (10,2)). Visibility +
Style only.

**Extraction ceiling.** The study renders inside the sandboxed TradingView iframe (`tradingview_*`,
canvas) and its Pine source is server-protected, so the computed series / exact trigger rule cannot
be pulled — only the visual contract above. Model reads as: **enter on a momentum trigger (`Gen`),
stop at the last protected swing (`Void`), exit/flip when the Void Line breaks.**

## Replication notes (→ ArthaYantra)

- The signal-generation model is proprietary. For replication the *display schema* (arrow + In/Out price labels) and the *input feature set* (Price/Volume/OI/VIX/Global/SuperTrend) are the spec for any equivalent we might build.
- This is an Advance-Chart overlay, so it lives alongside [advance-chart.md](advance-chart.md) and [multiframe-chart.md](multiframe-chart.md).
