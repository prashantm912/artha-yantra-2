package in.arthayantra.strategysignal.execution.openalgo.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * One net-position row of OpenAlgo {@code POST /api/v1/positionbook} (docs.openalgo.in
 * accounts-api/positionbook). Mirrors every documented field. {@code quantity} is the SIGNED net qty
 * (negative = short, {@code 0} = flat); {@code average_price} is a STRING decimal on the wire
 * (e.g. {@code "0.00"}). {@code ltp} and {@code pnl} are present in the broker-mapped/normalized
 * shape (mirrored so the read surface can carry mark-to-market) though omitted from the bare
 * positionbook doc sample.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAlgoPosition(
    @JsonProperty("symbol") String symbol,
    @JsonProperty("exchange") String exchange,
    @JsonProperty("product") String product,
    @JsonProperty("quantity") BigDecimal quantity,
    @JsonProperty("average_price") BigDecimal averagePrice,
    @JsonProperty("ltp") BigDecimal ltp,
    @JsonProperty("pnl") BigDecimal pnl) {}
