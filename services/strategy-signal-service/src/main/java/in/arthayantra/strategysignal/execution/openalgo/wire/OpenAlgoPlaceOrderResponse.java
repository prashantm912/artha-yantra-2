package in.arthayantra.strategysignal.execution.openalgo.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response of OpenAlgo {@code POST /api/v1/placeorder} (docs.openalgo.in orders-api/placeorder).
 * On success OpenAlgo returns {@code {"orderid":"240307000614705","status":"success"}}; on a
 * rejection / error it returns {@code {"status":"error","message":"..."}} (and a non-200 HTTP
 * status). Mirrors every documented field; {@code orderid} is absent on an error so it stays
 * nullable.
 *
 * <p>This is the WRITE leaf of the {@code openalgo/wire} account/order surface — the order-WRITE
 * path (master plan §17.3, gated behind {@code artha.scalper.execution=live}). Binds liberally
 * ({@code @JsonIgnoreProperties}) so an additive OpenAlgo field can never crash a placement.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAlgoPlaceOrderResponse(
    @JsonProperty("status") String status,
    @JsonProperty("orderid") String orderid,
    @JsonProperty("message") String message) {}
