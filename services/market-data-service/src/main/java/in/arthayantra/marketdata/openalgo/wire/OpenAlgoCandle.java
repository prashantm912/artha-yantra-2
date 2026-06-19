package in.arthayantra.marketdata.openalgo.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * One OHLCV row of OpenAlgo {@code POST /api/v1/history} (verified against
 * {@code openalgo/services/history_service.py} + {@code broker/<plugin>/api/data.py}). The response is an
 * array of these objects. {@code timestamp} is Unix epoch SECONDS as a JSON integer (ns→s
 * server-side, then {@code DataFrame.to_dict("records")}). {@code oi} is ALWAYS present (forced to
 * {@code 0} server-side when absent; real for F&O).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAlgoCandle(
    @JsonProperty("timestamp") long timestamp,
    @JsonProperty("open") BigDecimal open,
    @JsonProperty("high") BigDecimal high,
    @JsonProperty("low") BigDecimal low,
    @JsonProperty("close") BigDecimal close,
    @JsonProperty("volume") long volume,
    @JsonProperty("oi") Long oi) {}
