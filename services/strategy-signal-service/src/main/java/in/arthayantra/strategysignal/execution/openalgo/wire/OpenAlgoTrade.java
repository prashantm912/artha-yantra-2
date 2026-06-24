package in.arthayantra.strategysignal.execution.openalgo.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * One trade (fill) row of OpenAlgo {@code POST /api/v1/tradebook} (docs.openalgo.in
 * accounts-api/tradebook). Mirrors every documented field. {@code average_price} is the fill price
 * and {@code trade_value} the notional ({@code average_price} × {@code quantity}); both are JSON
 * numbers. {@code timestamp} is the broker's human-readable fill time.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAlgoTrade(
    @JsonProperty("symbol") String symbol,
    @JsonProperty("exchange") String exchange,
    @JsonProperty("action") String action,
    @JsonProperty("quantity") BigDecimal quantity,
    @JsonProperty("average_price") BigDecimal averagePrice,
    @JsonProperty("trade_value") BigDecimal tradeValue,
    @JsonProperty("product") String product,
    @JsonProperty("orderid") String orderid,
    @JsonProperty("timestamp") String timestamp) {}
