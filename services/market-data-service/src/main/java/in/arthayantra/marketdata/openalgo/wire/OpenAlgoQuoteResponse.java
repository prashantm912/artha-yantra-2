package in.arthayantra.marketdata.openalgo.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Envelope of OpenAlgo {@code POST /api/v1/quotes}: {@code {"status":"success","data":{...}}}. The
 * request is single-symbol ({@code symbol}+{@code exchange} in the JSON body), so {@code data} is one
 * {@link OpenAlgoQuote}, not a keyed map (contrast Kite's batched quote map).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAlgoQuoteResponse(
    @JsonProperty("status") String status, @JsonProperty("data") OpenAlgoQuote data) {}
