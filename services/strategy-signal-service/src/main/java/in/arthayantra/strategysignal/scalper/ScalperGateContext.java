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

  /** OI confluence from the market-data readers: 4-state quadrants, sentiment %, trending cross, basis. */
  public record Oi(
      OiQuadrant underlying,
      OiQuadrant futures,
      BigDecimal sentimentPct,
      BigDecimal trendingPeMinusCePct,
      BigDecimal futuresBasis) {}

  /** Macro confluence: ATM IV + rank, India VIX (level + direction), breadth, FII positioning. */
  public record Macro(
      BigDecimal atmIv,
      BigDecimal ivRank,
      BigDecimal vixLevel,
      Boolean vixRising,
      int advances,
      int declines,
      BigDecimal fiiLongPct) {}
}
