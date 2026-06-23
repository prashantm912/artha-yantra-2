package in.arthayantra.marketdata.upstox;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * Wire mirror of Upstox {@code GET /v2/expired-instruments/historical-candle/...} — the OHLCV+OI
 * series for one expired contract. Each candle is a positional array:
 * {@code [timestamp, open, high, low, close, volume, openInterest]} (index 6 = OI). Kept as raw
 * {@link JsonNode}s so the client reads the exact decimal text (never a lossy double) when mapping
 * to {@code NUMERIC} candle rows. {@code @JsonIgnoreProperties} per the kite/wire convention.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpstoxExpiredCandles(String status, Data data) {

  /** The {@code data} block — just the candle array-of-arrays. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Data(List<List<JsonNode>> candles) {}
}
