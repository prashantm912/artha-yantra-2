package in.arthayantra.marketdata.kite.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * {@code GET /quote} envelope: {@code {status, data}} where {@code data} is keyed by
 * {@code "exchange:tradingsymbol"} (the instrument's canonical key).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KiteQuoteResponse(
    @JsonProperty("status") String status, @JsonProperty("data") Map<String, KiteQuote> data) {}
