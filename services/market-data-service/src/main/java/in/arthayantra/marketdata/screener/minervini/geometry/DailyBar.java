package in.arthayantra.marketdata.screener.minervini.geometry;

import java.time.LocalDate;

/**
 * One daily OHLCV bar of an equity's price series (MV-5.x geometry). Prices are plain {@code double}
 * — base geometry works in relative % terms where {@code double} precision is ample and avoids the
 * BigDecimal noise of thousands of window comparisons (the persisted screen keeps BigDecimal money).
 */
public record DailyBar(
    LocalDate date, double open, double high, double low, double close, long volume) {}
