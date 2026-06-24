package in.arthayantra.strategysignal.execution.openalgo.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Envelope of OpenAlgo {@code POST /api/v1/funds}: {@code {"status":"success","data":{...}}}. The
 * single {@code data} object is one {@link OpenAlgoFunds} (not a list).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAlgoFundsResponse(
    @JsonProperty("status") String status, @JsonProperty("data") OpenAlgoFunds data) {}
