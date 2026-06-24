package in.arthayantra.strategysignal.execution.openalgo.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * One order row of OpenAlgo {@code POST /api/v1/orderbook} (docs.openalgo.in accounts-api/orderbook).
 * Mirrors every documented field. {@code price}/{@code trigger_price} are JSON numbers on the wire
 * ({@code 0} for a MARKET order); {@code order_status} is the normalized lifecycle string
 * (e.g. {@code complete}/{@code open}/{@code rejected}); {@code timestamp} is the broker's
 * human-readable order time (e.g. {@code 09-Dec-2024 09:44:09}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAlgoOrder(
    @JsonProperty("symbol") String symbol,
    @JsonProperty("exchange") String exchange,
    @JsonProperty("action") String action,
    @JsonProperty("quantity") BigDecimal quantity,
    @JsonProperty("price") BigDecimal price,
    @JsonProperty("trigger_price") BigDecimal triggerPrice,
    @JsonProperty("pricetype") String pricetype,
    @JsonProperty("product") String product,
    @JsonProperty("orderid") String orderid,
    @JsonProperty("order_status") String orderStatus,
    @JsonProperty("timestamp") String timestamp) {}
