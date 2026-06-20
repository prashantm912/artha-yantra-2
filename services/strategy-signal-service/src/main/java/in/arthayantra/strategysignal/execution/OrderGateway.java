package in.arthayantra.strategysignal.execution;

import java.math.BigDecimal;

/**
 * The broker-agnostic order-placement boundary (master plan §17.3). {@link LiveOrderService} calls
 * this port — never a broker gateway directly — so the broker stays swappable (Kite / OpenAlgo) and
 * no broker types leak into the engine. Read endpoints (orderbook/positions/funds, §18.1) are a later
 * Phase-4b deliverable and are deliberately NOT on this port yet.
 *
 * <p>The default wired implementation is {@link DisabledOrderGateway} — a fail-safe that places
 * nothing. A live broker gateway (OpenAlgo SDK, gated on §S3 + the §17.3 latency gate) replaces it
 * only when live execution is deliberately configured.
 */
public interface OrderGateway {

  /** Places one order and returns the broker's acknowledgement (mapped to a domain record). */
  OrderAck place(OrderRequest request);

  /** A broker-agnostic order request. {@code product} e.g. MIS (intraday); {@code orderType} MARKET/LIMIT. */
  record OrderRequest(
      String exchange,
      String tradingsymbol,
      String side,
      int qty,
      String product,
      String orderType,
      BigDecimal price) {}

  /** A broker-agnostic order acknowledgement. */
  record OrderAck(String orderId, String status, String message) {}
}
