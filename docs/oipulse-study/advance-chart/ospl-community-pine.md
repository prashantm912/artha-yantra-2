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
