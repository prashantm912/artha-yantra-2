package in.arthayantra.marketdata.screener.manas;

import java.math.BigDecimal;

/**
 * One Manas Arora selection candidate as of a screen date. Carries the raw daily-series numbers, the
 * 6 §4.1 gate booleans, the §1.2 within-25%-of-52w-high universe flag, the §4.3 liquidity numbers +
 * flags, the §4.1 gate-4 "preferably above 50-SMA" soft flag, and the (default-off) §4.4 low-cap
 * fundamentals inputs (NULL until the Upstox fundamentals feed fills them). {@code passesAll} is the
 * AND of the 6 selection gates + the within-high universe gate + both liquidity gates (and, when the
 * low-cap gate is enabled, the float gate too — applied by the screen service, not this record).
 */
public record ManasCandidate(
    String symbol,
    String exchange,
    BigDecimal close,
    BigDecimal sma50,
    BigDecimal sma200,
    BigDecimal high52w,
    BigDecimal low52w,
    BigDecimal avgVol20,
    BigDecimal avgVol50,
    BigDecimal turnover50,
    BigDecimal withinHighPct,
    BigDecimal aboveLowPct,
    boolean withinHigh,
    boolean aboveSma50,
    boolean liquidVolume,
    boolean liquidDepth,
    boolean lowCap,
    boolean[] gates,
    int gatesPassed,
    boolean passesAll,
    BigDecimal freeFloatMcapCr,
    BigDecimal freeFloatPct,
    // §4.1/§4.10 cross-sectional RS-rank (0..100 percentile of the weighted trailing relative strength
    // over the screened universe). Drives the funnel's RS-priority admission.
    BigDecimal rsRank) {

  /** Gate i (1-based) as stored (gates[0] = gate1). */
  public boolean gate(int i) {
    return gates[i - 1];
  }
}
