package in.arthayantra.strategysignal.execution.openalgo.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Envelope of OpenAlgo {@code POST /api/v1/tradebook}:
 * {@code {"status":"success","data":[...]}}. Unlike orderbook, {@code data} is the trade ARRAY
 * directly (no {@code trades} key or statistics block).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAlgoTradebookResponse(
    @JsonProperty("status") String status, @JsonProperty("data") List<OpenAlgoTrade> data) {}
