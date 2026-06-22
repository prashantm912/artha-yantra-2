package in.arthayantra.marketdata.upstox;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Full-mirror wire DTO for the Upstox Market-Information activity endpoints ({@code GET
 * /v2/market/fii}, {@code /v2/market/dii}) — the same anti-corruption pattern as {@code kite/wire}.
 * Both endpoints share this shape: a {@code data} map keyed by the requested {@code data_type}
 * segment to an array of per-interval activity records. Every documented field is mirrored and
 * {@code ignoreUnknown=true}, so a field Upstox ADDS can never crash the parse.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpstoxMarketActivity(
    @JsonProperty("status") String status,
    @JsonProperty("data") Map<String, List<Activity>> data) {

  /**
   * One interval's activity for a segment. Cash segments ({@code NSE_EQ|CASH}) carry only
   * buy/sell amounts (contracts/OI/long-short are zero); F&amp;O segments carry the full position
   * breakdown. Amounts are in INR.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Activity(
      @JsonProperty("time_stamp") long timeStamp,
      @JsonProperty("buy_amount") BigDecimal buyAmount,
      @JsonProperty("sell_amount") BigDecimal sellAmount,
      @JsonProperty("buy_contracts") long buyContracts,
      @JsonProperty("sell_contracts") long sellContracts,
      @JsonProperty("oi_contracts") long oiContracts,
      @JsonProperty("oi_amount") BigDecimal oiAmount,
      @JsonProperty("total_long_contracts") long totalLongContracts,
      @JsonProperty("total_short_contracts") long totalShortContracts,
      @JsonProperty("total_call_long_contracts") long totalCallLongContracts,
      @JsonProperty("total_put_long_contracts") long totalPutLongContracts,
      @JsonProperty("total_call_short_contracts") long totalCallShortContracts,
      @JsonProperty("total_put_short_contracts") long totalPutShortContracts) {}
}
