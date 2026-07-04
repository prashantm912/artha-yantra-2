package in.arthayantra.marketdata.fundamentals;

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
    String isin,
    BigDecimal marketCapCr,
    BigDecimal freeFloatMcapCr,
    BigDecimal freeFloatPct,
    BigDecimal promoterPct,
    BigDecimal pe,
    BigDecimal roe,
    BigDecimal netProfitCr,
    BigDecimal revenueCr,
    BigDecimal revenuePrevCr,
    LocalDate asOf) {}
