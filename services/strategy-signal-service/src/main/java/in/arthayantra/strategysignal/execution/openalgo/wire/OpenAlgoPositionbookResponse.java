package in.arthayantra.strategysignal.execution.openalgo.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Envelope of OpenAlgo {@code POST /api/v1/positionbook}:
 * {@code {"status":"success","data":[...]}}. {@code data} is the position ARRAY directly.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAlgoPositionbookResponse(
    @JsonProperty("status") String status, @JsonProperty("data") List<OpenAlgoPosition> data) {}
