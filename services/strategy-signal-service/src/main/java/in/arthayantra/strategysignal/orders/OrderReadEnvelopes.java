package in.arthayantra.strategysignal.orders;

import in.arthayantra.strategysignal.execution.OrderGateway;
import java.util.List;

/**
 * The three §18.1 order-READ list envelopes, typed so the contract gate can see them (ledger D3
 * slice 1).
 *
 * <p>PURE RETYPINGS of {@code Map.of("items", …)}. The item types were ALREADY records on
 * {@link OrderGateway}, so the envelope was the only opaque part — converting it makes the whole
 * response enumerable instead of leaving an {@code array of object} behind. {@code funds()} in the
 * same controller was already typed and is the precedent these follow.
 *
 * <p>The source was {@code Map.of}, whose iteration order is unspecified, so no client could depend
 * on key order here; a record additionally makes it deterministic. Key NAME and value types — what
 * clients actually bind to — are unchanged.
 */
public final class OrderReadEnvelopes {

  private OrderReadEnvelopes() {}

  /** {@code GET /api/v1/orders/orderbook} — placed orders with their lifecycle status. */
  public record Orderbook(List<OrderGateway.OrderbookEntry> items) {}

  /** {@code GET /api/v1/orders/positions} — broker net positions with mark-to-market P&amp;L. */
  public record Positions(List<OrderGateway.PositionEntry> items) {}

  /** {@code GET /api/v1/orders/tradebook} — executed fills. */
  public record Tradebook(List<OrderGateway.TradebookEntry> items) {}
}
