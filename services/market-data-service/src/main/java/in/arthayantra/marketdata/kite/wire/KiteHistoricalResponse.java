package in.arthayantra.marketdata.kite.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * {@code GET /instruments/historical/{token}/{interval}} envelope. Candles arrive as a positional
 * array per bar ({@code [timestamp, open, high, low, close, volume, oi?]}); the raw nodes are kept
 * here and mapped to {@link KiteCandle} by the gateway.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KiteHistoricalResponse(
    @JsonProperty("status") String status, @JsonProperty("data") Data data) {

  /** The {@code data} block — only {@code candles} is documented. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Data(@JsonProperty("candles") List<List<JsonNode>> candles) {}
}
