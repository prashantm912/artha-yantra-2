package in.arthayantra.marketdata.options.analytics;

import java.math.BigDecimal;

/**
 * One bucket of the intraday PCR-vs-price series (the OI-Statistics chart): the {@code pcr} (4 dp)
 * and the underlying {@code spot} at {@code time} (HH:mm IST). Shared by the native fold and the
 * Upstox source so {@code /pcr-series} returns a uniform shape regardless of {@code
 * source.optionanalytics}.
 */
public record PcrSeriesPoint(String time, BigDecimal pcr, BigDecimal spot) {}
