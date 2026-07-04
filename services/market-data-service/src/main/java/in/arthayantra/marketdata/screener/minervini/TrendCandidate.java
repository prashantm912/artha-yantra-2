package in.arthayantra.marketdata.screener.minervini;

import java.math.BigDecimal;

/**
 * One Minervini Trend-Template candidate as of a screen date. Carries the raw daily-series numbers,
 * the 8 gate booleans (§4.2), the cross-sectional RS-rank, the liquidity turnover, the derived
 * Stage label (§4.1, MV-2.9), and the low-cap fundamentals inputs (NULL until the Upstox
 * fundamentals layer fills them — PR-B). {@code passesAll} is the AND of the 8 price gates.
 */
public record TrendCandidate(
    String symbol,
    String exchange,
    BigDecimal close,
    BigDecimal sma50,
    BigDecimal sma150,
    BigDecimal sma200,
    BigDecimal high52w,
    BigDecimal low52w,
    BigDecimal pctFromHigh,
    BigDecimal pctAboveLow,
    BigDecimal avgTurnover50,
    BigDecimal rsRaw,
    BigDecimal rsRank,
    boolean[] gates,
    int gatesPassed,
    boolean passesAll,
    Integer stage,
    BigDecimal freeFloatMcapCr,
    BigDecimal freeFloatPct) {

  /** Gate i (1-based) as stored (gates[0] = gate1). */
  public boolean gate(int i) {
    return gates[i - 1];
  }
}
