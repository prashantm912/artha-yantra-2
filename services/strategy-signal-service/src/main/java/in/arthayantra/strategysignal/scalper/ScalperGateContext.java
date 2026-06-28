package in.arthayantra.strategysignal.scalper;

import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * Immutable per-(instrument, bar) snapshot the scalper gates (§12.1) and the Connect-the-Dots scorer
 * (§12.3) read. Assembled once per bar from the engine {@code IndicatorBank} (chart dots) plus the
 * market-data OI/macro readers (via {@code MarketOiClient}). Deterministic given fixed reader outputs,
 * so the deterministic replay recomputes it byte-identically (the parity rule, §12.9).
 */
public record ScalperGateContext(
    String underlying, String signalIndex, LocalTime istTime, Chart chart, Oi oi, Macro macro) {

  // `underlying` = the index whose OI/macro this context carries (the oi-confluence index, 2c). The
  // `chart` (and its volume) come from the SIGNAL future, so the §0B volume-floor dot keys off
  // `signalIndex` (the signal future's index), not `underlying` — they differ for a decoupled SENSEX
  // scalper (signalIndex NIFTY 50 / oiIndex SENSEX). Single-index scalpers have them equal.

  /** Chart dots from the engine {@code IndicatorBank} (already computed per bar). */
  public record Chart(
      BigDecimal close,
      BigDecimal vwap,
      BigDecimal vwma20,
      BigDecimal psar,
      int supertrendDir,
      BigDecimal rsi14,
      BigDecimal volume,
      // E5 §3.2/§4.2: the higher-TF RSI reads (5m + daily) for the overbought/oversold caps; null when
      // the strategy declares no such indicator (read only behind bank.has(...)).
      BigDecimal rsi5m,
      BigDecimal rsiDaily) {

    /** Pre-higher-TF 7-arg form: {@code rsi5m}/{@code rsiDaily} default to null (keeps literals intact). */
    public Chart(
        BigDecimal close,
        BigDecimal vwap,
        BigDecimal vwma20,
        BigDecimal psar,
        int supertrendDir,
        BigDecimal rsi14,
        BigDecimal volume) {
      this(close, vwap, vwma20, psar, supertrendDir, rsi14, volume, null, null);
    }
  }

  /**
   * OI confluence from the market-data readers: 4-state quadrants, sentiment %, trending cross, basis,
   * plus the Phase-3.5 TEMPORAL derivations computed over the trending / sentiment series in {@code
   * MarketOiClient} (the scorer is pure/point-in-time, so all temporal math is pre-computed here):
   * signed CE/PE OI deltas over the window, the call/put delta imbalance %, whether the PE−CE tilt
   * crossed within the window and whether that gap is widening, the sentiment slope, and the spurt
   * OI/price magnitudes. Each temporal field is {@code null}/{@code false} when its series is short or
   * absent, so the scorer can never confirm a side on a missing derivation.
   */
  public record Oi(
      OiQuadrant underlying,
      OiQuadrant futures,
      BigDecimal sentimentPct,
      BigDecimal trendingPeMinusCePct,
      BigDecimal futuresBasis,
      BigDecimal ceOiDelta,
      BigDecimal peOiDelta,
      BigDecimal callPutDeltaImbalancePct,
      boolean crossedThisWindow,
      boolean gapWidening,
      BigDecimal sentimentSlope,
      BigDecimal spurtOiPct,
      BigDecimal spurtPricePct,
      // E2 M3: the PE−CE OI gap as a % of the latest bucket's total OI (the "lines diverge ~20-30%"
      // magnitude); null when the series is short/flat. The producer computes it in deriveTrending.
      BigDecimal oiDivergencePct) {

    /** Pre-M3 13-arg form: {@code oiDivergencePct} defaults to null (keeps existing literals intact). */
    public Oi(
        OiQuadrant underlying,
        OiQuadrant futures,
        BigDecimal sentimentPct,
        BigDecimal trendingPeMinusCePct,
        BigDecimal futuresBasis,
        BigDecimal ceOiDelta,
        BigDecimal peOiDelta,
        BigDecimal callPutDeltaImbalancePct,
        boolean crossedThisWindow,
        boolean gapWidening,
        BigDecimal sentimentSlope,
        BigDecimal spurtOiPct,
        BigDecimal spurtPricePct) {
      this(
          underlying, futures, sentimentPct, trendingPeMinusCePct, futuresBasis, ceOiDelta, peOiDelta,
          callPutDeltaImbalancePct, crossedThisWindow, gapWidening, sentimentSlope, spurtOiPct,
          spurtPricePct, null);
    }
  }

  /**
   * Macro confluence: ATM IV + rank, India VIX (level + direction), breadth, FII positioning, plus the
   * Phase-3.5 6-strike CE/PE IV averages (the mean IV over the 3 strikes above + 3 below the ATM) —
   * {@code null} when fewer than 6 usable strikes carry the needed IV — plus the E4 per-strike CE/PE IV
   * SLOPE (the peak-OI strike's IV direction over the active-strike window; rising = demand confirms).
   */
  public record Macro(
      BigDecimal atmIv,
      BigDecimal ivRank,
      BigDecimal vixLevel,
      Boolean vixRising,
      int advances,
      int declines,
      BigDecimal fiiLongPct,
      BigDecimal ceIvAvg6,
      BigDecimal peIvAvg6,
      // E3: the index heavyweights' net weighted % push (the /equity/index-contribution indexChangePct);
      // its SIGN is the constituent direction. null when bhavcopy/weights are unavailable.
      BigDecimal constituentBias,
      // E4 iv-per-strike: the signed CE/PE IV slope (last − first) of the peak-OI strike's IV over the
      // active-strike window; null when the series is short or the leg's IV is absent. A RISING slope on
      // the buy side confirms (a buyer paying up = demand); null never confirms.
      BigDecimal ceIvSlope,
      BigDecimal peIvSlope,
      // E7 §3.7/§6.7 (Hero-Zero): the per-side ATM premium skew = (CE ATM ltp − PE ATM ltp)/PE ltp × 100
      // (positive ⇒ CE is the richer/more-expensive side). null when the ATM premiums are absent; the dot
      // degrades to neutral on null.
      BigDecimal premiumSkewPct) {

    /** Pre-constituent 9-arg form: trailing macro fields default to null (keeps existing literals intact). */
    public Macro(
        BigDecimal atmIv,
        BigDecimal ivRank,
        BigDecimal vixLevel,
        Boolean vixRising,
        int advances,
        int declines,
        BigDecimal fiiLongPct,
        BigDecimal ceIvAvg6,
        BigDecimal peIvAvg6) {
      this(atmIv, ivRank, vixLevel, vixRising, advances, declines, fiiLongPct, ceIvAvg6, peIvAvg6, null,
          null, null, null);
    }

    /** Pre-iv-slope 10-arg form: {@code ceIvSlope}/{@code peIvSlope}/{@code premiumSkewPct} default to null. */
    public Macro(
        BigDecimal atmIv,
        BigDecimal ivRank,
        BigDecimal vixLevel,
        Boolean vixRising,
        int advances,
        int declines,
        BigDecimal fiiLongPct,
        BigDecimal ceIvAvg6,
        BigDecimal peIvAvg6,
        BigDecimal constituentBias) {
      this(atmIv, ivRank, vixLevel, vixRising, advances, declines, fiiLongPct, ceIvAvg6, peIvAvg6,
          constituentBias, null, null, null);
    }

    /** Pre-premium-skew 12-arg form: {@code premiumSkewPct} defaults to null. */
    public Macro(
        BigDecimal atmIv,
        BigDecimal ivRank,
        BigDecimal vixLevel,
        Boolean vixRising,
        int advances,
        int declines,
        BigDecimal fiiLongPct,
        BigDecimal ceIvAvg6,
        BigDecimal peIvAvg6,
        BigDecimal constituentBias,
        BigDecimal ceIvSlope,
        BigDecimal peIvSlope) {
      this(atmIv, ivRank, vixLevel, vixRising, advances, declines, fiiLongPct, ceIvAvg6, peIvAvg6,
          constituentBias, ceIvSlope, peIvSlope, null);
    }
  }
}
