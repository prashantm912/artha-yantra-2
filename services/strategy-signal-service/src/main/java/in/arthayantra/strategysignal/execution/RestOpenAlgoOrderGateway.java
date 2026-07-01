package in.arthayantra.strategysignal.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategysignal.execution.openalgo.wire.OpenAlgoPlaceOrderResponse;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * The OpenAlgo order-WRITE gateway — the live broker order path (master plan §17.3), the concrete
 * impl behind the {@link OrderGateway} port that {@link LiveOrderService} drives on the owner's
 * "Take". Maps a broker-agnostic {@link OrderRequest} to OpenAlgo's {@code POST /api/v1/placeorder}
 * payload and maps the {@code {"orderid","status"}} response back to an {@link OrderAck}, using the
 * same hand-rolled {@link RestClient} + typed-wire-DTO anti-corruption pattern as
 * {@link RestOpenAlgoOrderReadGateway} (parity with the {@code kite/wire} convention).
 *
 * <p><b>Dormant by default — defense in depth.</b> This bean is bound ONLY when
 * {@code artha.scalper.execution=live} (default {@code paper} ⇒ the {@link DisabledOrderGateway}
 * places nothing). Even then, {@link LiveOrderService} only places on the owner's deliberate Take
 * (never auto-fired), and the owner arms it behind the §17.3 latency gate. Every placement logs a
 * single explicit line.
 *
 * <p><b>Never propagates.</b> Per the {@link LiveOrderService} contract, a placement failure (5xx,
 * an error JSON, or a transport error) returns a {@code FAILED} {@link OrderAck} — it NEVER throws,
 * so a broker hiccup can never break signal emission. The §18.1 order-READ surface (orderbook /
 * positions / tradebook / funds) is delegated to a {@link RestOpenAlgoOrderReadGateway} so a live
 * write stack also surfaces reads through the one bean.
 */
public class RestOpenAlgoOrderGateway implements OrderGateway {

  private static final Logger log = LoggerFactory.getLogger(RestOpenAlgoOrderGateway.class);

  private final RestClient restClient;
  private final String apiKey;
  private final String strategy;
  private final ObjectMapper objectMapper;
  private final RestOpenAlgoOrderReadGateway reads;

  /**
   * Wires the appliance base URL + OpenAlgo API key (the appliance's own generated key) + the
   * {@code strategy} tag stamped on every order (OpenAlgo logs/routes by it). Reads delegate to a
   * read gateway over the SAME base URL + key.
   */
  public RestOpenAlgoOrderGateway(
      RestClient.Builder builder,
      String baseUrl,
      String apiKey,
      String strategy,
      ObjectMapper objectMapper) {
    this.restClient = builder.baseUrl(baseUrl).build();
    this.apiKey = apiKey;
    this.strategy = strategy;
    this.objectMapper = objectMapper;
    // clone(): reuse the AUTO-CONFIGURED builder (bounded HTTP, audit P0-4) without sharing
    // mutable builder state with the write client built above.
    this.reads = new RestOpenAlgoOrderReadGateway(builder.clone(), baseUrl, apiKey, objectMapper);
  }

  @Override
  public OrderAck place(OrderRequest request) {
    log.warn(
        "LIVE OpenAlgo order placement: {} {} x{} on {}:{} (strategy={}, product={})",
        request.side(), request.orderType(), request.qty(), request.exchange(),
        request.tradingsymbol(), strategy, request.product());
    String payload;
    try {
      payload = objectMapper.writeValueAsString(toPlaceOrderPayload(request));
    } catch (JsonProcessingException e) {
      log.error("OpenAlgo placeorder request serialize failed: {}", e.getMessage());
      return new OrderAck(null, "FAILED", "request serialize failed: " + e.getMessage());
    }
    try {
      OpenAlgoPlaceOrderResponse response =
          restClient
              .post()
              .uri("/api/v1/placeorder")
              .contentType(MediaType.APPLICATION_JSON)
              .body(payload)
              .retrieve()
              .body(OpenAlgoPlaceOrderResponse.class);
      return toAck(response);
    } catch (Exception e) {
      // Never propagate — a broker/transport error must not break signal emission (§17.3).
      log.error(
          "OpenAlgo placeorder failed for {}:{} — {}",
          request.exchange(), request.tradingsymbol(), e.getMessage());
      return new OrderAck(null, "FAILED", "openalgo placeorder failed: " + e.getMessage());
    }
  }

  @Override
  public List<OrderbookEntry> orderbook() {
    return reads.orderbook();
  }

  @Override
  public List<PositionEntry> positions() {
    return reads.positions();
  }

  @Override
  public List<TradebookEntry> tradebook() {
    return reads.tradebook();
  }

  @Override
  public Funds funds() {
    return reads.funds();
  }

  /**
   * The OpenAlgo {@code /api/v1/placeorder} payload (docs.openalgo.in orders-api/placeorder). Field
   * names + defaults mirror the documented schema: {@code action} is the leg side (BUY/SELL),
   * {@code pricetype} the order type (MARKET/LIMIT), {@code price} only meaningful for LIMIT (0 for
   * MARKET). A {@code LinkedHashMap} keeps a stable, readable wire order; {@code price} is sent as a
   * plain string to match the documented sample.
   */
  private Map<String, Object> toPlaceOrderPayload(OrderRequest request) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("apikey", apiKey);
    payload.put("strategy", strategy);
    payload.put("exchange", request.exchange());
    payload.put("symbol", request.tradingsymbol());
    payload.put("action", request.side());
    payload.put("product", request.product());
    payload.put("pricetype", request.orderType());
    payload.put("quantity", String.valueOf(request.qty()));
    BigDecimal price = request.price() == null ? BigDecimal.ZERO : request.price();
    payload.put("price", price.toPlainString());
    return payload;
  }

  private static OrderAck toAck(OpenAlgoPlaceOrderResponse response) {
    if (response == null) {
      return new OrderAck(null, "FAILED", "openalgo placeorder returned no body");
    }
    boolean ok = "success".equalsIgnoreCase(response.status());
    String status = ok ? "OPEN" : "FAILED";
    return new OrderAck(response.orderid(), status, response.message());
  }
}
