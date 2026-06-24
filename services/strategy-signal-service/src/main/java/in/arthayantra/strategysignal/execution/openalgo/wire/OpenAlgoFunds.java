package in.arthayantra.strategysignal.execution.openalgo.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * The {@code data} block of OpenAlgo {@code POST /api/v1/funds} (docs.openalgo.in
 * accounts-api/funds). Mirrors every documented field. ALL fields are STRING decimals on the wire
 * (e.g. {@code "18083.01"}); each maps to {@code BigDecimal}. {@code availablecash} is free margin,
 * {@code utiliseddebits} the margin in use, {@code collateral} pledged collateral, and
 * {@code m2mrealized}/{@code m2munrealized} the realized/unrealized mark-to-market.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAlgoFunds(
    @JsonProperty("availablecash") BigDecimal availableCash,
    @JsonProperty("collateral") BigDecimal collateral,
    @JsonProperty("m2mrealized") BigDecimal m2mRealized,
    @JsonProperty("m2munrealized") BigDecimal m2mUnrealized,
    @JsonProperty("utiliseddebits") BigDecimal utilisedDebits) {}
