# oipulse external anchor — 2026-07-06 ~10:44 IST (live)

Captured from the owner's authenticated oipulse tab for AY-vs-oipulse parity cross-checks
(the cluster agents can't reach oipulse — this is the external reference the compile step folds in).

## Headline (Connecting Dots ticker)
- **NIFTY(F): 24459.60  +106.90 (+0.44%)**
- BANKNIFTY(F): 58506.20 +285.20 (+0.49%)
- RELIANCE(F): 1315.10 +5.50 (+0.42%) · HDFCBANK(F): 825.45 +20.25 (+2.51%) · INFY(F): 1038.40 −7.40 (−0.71%)
- TCS(F): 2055.00 −24.20 (−1.16%) · ICICIBANK(F): 1430.90 +15.70 · KOTAKBANK(F): 383.60 −15.00 (−3.76%) · AXISBANK(F): 1352.40 +3.80

## NIFTY Connecting-Dots 3-min TREND sequence (today, most-recent first) — cross-ref AY /features/connecting-dots
10:42 Bullish · 10:39 Bullish · 10:36 Bearish · 10:33 Bullish · 10:30 Bearish · 10:27 Bullish ·
10:24 Bearish · 10:21 **Ext.Bullish** · 10:18 Bullish · 10:15 Bullish · 10:12 Bearish · 10:09 Bullish ·
10:06 Bearish · 10:03 Bearish · 10:00 Bearish · 09:57 Bullish · 09:54 Bullish · 09:51 Bearish ·
09:48 Bearish · 09:45 Bullish · 09:42 **Ext.Bullish** · 09:39 Bearish · 09:36 Bearish · 09:33 Bullish ·
09:30 **Ext.Bullish**  (30 rows total today)

## Cross-check targets
- AY futures/OI pages' NIFTY front-future price should be ~24459 (the live dated front, NOT NIFTY-FUT-CONT).
- AY /features/connecting-dots NIFTY 3-min trend verdicts should broadly track this sequence
  (known permanent divergences per [[oipulse-live-qa-method]] — BSE OI dissemination lag, our
  end-of-window values are fresher; Dow/IV NEUTRAL on derived history).
- The individual dot columns (Dow/Vix/Volume/ActiveStrikeIV/ActiveStrikeOI/OIInter/VWAP/Supertrend/
  RSI) render as colour cells on oipulse (not text-extractable) — a deeper per-dot compare needs a
  visual pass; the 13:05 scheduled #512 task does the sentiment/level reconcile.
