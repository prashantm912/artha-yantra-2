package in.arthayantra.marketdata.options.analytics;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/**
 * One bucket of the intraday PCR-vs-price series (the OI-Statistics chart): the {@code pcr} (4 dp)
 * and the underlying {@code spot} at {@code time} (HH:mm IST). Shared by the native fold and the
 * Upstox source so {@code /pcr-series} returns a uniform shape regardless of {@code
 * source.optionanalytics}.
 */
public record PcrSeriesPoint(
    String time,
    @Schema(types = {"number", "null"}) BigDecimal pcr,
    @Schema(types = {"number", "null"}) BigDecimal spot) {}
