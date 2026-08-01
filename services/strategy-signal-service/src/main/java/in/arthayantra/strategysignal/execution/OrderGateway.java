package in.arthayantra.strategysignal.execution;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

/**
 * The broker-agnostic order boundary (master plan §17.3). {@link LiveOrderService} calls this port —
 * never a broker gateway directly — so the broker stays swappable (Kite / OpenAlgo) and no broker
 * types leak into the engine.
 *
 * <p>The order-WRITE path ({@link #place}) stays gated: even with {@code artha.scalper.execution=live}
 * the default {@link DisabledOrderGateway} places nothing until a real write gateway is deliberately
 * wired. The order-READ surface (§18.1 — orderbook / positions / tradebook / funds, the documented
 * Phase-4b deliverable) is broker-agnostic too: the default {@link DisabledOrderGateway} returns
 * empty lists / a {@code NOT_CONFIGURED} funds row, and a live
 * {@link RestOpenAlgoOrderReadGateway} replaces it only when OpenAlgo order-read is configured.
 */
public interface OrderGateway {

  /** Places one order and returns the broker's acknowledgement (mapped to a domain record). */
  OrderAck place(OrderRequest request);

  /** The broker orderbook — every order placed today, newest as the broker returns them (§18.1). */
  List<OrderbookEntry> orderbook();

  /** The broker net positions with mark-to-market P&amp;L (§18.1). */
  List<PositionEntry> positions();

  /** The broker tradebook — the executed fills (§18.1). */
  List<TradebookEntry> tradebook();

  /** The broker funds/margin snapshot, or a {@code NOT_CONFIGURED} sentinel when unavailable (§18.1). */
  Funds funds();

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

  /** One orderbook row — a placed order with its lifecycle status. */
  record OrderbookEntry(
      String symbol,
      String exchange,
      String action,
      @Schema(type = "string") BigDecimal qty,
      @Schema(type = "string") BigDecimal price,
      @Schema(type = "string") BigDecimal triggerPrice,
      String pricetype,
      String product,
      String orderId,
      String status,
      String timestamp) {}

  /** One net-position row. {@code qty} is signed (negative = short); {@code side} is its derived BUY/SELL. */
  record PositionEntry(
      String symbol,
      String exchange,
      String side,
      @Schema(type = "string") BigDecimal qty,
      String product,
      @Schema(type = "string") BigDecimal avgPrice,
      @Schema(type = "string") BigDecimal ltp,
      @Schema(type = "string") BigDecimal mtmPnl) {}

  /** One tradebook (fill) row. */
  record TradebookEntry(
      String symbol,
      String exchange,
      String action,
      @Schema(type = "string") BigDecimal qty,
      @Schema(type = "string") BigDecimal price,
      @Schema(type = "string") BigDecimal tradeValue,
      String product,
      String orderId,
      String tradeTime) {}

  /** The funds/margin snapshot. {@code status} is {@code OK} or {@code NOT_CONFIGURED} (sentinel). */
  record Funds(
      String status,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal availableCash,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal collateral,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal m2mRealized,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal m2mUnrealized,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal utilisedDebits) {

    /** The fail-safe row a disabled gateway returns — all-null amounts, {@code NOT_CONFIGURED}. */
    public static Funds notConfigured() {
      return new Funds("NOT_CONFIGURED", null, null, null, null, null);
    }
  }
}
