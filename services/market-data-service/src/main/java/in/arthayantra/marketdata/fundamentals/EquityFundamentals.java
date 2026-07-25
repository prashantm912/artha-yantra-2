package in.arthayantra.marketdata.fundamentals;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A per-stock fundamentals snapshot derived from the Upstox Company Fundamentals API (ADR-0004).
 * Feeds the Minervini low-cap universe gate (free-float market cap + free-float %) and the §4.8 ROE
 * read. {@code marketCapCr = pe × netProfitCr}; {@code freeFloatMcapCr = marketCapCr × freeFloatPct/100};
 * {@code freeFloatPct = 100 − promoterPct}. Nulls where Upstox lacks the input (treated as UNKNOWN,
 * never a silent exclusion).
 */
public record EquityFundamentals(
    String symbol,
    @Schema(types = {"string", "null"}) String isin,
    @Schema(types = {"number", "null"}) BigDecimal marketCapCr,
    @Schema(types = {"number", "null"}) BigDecimal freeFloatMcapCr,
    @Schema(types = {"number", "null"}) BigDecimal freeFloatPct,
    @Schema(types = {"number", "null"}) BigDecimal promoterPct,
    @Schema(types = {"number", "null"}) BigDecimal pe,
    @Schema(types = {"number", "null"}) BigDecimal roe,
    @Schema(types = {"number", "null"}) BigDecimal netProfitCr,
    @Schema(types = {"number", "null"}) BigDecimal revenueCr,
    @Schema(types = {"number", "null"}) BigDecimal revenuePrevCr,
    @Schema(types = {"string", "null"}) LocalDate asOf) {}
