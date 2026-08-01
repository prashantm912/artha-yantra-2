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
    @Schema(type = "string", types = {"string", "null"}) BigDecimal marketCapCr,
    @Schema(type = "string", types = {"string", "null"}) BigDecimal freeFloatMcapCr,
    @Schema(type = "string", types = {"string", "null"}) BigDecimal freeFloatPct,
    @Schema(type = "string", types = {"string", "null"}) BigDecimal promoterPct,
    @Schema(type = "string", types = {"string", "null"}) BigDecimal pe,
    @Schema(type = "string", types = {"string", "null"}) BigDecimal roe,
    @Schema(type = "string", types = {"string", "null"}) BigDecimal netProfitCr,
    @Schema(type = "string", types = {"string", "null"}) BigDecimal revenueCr,
    @Schema(type = "string", types = {"string", "null"}) BigDecimal revenuePrevCr,
    @Schema(types = {"string", "null"}) LocalDate asOf) {}
