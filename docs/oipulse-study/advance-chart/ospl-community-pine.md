# OSPL community Pine scripts — VWAP + SuperTrend confluence template

Source: two Pine v4 `study` overlays shared by **Siva Sir** (Options Scalping mentoring),
shorttitle **OSPL**. These are the **public / community** OSPL confluence template — **NOT** the
server-protected proprietary oipulse "OSPL Signal / Qwik scalp / OSPL Volume" studies (those are
closed-source; see [ospl-signal.md](ospl-signal.md)). They are recorded here verbatim because they
**cross-confirm** the live reverse-engineering: the proprietary OSPL Signal runs **SuperTrend(10, 2)**
and the dark/strong-volume threshold is **50K** — both are explicit in these scripts.

> Recorded 2026-06-18. Three scripts below: **(1)** the base (Siva Sir), **(2)** a modified variant
> with EMA + Bollinger Bands, **(3)** a bug-fixed copy of (2). Scripts 1 & 2 are reproduced **as-is**;
> script 3 is ours, with the single BB-source bug corrected (see [Fixes](#fixes)).

## Component overview

Overlay stack (each component has an enable toggle):

| # | Component | Params | Plot | Logic |
|---|---|---|---|---|
| 1 | **Session VWAP** | src=`hlc3` | red (orange in v2), w2 | Manual cumulative `Σ(src·vol)/Σvol`, **resets daily** (`t = time("D")`, `start = t>t[1]`). |
| 2 | **SuperTrend** | **Factor=2, Pd=10** | green/red line, w2 | Classic: `Up=hl2−2·atr(10)`, `Dn=hl2+2·atr(10)`; Trend=1 when `close>TrendDown[1]`, −1 when `close<TrendUp[1]`; `Tsl` = active band. Green=uptrend, red=down. |
| 3 | **Parabolic SAR** | 0.02 / 0.02 / 0.2 | black crosses | Built-in `sar()`. |
| 4 | **20 VWMA** | `vwma(close,20)` | blue line | Volume-weighted MA. |
| 5 | **High-Volume flag** | `Averageval` default **50** (×1000 = 50K) | blue square at bottom | `plotshape` when `volume > 50000`. |
| 6 | **200 EMA** *(v2/v3 only)* | `ema(close,200)` | fuchsia line | Long-term trend filter. |
| 7 | **Bollinger Bands** *(v2/v3 only)* | length 20, mult 2.0 | yellow + teal fill | basis `sma(close,20)` ± `mult·stdev`. |

Read = **trend/momentum confluence**: VWAP + SuperTrend + PSAR + VWMA must agree; the high-volume
square confirms conviction; the modified variant layers a 200-EMA bias filter and BB volatility envelope.

## Diff: v1 → v2

`OSPL-Modified with BB` = `OSPL - Siva Sir` **plus**:
- VWAP recolored red → **orange**.
- **+ 200 EMA** (`ema(close,200)`, fuchsia).
- **+ Bollinger Bands** (20, 2.0; yellow basis/upper/lower + teal fill, transp 95).

## Fixes

The v2 Bollinger block has a **source bug** (line 78):

```pine
src1  = input(close, title="Source")   // BB source = close
basis = sma(src1, length)              // centred on SMA(close,20)  ✓
dev   = mult * stdev(src, length)      // ✗ uses `src` (hlc3, the VWAP source), not src1
```

`basis` is centred on the **close** SMA, but the deviation half-width is computed from **hlc3**
(`src`, the VWAP source). Mismatched — on wide-range bars the bands sit slightly off a textbook BB.
**Script 3** below fixes this single line to `dev = mult * stdev(src1, length)`; everything else is
byte-identical to v2.

## Live correlation vs the proprietary OSPL studies (BANKNIFTY-I 3m, 2026-06-18)

Added the fixed-script components as TV built-ins (VWAP, SuperTrend **10/2**, PSAR 0.02/0.02/0.2,
VWMA 20, EMA 200, BB 20/2 — TV's built-in BB is single-source so already bug-free) **alongside** the
proprietary **OSPL Signal**, **OSPL Qwik scalp** and **OSPL Volume**, then compared on a live chart.

1. **Qwik scalp ⟷ SuperTrend(10,2) — same direction, Qwik leads.** Bullish Qwik arrows printed at
   swing lows **1–2 bars BEFORE** SuperTrend flipped green (Gen 57501.6 at the bottom, 57755 at the
   pullback — both while ST was still red); the bearish Qwik at the top (57730) was **coincident** with
   the ST red flip. So Qwik scalp = a **faster front-run of the SuperTrend(10,2) trend**; its `Void`
   level tracks the recent swing extreme (same role as ST's trailing stop, but structure-based/tighter,
   not ATR-based).
2. **OSPL Signal (10,2) — silent all window.** **Zero In/Out markers** across the visible ~2 days while
   Qwik fired ~4×. Confirms the design split: **OSPL Signal = rare high-conviction, Qwik scalp =
   frequent scalp**. Shares the ST(10,2) core (param match), but **could not be flip-compared today**
   (it never fired) — caveat, not a disproof.
3. **OSPL Volume ⟷ >50K dark-bar flag — never triggered.** All bars pastel, no dark bars: BANKNIFTY 3m
   futures volume stayed **<50K all session** (axis topped ~30K). The high-volume confluence is silent
   on a quiet day — consistent with the script's 50K threshold.
4. **Confluence at the bottom turn.** The strongest bullish Qwik (57501.6) lined up with PSAR flipping
   below price + price reclaiming VWAP + ST flipping green the next bar — the exact multi-factor
   agreement this confluence stack is built to catch. Price held **above EMA200** through the rally
   (long-term bias up), aligning with the bullish signals.
5. **Bollinger Bands(20,2) ⟷ Qwik scalp — band extremes + squeeze/expansion** (BB has **no OSPL
   proprietary counterpart**, so this is a confluence check, not an equivalence). The bearish Qwik
   (57730) fired as price **tagged/extended past the upper band and stalled** at the post-rally top — an
   upper-band-extreme fade. The rally was a **band-ride** (price hugging the upper band = strong trend)
   off a prior **squeeze** (narrow bands in the pre-rally chop) that **expanded** on the breakout bar.
   The bullish Qwik (57825) sat at a **pullback toward the basis/lower half** mid-uptrend (buy-the-dip).
   The **basis (SMA20)** acted as pullback S/R — price broke below it as the move rolled toward the
   lower band. With only ~3 signals in view this is suggestive, not statistical.

**Net:** the proprietary studies are not independent magic — **Qwik scalp ≈ a leading SuperTrend(10,2)**,
**OSPL Signal ≈ a filtered/rarer SuperTrend(10,2)**, **OSPL Volume ≈ the >50K dark-bar flag**. The
community Pine reproduces the building blocks; the proprietary layer adds the lead timing (Qwik) and the
conviction filter (Signal). Both observed flip-timings still depend on the closed Pine source, so treat
the equivalences as strong-but-empirical, not proven identity.

> **Correction to point 2 (added 2026-06-18 PM):** the "OSPL Signal silent / rare high-conviction / uses
> In:/Out:" read above was an artefact of that one quiet 2-day **BANKNIFTY** window. A wider **NIFTY-I 3m**
> sample (next section) shows OSPL Signal firing **~15–20× in three weeks** and drawing **Gen/Void/Void Line**
> (NOT In/Out). It is **not rare**, and it is the **same marker family as Qwik scalp** — see below.

## Live correlation — OSPL Signal vs the Pine components (NIFTY-I 3m, May 19–Jun 18 2026)

Second pass, on the **OSPL Signal** study (the chart already carried `OSPL Signal 10 2` + RSI; added
BB(20,2), SuperTrend(10,2), Parabolic SAR(0.02/0.02/0.2), VWMA(20), EMA(200), VWAP and OSPL Volume).
**Qwik scalp was deliberately NOT used here** — Qwik and OSPL Signal are counterparts of the *same*
Gen/Void engine, so correlating them to each other is circular; the point is to relate OSPL Signal to
the *independent* Pine confluence components.

**Two corrections to the OSPL Signal record (live-confirmed this pass):**
- **Live visual = `Gen. At:` / `Void. At:` / yellow `Void Line`** — the SAME marker family as OSPL Qwik
  scalp, **NOT** the "In:/Out:" labels the manual describes. The manual's In/Out is **stale**; legend
  reads `OSPL Signal 10 2`, arrows are green ↑ at swing lows / red ↓ at swing highs with a persisting
  Void Line. (See [ospl-signal.md](ospl-signal.md), updated.)
- **Not rare** — ~15–20 fires over the 3-week window (≈2–3/session), i.e. the BANKNIFTY "0 fires" above
  was a quiet-window fluke, not the design.

1. **OSPL Signal ⟷ SuperTrend(10,2) — direction-locked (the core).** Every red ↓ sell printed while the
   SuperTrend line was **RED** (above price); every green ↑ buy while it was **GREEN** (below price). The
   **first** signal of a leg ≈ the bar SuperTrend flips; **subsequent same-direction signals are
   adds/re-entries** down/up the same leg — the May 19–20 decline stacked ~5 red sells with Void Lines
   stair-stepping **23349 → 23309 → 23283 → 23273**, all under one red ST run. The `Void Line` sits right
   at/just inside the SuperTrend trailing band → **Void ≈ the SuperTrend stop**. Since the legend params
   ARE (10,2), OSPL Signal ≈ **SuperTrend(10,2) direction + a structure-stop overlay**. Strongest link.
2. **OSPL Signal ⟷ Parabolic SAR — flips coincide at leg starts.** SAR flips below price at buy reversals
   / above price at sell reversals, on the same bars (±1) as the first signal of each leg; the SAR dot
   trail rides opposite price ≈ where the Void Line sits. SAR and ST flip near-together → largely
   redundant with #1 (both are the trend/stop leg).
3. **OSPL Signal ⟷ Bollinger Bands(20,2) — sell-the-rip, buy-the-washout.** In the downtrend price **rode
   the lower band**; red ↓ sells fired on **bounces back toward the basis/upper band** (sell the rally to
   the mean), then price slid to the lower band again. The big green ↑ buy (**Gen 23365.4 / Void 23285**)
   fired at a **lower-band washout reversal** where the bands **ballooned** (vol expansion) at the May 20
   capitulation, then price reclaimed toward the basis. Basis(SMA20) = the mean signals fade from / revert
   toward. Same band behaviour as Qwik (point 5 above); BB has no proprietary OSPL counterpart →
   confluence, not equivalence.
4. **OSPL Signal ⟷ OSPL Volume — turns get volume, adds don't.** The high-conviction reversals carried
   OSPL-Volume spikes: a **red climax bar** at the start of the sell cluster and a **green spike** at the
   buy reversal, with the volume-bar hue (prev-close rule) matching signal direction at those turns.
   Mid-leg add signals fired on ordinary volume. No saturated >125K "dark bars" were unambiguously visible
   at the zoom used (NIFTY threshold ~125K) → treat the dark-bar tie-in as plausible-not-proven.
5. **OSPL Signal ⟷ EMA(200) — NOT a gate (bar-verified 2026-06-18).** Isolated live (OSPL Signal + EMA 200
   only, all else hidden) on NIFTY-I 3m. EMA200 does **NOT** filter signal direction: in a window where the
   200-line sat **above all price** (24038→23400, price 23050–23260), OSPL Signal still fired **green buys
   deep below it** — Gen 23260.6/Void 23153 and Gen 23183.1/Void 23133 — i.e. counter-trend reversal longs
   far under the 200 EMA, alongside reds (Gen 23120/23104.9/23005.2) also below it. On the right side, as
   price climbed above the rising EMA, greens fired above it (Gen 23354.4, 23571.2, 23485). So **greens
   appear both above AND far below the 200 EMA** → direction follows the **SuperTrend(10,2) flip, not
   price-vs-EMA200**. The 200 EMA is independent macro context, not a trend filter. (This corrects the prior
   "inferred trend-filter / EMA200 macro bias" reading, which was characterised from price-vs-MA structure
   before clean isolation was possible.)
   - **VWMA(20) / VWAP — still inferred** (not yet isolated; the legend hide-toggle drift that blocked these
     before persists). Characterised only from price-vs-MA structure: price tracked the fast MAs through the
     down-leg. SuperTrend(10,2) already encapsulates the fast-MA momentum role, so the MA stack adds little
     beyond #1.

**Net (OSPL Signal):** OSPL Signal ≈ **SuperTrend(10,2) direction + a structure-stop (Void/Void Line) +
volume-confirmed entries**, with BB / SAR / the MA stack as agreeing confluence. It is **not** the rare
In/Out study the manual implies — live it is a frequent, SuperTrend-locked Gen/Void scalp signal, the
**same marker family as Qwik scalp** (Qwik = the faster, ST-*leading* sibling; Signal = the
ST-*coincident* one). The two are counterparts of one engine — correlating them to each other is
circular, and both collapse to a SuperTrend(10,2) core once the proprietary timing is stripped.

---

## Script 1 — `OSPL - Siva Sir.txt` (base, as-is)

```pine
//@version=4
study(title="Vwap and Super Trend by Options Scalping", shorttitle="OSPL", overlay=true)
ha_t = syminfo.ticker
// === VWAP ===
_isVWAP           = input(true, "─────── Enable VWAP ─────")
src = input(title = "Source", type = input.source, defval = hlc3)
t = time("D")
start = na(t[1]) or t > t[1]

sumSrc = src * volume
sumVol = volume
sumSrc := start ? sumSrc : sumSrc + sumSrc[1]
sumVol := start ? sumVol : sumVol + sumVol[1]

// You can use built-in vwap() function instead.

plot(_isVWAP?sumSrc / sumVol:na, title="VWAP", color=color.red,linewidth=2)


// === SuperTrend ===
_isSuperTrend           = input(true, "─────── Enable SuperTrend ─────")
Factor=input(2, minval=1,maxval = 100)
Pd=input(10, minval=1,maxval = 100)


Up=hl2-(Factor*atr(Pd))
Dn=hl2+(Factor*atr(Pd))

Trend=0.0
TrendUp=0.0
TrendDown=0.0
TrendUp:=close[1]>TrendUp[1]? max(Up,TrendUp[1]) : Up
TrendDown:=close[1]<TrendDown[1]? min(Dn,TrendDown[1]) : Dn

Trend := close > TrendDown[1] ? 1: close< TrendUp[1]? -1: nz(Trend[1],1)
Tsl = Trend==1? TrendUp: TrendDown

linecolor = Trend == 1 ? color.green : color.red

plot(_isSuperTrend?Tsl:na, color = linecolor , style = plot.style_line , linewidth = 2, transp=1, title = "SuperTrend")


// === Parabolic SAR ===
_isPSAR                     = input(true, "──── Enable Parabolic SAR ─────")

start1 = input(0.02)
increment = input(0.02)
maximum = input(0.2)
out = sar(start1, increment, maximum)
plot(_isPSAR?out:na,title="PSAR", style=plot.style_cross, color=color.black)

// === 20 VWMA ===
_isVWMA                     = input(true, "──── Enable 20 VWMA ─────")
plot(_isVWMA?vwma(close,20):na,title="VWMA", style=plot.style_line, color=color.blue)



// === Strong Volume ===
ShowHighVolume = input(true, "──── Enable High Volume Indicator ─────")
Averageval= input(title="Average Volume: (in K)", type=input.integer,defval=50, minval=1)
Averageval := Averageval * 1000
varstrong = ShowHighVolume ? volume > Averageval : false
plotshape(varstrong,style=shape.square, location=location.bottom, color=color.blue)
```

---

## Script 2 — `OSPL-Modified with BB.txt` (+ EMA + Bollinger Bands, as-is)

```pine
//@version=4
study(title="Vwap and Super Trend by Options Scalping", shorttitle="OSPL", overlay=true)
ha_t = syminfo.ticker
// === VWAP ===
_isVWAP           = input(true, "─────── Enable VWAP ─────")
src = input(title = "Source", type = input.source, defval = hlc3)
t = time("D")
start = na(t[1]) or t > t[1]

sumSrc = src * volume
sumVol = volume
sumSrc := start ? sumSrc : sumSrc + sumSrc[1]
sumVol := start ? sumVol : sumVol + sumVol[1]


// You can use built-in vwap() function instead.

plot(_isVWAP?sumSrc / sumVol:na, title="VWAP", color=color.orange,linewidth=2)

//EMA
PlotEMA = input(title = "Plot EMA?", type=input.bool, defval=true)
EMALength = input(title="EMA Length", type=input.integer, defval=200)
EMASource = input(title="EMA Source", type=input.source, defval=close)
EMAvg = ema (EMASource, EMALength)
plot(PlotEMA ? EMAvg : na,  color= color.fuchsia, title="EMA")

// === SuperTrend ===
_isSuperTrend           = input(true, "─────── Enable SuperTrend ─────")
Factor=input(2, minval=1,maxval = 100)
Pd=input(10, minval=1,maxval = 100)


Up=hl2-(Factor*atr(Pd))
Dn=hl2+(Factor*atr(Pd))

Trend=0.0
TrendUp=0.0
TrendDown=0.0
TrendUp:=close[1]>TrendUp[1]? max(Up,TrendUp[1]) : Up
TrendDown:=close[1]<TrendDown[1]? min(Dn,TrendDown[1]) : Dn

Trend := close > TrendDown[1] ? 1: close< TrendUp[1]? -1: nz(Trend[1],1)
Tsl = Trend==1? TrendUp: TrendDown

linecolor = Trend == 1 ? color.green : color.red

plot(_isSuperTrend?Tsl:na, color = linecolor , style = plot.style_line , linewidth = 2, transp=1, title = "SuperTrend")


// === Parabolic SAR ===
_isPSAR                     = input(true, "──── Enable Parabolic SAR ─────")

start1 = input(0.02)
increment = input(0.02)
maximum = input(0.2)
out = sar(start1, increment, maximum)
plot(_isPSAR?out:na,title="PSAR", style=plot.style_cross, color=color.black)

// === 20 VWMA ===
_isVWMA                     = input(true, "──── Enable 20 VWMA ─────")
plot(_isVWMA?vwma(close,20):na,title="VWMA", style=plot.style_line, color=color.blue)



// === Strong Volume ===
ShowHighVolume = input(true, "──── Enable High Volume Indicator ─────")
Averageval= input(title="Average Volume: (in K)", type=input.integer,defval=50, minval=1)
Averageval := Averageval * 1000
varstrong = ShowHighVolume ? volume > Averageval : false
plotshape(varstrong,style=shape.square, location=location.bottom, color=color.blue)

///////////// Bollinger Bands

length = input(20, minval=1)
src1 = input(close, title="Source")
mult = input(2.0, minval=0.001, maxval=50, title="StdDev")
basis = sma(src1, length)
dev = mult * stdev(src, length)
upper = basis + dev
lower = basis - dev
offset = input(0, "Offset", type = input.integer, minval = -500, maxval = 500)
plot(basis, "Basis", color=color.yellow, offset = offset)
p1 = plot(upper, "Upper", color=color.yellow, offset = offset)
p2 = plot(lower, "Lower", color=color.yellow, offset = offset)
fill(p1, p2, title = "Background", color=#198787, transp=95)
```

---

## Script 3 — Fixed (ours; v2 with BB source bug corrected)

Identical to Script 2 **except** the Bollinger `dev` line now uses `src1` (close) instead of `src`
(hlc3) — the only change.

```pine
//@version=4
study(title="Vwap and Super Trend by Options Scalping", shorttitle="OSPL", overlay=true)
ha_t = syminfo.ticker
// === VWAP ===
_isVWAP           = input(true, "─────── Enable VWAP ─────")
src = input(title = "Source", type = input.source, defval = hlc3)
t = time("D")
start = na(t[1]) or t > t[1]

sumSrc = src * volume
sumVol = volume
sumSrc := start ? sumSrc : sumSrc + sumSrc[1]
sumVol := start ? sumVol : sumVol + sumVol[1]


// You can use built-in vwap() function instead.

plot(_isVWAP?sumSrc / sumVol:na, title="VWAP", color=color.orange,linewidth=2)

//EMA
PlotEMA = input(title = "Plot EMA?", type=input.bool, defval=true)
EMALength = input(title="EMA Length", type=input.integer, defval=200)
EMASource = input(title="EMA Source", type=input.source, defval=close)
EMAvg = ema (EMASource, EMALength)
plot(PlotEMA ? EMAvg : na,  color= color.fuchsia, title="EMA")

// === SuperTrend ===
_isSuperTrend           = input(true, "─────── Enable SuperTrend ─────")
Factor=input(2, minval=1,maxval = 100)
Pd=input(10, minval=1,maxval = 100)


Up=hl2-(Factor*atr(Pd))
Dn=hl2+(Factor*atr(Pd))

Trend=0.0
TrendUp=0.0
TrendDown=0.0
TrendUp:=close[1]>TrendUp[1]? max(Up,TrendUp[1]) : Up
TrendDown:=close[1]<TrendDown[1]? min(Dn,TrendDown[1]) : Dn

Trend := close > TrendDown[1] ? 1: close< TrendUp[1]? -1: nz(Trend[1],1)
Tsl = Trend==1? TrendUp: TrendDown

linecolor = Trend == 1 ? color.green : color.red

plot(_isSuperTrend?Tsl:na, color = linecolor , style = plot.style_line , linewidth = 2, transp=1, title = "SuperTrend")


// === Parabolic SAR ===
_isPSAR                     = input(true, "──── Enable Parabolic SAR ─────")

start1 = input(0.02)
increment = input(0.02)
maximum = input(0.2)
out = sar(start1, increment, maximum)
plot(_isPSAR?out:na,title="PSAR", style=plot.style_cross, color=color.black)

// === 20 VWMA ===
_isVWMA                     = input(true, "──── Enable 20 VWMA ─────")
plot(_isVWMA?vwma(close,20):na,title="VWMA", style=plot.style_line, color=color.blue)



// === Strong Volume ===
ShowHighVolume = input(true, "──── Enable High Volume Indicator ─────")
Averageval= input(title="Average Volume: (in K)", type=input.integer,defval=50, minval=1)
Averageval := Averageval * 1000
varstrong = ShowHighVolume ? volume > Averageval : false
plotshape(varstrong,style=shape.square, location=location.bottom, color=color.blue)

///////////// Bollinger Bands

length = input(20, minval=1)
src1 = input(close, title="Source")
mult = input(2.0, minval=0.001, maxval=50, title="StdDev")
basis = sma(src1, length)
dev = mult * stdev(src1, length)   // FIX: was stdev(src, ...) — BB now uses its own source (close)
upper = basis + dev
lower = basis - dev
offset = input(0, "Offset", type = input.integer, minval = -500, maxval = 500)
plot(basis, "Basis", color=color.yellow, offset = offset)
p1 = plot(upper, "Upper", color=color.yellow, offset = offset)
p2 = plot(lower, "Lower", color=color.yellow, offset = offset)
fill(p1, p2, title = "Background", color=#198787, transp=95)
```

## Replication notes (→ ArthaYantra)

- This confluence stack maps cleanly to our chart overlays: session-VWAP, SuperTrend(10,2),
  PSAR, VWMA(20), 200-EMA, BB(20,2) — all standard, all derivable from 1m/3m candles we already store.
- The **High-Volume flag** (`volume > 50K`) is the same threshold concept as oipulse's dark-bar; for
  NIFTY use ~125K, BankNifty/SENSEX ~50K (instrument-dependent — make it an input, as the script does).
- **SuperTrend(10,2)** is the shared core with the proprietary OSPL Signal — a reasonable v1 baseline
  for a "directional signal" feature; the proprietary Gen/Void scalp layer ([ospl-signal.md](ospl-signal.md))
  sits on top and would be fitted empirically.
