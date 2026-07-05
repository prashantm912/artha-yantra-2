package in.arthayantra.strategysignal.paper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
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

  private final JdbcTemplate jdbc;

  /** Wires the strategy datasource. */
  public PaperOrderRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Inserts a FILLED order in a book with its fill-audit trail; returns the generated id. */
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
    Long id =
        jdbc.queryForObject(
            """
            INSERT INTO paper_orders
              (book, signal_id, exchange, tradingsymbol, side, qty, order_type, status, placed_at,
               filled_at, fill_price, fill_simulator, slippage_applied, quote_bid, quote_ask)
            VALUES (?,?,?,?,?,?, 'MARKET', 'FILLED', now(), now(), ?,?,?,?,?)
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
            quoteAsk);
    return id == null ? 0 : id;
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
