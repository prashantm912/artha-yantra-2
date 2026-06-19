package in.arthayantra.marketdata.openalgo.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

/**
 * Envelope of OpenAlgo {@code POST /api/v1/optionchain}: the underlying context plus the per-strike
 * {@link OpenAlgoChainRow} list (each carrying CE/PE {@code oi}). {@code expiry_date} is OpenAlgo's
 * {@code DDMMMYY} string.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAlgoOptionChainResponse(
    @JsonProperty("status") String status,
    @JsonProperty("underlying") String underlying,
    @JsonProperty("underlying_ltp") BigDecimal underlyingLtp,
    @JsonProperty("underlying_prev_close") BigDecimal underlyingPrevClose,
    @JsonProperty("expiry_date") String expiryDate,
    @JsonProperty("atm_strike") BigDecimal atmStrike,
    @JsonProperty("quotes_included") Boolean quotesIncluded,
    @JsonProperty("chain") List<OpenAlgoChainRow> chain) {}
