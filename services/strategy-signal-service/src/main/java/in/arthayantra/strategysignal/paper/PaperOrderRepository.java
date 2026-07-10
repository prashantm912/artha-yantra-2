package in.arthayantra.strategysignal.paper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC access to {@code paper_orders} — the simulated order log with the fill-audit columns sourced
 * from the engine JAR's {@code FillSimulator} (id, slippage_applied, quote bid/ask). Market orders
 * land FILLED in one step; the ledger view reads {@code placed_at DESC}.
 */
@Repository
public class PaperOrderRepository {

  /** One order row (fill-audit included). */
  public record OrderRow(
      long id,
      Long signalId,
      String exchange,
      String tradingsymbol,
      String side,
      long qty,
      String status,
      OffsetDateTime placedAt,
      OffsetDateTime filledAt,
      BigDecimal fillPrice,
      String fillSimulator,
      BigDecimal slippageApplied,
      BigDecimal quoteBid,
      BigDecimal quoteAsk) {}

  /** The open-position key of the order carrying a clientOrderId — the V2 idempotency read-back anchor. */
  public record OrderKey(String exchange, String tradingsymbol, String side) {}

  private final JdbcTemplate jdbc;

  /** Wires the strategy datasource. */
  public PaperOrderRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Inserts a FILLED order with its fill-audit trail, no idempotency key (engine/taken/exit fills). */
  public long insertFilled(
      String book,
      Long signalId,
      String exchange,
      String tradingsymbol,
      String side,
      long qty,
      BigDecimal fillPrice,
      String fillSimulator,
      BigDecimal slippageApplied,
      BigDecimal quoteBid,
      BigDecimal quoteAsk) {
    return insertFilled(
        book, signalId, exchange, tradingsymbol, side, qty, fillPrice, fillSimulator, slippageApplied,
        quoteBid, quoteAsk, null);
  }

  /**
   * Inserts a FILLED order in a book with its fill-audit trail; returns the generated id. A non-null
   * {@code clientOrderId} (the MANUAL order path only, audit V2) is stamped on the row and constrained
   * by the {@code uq_paper_orders_client_order_id} partial-unique index — so a concurrent duplicate
   * submission's INSERT collides here (23505) and its whole fill transaction rolls back.
   */
  public long insertFilled(
      String book,
      Long signalId,
      String exchange,
      String tradingsymbol,
      String side,
      long qty,
      BigDecimal fillPrice,
      String fillSimulator,
      BigDecimal slippageApplied,
      BigDecimal quoteBid,
      BigDecimal quoteAsk,
      String clientOrderId) {
    Long id =
        jdbc.queryForObject(
            """
            INSERT INTO paper_orders
              (book, signal_id, exchange, tradingsymbol, side, qty, order_type, status, placed_at,
               filled_at, fill_price, fill_simulator, slippage_applied, quote_bid, quote_ask,
               client_order_id)
            VALUES (?,?,?,?,?,?, 'MARKET', 'FILLED', now(), now(), ?,?,?,?,?,?)
            RETURNING id
            """,
            Long.class,
            book,
            signalId,
            exchange,
            tradingsymbol,
            side,
            qty,
            fillPrice,
            fillSimulator,
            slippageApplied,
            quoteBid,
            quoteAsk,
            clientOrderId);
    return id == null ? 0 : id;
  }

  /**
   * The {@link OrderKey} of the FILLED order carrying this {@code clientOrderId} in a book, if any — the
   * V2 idempotency read-back anchor (a replay resolves its original position through this key).
   * Book-scoped because {@code client_order_id} uniqueness is per book.
   */
  public Optional<OrderKey> keyForClientOrderId(String book, String clientOrderId) {
    return jdbc
        .query(
            "SELECT exchange, tradingsymbol, side FROM paper_orders"
                + " WHERE book=? AND client_order_id=? LIMIT 1",
            (rs, n) ->
                new OrderKey(rs.getString("exchange"), rs.getString("tradingsymbol"), rs.getString("side")),
            book,
            clientOrderId)
        .stream()
        .findFirst();
  }

  /** Recent orders, newest first (the ledger view). */
  public List<OrderRow> recent(int limit, int offset) {
    return jdbc.query(
        """
        SELECT id, signal_id, exchange, tradingsymbol, side, qty, status, placed_at, filled_at,
               fill_price, fill_simulator, slippage_applied, quote_bid, quote_ask
        FROM paper_orders ORDER BY placed_at DESC LIMIT ? OFFSET ?
        """,
        (rs, n) ->
            new OrderRow(
                rs.getLong("id"),
                (Long) rs.getObject("signal_id"),
                rs.getString("exchange"),
                rs.getString("tradingsymbol"),
                rs.getString("side"),
                rs.getLong("qty"),
                rs.getString("status"),
                rs.getObject("placed_at", OffsetDateTime.class),
                rs.getObject("filled_at", OffsetDateTime.class),
                rs.getBigDecimal("fill_price"),
                rs.getString("fill_simulator"),
                rs.getBigDecimal("slippage_applied"),
                rs.getBigDecimal("quote_bid"),
                rs.getBigDecimal("quote_ask")),
        Math.min(Math.max(limit, 1), 500),
        Math.max(offset, 0));
  }

  /** Wipes a book's order log ({@code book} null → all books; paper reset). */
  public int deleteAll(String book) {
    return jdbc.update("DELETE FROM paper_orders WHERE (?::text IS NULL OR book = ?)", book, book);
  }
}
