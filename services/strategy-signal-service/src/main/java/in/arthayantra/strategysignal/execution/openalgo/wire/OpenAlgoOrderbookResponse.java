package in.arthayantra.strategysignal.execution.openalgo.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Envelope of OpenAlgo {@code POST /api/v1/orderbook}:
 * {@code {"status":"success","data":{"orders":[...],"statistics":{...}}}}. The {@code statistics}
 * block (order-count rollups) is mirrored for the drift guard but the read surface only maps
 * {@code orders}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAlgoOrderbookResponse(
    @JsonProperty("status") String status, @JsonProperty("data") Data data) {

  /** The {@code data} block: the order list plus the count statistics. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Data(
      @JsonProperty("orders") List<OpenAlgoOrder> orders,
      @JsonProperty("statistics") Statistics statistics) {}

  /** Order-count rollups (mirror-only; not mapped to the read surface). */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Statistics(
      @JsonProperty("total_buy_orders") Integer totalBuyOrders,
      @JsonProperty("total_sell_orders") Integer totalSellOrders,
      @JsonProperty("total_completed_orders") Integer totalCompletedOrders,
      @JsonProperty("total_open_orders") Integer totalOpenOrders,
      @JsonProperty("total_rejected_orders") Integer totalRejectedOrders) {}
}
