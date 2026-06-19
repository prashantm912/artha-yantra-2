package in.arthayantra.marketdata.openalgo.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Envelope of OpenAlgo {@code POST /api/v1/history}:
 * {@code {"status":"success","data":[{candle},...]}}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAlgoHistoryResponse(
    @JsonProperty("status") String status, @JsonProperty("data") List<OpenAlgoCandle> data) {}
