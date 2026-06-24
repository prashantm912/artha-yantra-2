package in.arthayantra.strategysignal.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.strategysignal.execution.openalgo.wire.OpenAlgoFunds;
import in.arthayantra.strategysignal.execution.openalgo.wire.OpenAlgoFundsResponse;
import in.arthayantra.strategysignal.execution.openalgo.wire.OpenAlgoOrder;
import in.arthayantra.strategysignal.execution.openalgo.wire.OpenAlgoOrderbookResponse;
import in.arthayantra.strategysignal.execution.openalgo.wire.OpenAlgoPosition;
import in.arthayantra.strategysignal.execution.openalgo.wire.OpenAlgoPositionbookResponse;
import in.arthayantra.strategysignal.execution.openalgo.wire.OpenAlgoTrade;
import in.arthayantra.strategysignal.execution.openalgo.wire.OpenAlgoTradebookResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * The OpenAlgo order-READ arm of the {@link OrderGateway} port (§18.1 — the documented Phase-4b
 * read deliverable). Each read is a single-symbol-free {@code POST} with just {@code {"apikey":...}}
 * in the body (orderbook / positionbook / tradebook / funds), the same hand-rolled
 * {@link RestClient} + typed-wire-DTO anti-corruption pattern market-data uses for OpenAlgo data
 * reads — NOT the SDK (parity with the {@code kite/wire} convention). The wire DTOs
 * ({@code openalgo/wire}) bind liberally ({@code @JsonIgnoreProperties}) so an additive OpenAlgo
 * field can never crash a read; this gateway maps them to the broker-agnostic
 * {@link OrderGateway} domain records.
 *
 * <p><b>Read-only.</b> {@link #place} stays disabled — wiring the read gateway must NEVER enable the
 * write path. A placed order falls through to the same REJECTED ack the {@link DisabledOrderGateway}
 * returns (the live WRITE gateway is a separate, deliberately-later deliverable).
 */
public class RestOpenAlgoOrderReadGateway implements OrderGateway {

  private static final Logger log = LoggerFactory.getLogger(RestOpenAlgoOrderReadGateway.class);

  private final RestClient restClient;
  private final String apiKey;
  private final ObjectMapper objectMapper;

  /** Wires the appliance base URL + OpenAlgo API key (the appliance's own generated key). */
  public RestOpenAlgoOrderReadGateway(
      RestClient.Builder builder, String baseUrl, String apiKey, ObjectMapper objectMapper) {
    this.restClient = builder.baseUrl(baseUrl).build();
    this.apiKey = apiKey;
    this.objectMapper = objectMapper;
  }

  @Override
  public OrderAck place(OrderRequest request) {
    // Read gateway never writes — the write path is a separate, deliberately-later deliverable.
    log.warn(
        "OpenAlgo order-read gateway is read-only — order NOT placed: {} {} x{} on {}:{}",
        request.side(), request.orderType(), request.qty(), request.exchange(), request.tradingsymbol());
    return new OrderAck(null, "REJECTED", "order-read gateway is read-only — write path not wired");
  }

  @Override
  public List<OrderbookEntry> orderbook() {
    OpenAlgoOrderbookResponse response = read("/api/v1/orderbook", OpenAlgoOrderbookResponse.class);
    if (response.data() == null || response.data().orders() == null) {
      return List.of();
    }
    return response.data().orders().stream().map(RestOpenAlgoOrderReadGateway::toOrderbookEntry).toList();
  }

  @Override
  public List<PositionEntry> positions() {
    OpenAlgoPositionbookResponse response =
        read("/api/v1/positionbook", OpenAlgoPositionbookResponse.class);
    if (response.data() == null) {
      return List.of();
    }
    return response.data().stream().map(RestOpenAlgoOrderReadGateway::toPositionEntry).toList();
  }

  @Override
  public List<TradebookEntry> tradebook() {
    OpenAlgoTradebookResponse response = read("/api/v1/tradebook", OpenAlgoTradebookResponse.class);
    if (response.data() == null) {
      return List.of();
    }
    return response.data().stream().map(RestOpenAlgoOrderReadGateway::toTradebookEntry).toList();
  }

  @Override
  public Funds funds() {
    OpenAlgoFundsResponse response = read("/api/v1/funds", OpenAlgoFundsResponse.class);
    OpenAlgoFunds f = response.data();
    if (f == null) {
      return Funds.notConfigured();
    }
    return new Funds(
        "OK", f.availableCash(), f.collateral(), f.m2mRealized(), f.m2mUnrealized(), f.utilisedDebits());
  }

  private <T> T read(String uri, Class<T> type) {
    String requestBody = body();
    try {
      return restClient
          .post()
          .uri(uri)
          .contentType(MediaType.APPLICATION_JSON)
          .body(requestBody)
          .retrieve()
          .body(type);
    } catch (Exception e) {
      throw new ApiException(
          502,
          ErrorCodes.OPENALGO_UPSTREAM_ERROR,
          "openalgo " + uri + " read failed: " + e.getMessage());
    }
  }

  private String body() {
    try {
      return objectMapper.writeValueAsString(Map.of("apikey", apiKey));
    } catch (JsonProcessingException e) {
      throw new ApiException(
          500, ErrorCodes.INTERNAL_ERROR, "openalgo request serialize failed: " + e.getMessage());
    }
  }

  private static OrderbookEntry toOrderbookEntry(OpenAlgoOrder o) {
    return new OrderbookEntry(
        o.symbol(),
        o.exchange(),
        o.action(),
        o.quantity(),
        o.price(),
        o.triggerPrice(),
        o.pricetype(),
        o.product(),
        o.orderid(),
        o.orderStatus(),
        o.timestamp());
  }

  /** Net qty is signed on the wire; the BUY/SELL side is derived from its sign ({@code 0} ⇒ {@code FLAT}). */
  private static PositionEntry toPositionEntry(OpenAlgoPosition p) {
    BigDecimal qty = p.quantity();
    String side = qty == null || qty.signum() == 0 ? "FLAT" : qty.signum() > 0 ? "BUY" : "SELL";
    return new PositionEntry(
        p.symbol(), p.exchange(), side, qty, p.product(), p.averagePrice(), p.ltp(), p.pnl());
  }

  private static TradebookEntry toTradebookEntry(OpenAlgoTrade t) {
    return new TradebookEntry(
        t.symbol(),
        t.exchange(),
        t.action(),
        t.quantity(),
        t.averagePrice(),
        t.tradeValue(),
        t.product(),
        t.orderid(),
        t.timestamp());
  }
}
