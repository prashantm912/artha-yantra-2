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
    String underlying, LocalTime istTime, Chart chart, Oi oi, Macro macro) {

  /** Chart dots from the engine {@code IndicatorBank} (already computed per bar). */
  public record Chart(
      BigDecimal close,
      BigDecimal vwap,
      BigDecimal vwma20,
      BigDecimal psar,
      int supertrendDir,
      BigDecimal rsi14,
      BigDecimal volume) {}

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
      BigDecimal spurtPricePct) {}

  /**
   * Macro confluence: ATM IV + rank, India VIX (level + direction), breadth, FII positioning, plus the
   * Phase-3.5 6-strike CE/PE IV averages (the mean IV over the 3 strikes above + 3 below the ATM) —
   * {@code null} when fewer than 6 usable strikes carry the needed IV.
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
      BigDecimal peIvAvg6) {}
}
