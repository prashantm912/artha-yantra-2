package in.arthayantra.strategysignal.paper;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * JDBC access to {@code paper_orders} — the simulated order log with the fill-audit columns sourced
 * from the engine JAR's {@code FillSimulator} (id, slippage_applied, quote bid/ask). Market orders
 * land FILLED in one step; the ledger view reads {@code placed_at DESC}.
 */
@Repository
public class PaperOrderRepository {

  private static final Logger log = LoggerFactory.getLogger(PaperOrderRepository.class);

  private record InsertedOrder(long id, Long tickToFillMs) {}

  /** One order row (fill-audit included, plus the P1-5 fill-reference provenance). */
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
      BigDecimal quoteAsk,
      String refSource,
      Long refTickAgeMs) {}

  /** The open-position key of the order carrying a clientOrderId — the V2 idempotency read-back anchor. */
  public record OrderKey(String exchange, String tradingsymbol, String side) {}

  private final JdbcTemplate jdbc;
  private final Timer signalToFillTimer;

  /** Wires the strategy datasource. */
  public PaperOrderRepository(JdbcTemplate jdbc, MeterRegistry meterRegistry) {
    this.jdbc = jdbc;
    this.signalToFillTimer =
        Timer.builder("ay_signal_to_fill_seconds")
            .publishPercentiles(0.5, 0.95)
            .register(meterRegistry);
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

  /** The pre-P1-5 12-arg form (no fill-reference provenance): delegates with null ref_source/age. */
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
    return insertFilled(
        book, signalId, exchange, tradingsymbol, side, qty, fillPrice, fillSimulator, slippageApplied,
        quoteBid, quoteAsk, clientOrderId, null, null, null);
  }

  /**
   * Inserts a FILLED order in a book with its fill-audit trail + P1-5 fill-reference provenance
   * ({@code refSource} = CALLER | LIVE_TICK | SIGNAL_ENTRY; {@code refTickAgeMs} = the LIVE_TICK's
   * wall-clock age at fill, else null); returns the generated id. A non-null {@code clientOrderId} (the
   * MANUAL order path only, audit V2) is stamped on the row and constrained by the
   * {@code uq_paper_orders_client_order_id} partial-unique index — so a concurrent duplicate submission's
   * INSERT collides here (23505) and its whole fill transaction rolls back.
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
      String clientOrderId,
      String refSource,
      Long refTickAgeMs,
      OffsetDateTime signalGeneratedAt) {
    InsertedOrder inserted =
        jdbc.queryForObject(
            """
            INSERT INTO paper_orders
              (book, signal_id, exchange, tradingsymbol, side, qty, order_type, status, placed_at,
                filled_at, fill_price, fill_simulator, slippage_applied, quote_bid, quote_ask,
                client_order_id, ref_source, ref_tick_age_ms, tick_to_fill_ms)
            VALUES (?,?,?,?,?,?, 'MARKET', 'FILLED', now(), now(), ?,?,?,?,?,?,?,?,
                    (floor(extract(epoch FROM now()) * 1000)
                     - floor(extract(epoch FROM ?::timestamptz) * 1000))::bigint)
            RETURNING id, tick_to_fill_ms
            """,
            (rs, rowNum) -> new InsertedOrder(rs.getLong("id"), rs.getObject("tick_to_fill_ms", Long.class)),
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
            clientOrderId,
            refSource,
            refTickAgeMs,
            signalGeneratedAt);
    if (inserted != null && inserted.tickToFillMs() != null && inserted.tickToFillMs() >= 0) {
      recordSignalToFillAfterCommit(inserted.tickToFillMs());
    }
    return inserted == null ? 0 : inserted.id();
  }

  private void recordSignalToFillAfterCommit(long latencyMs) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        && TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              recordSignalToFill(latencyMs);
            }
          });
      return;
    }
    recordSignalToFill(latencyMs);
  }

  private void recordSignalToFill(long latencyMs) {
    try {
      signalToFillTimer.record(latencyMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    } catch (RuntimeException e) {
      log.warn("failed to record signal-to-fill metric: {}", e.toString());
    }
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
               fill_price, fill_simulator, slippage_applied, quote_bid, quote_ask,
               ref_source, ref_tick_age_ms
        FROM paper_orders ORDER BY placed_at DESC LIMIT ? OFFSET ?
        """,
        PaperOrderRepository::mapOrder,
        Math.min(Math.max(limit, 1), 500),
        Math.max(offset, 0));
  }

  /**
   * The order legs of ONE position: every fill for its {@code (book, exchange, tradingsymbol)} whose
   * fill time falls within the position's lifetime {@code [openedAt, coalesce(closedAt, now())]},
   * oldest-first. Captures the entry leg(s) — same side, including averaged adds — and the opposite-side
   * exit leg. Book- + time-scoped so a re-opened key attributes each leg to the right lifetime (the same
   * disambiguation the V026 opening-signal backfill uses). DISPLAY provenance for the detail pane (§6.4).
   */
  public List<OrderRow> legsForPosition(
      String book,
      String exchange,
      String tradingsymbol,
      OffsetDateTime openedAt,
      OffsetDateTime closedAt) {
    return jdbc.query(
        """
        SELECT id, signal_id, exchange, tradingsymbol, side, qty, status, placed_at, filled_at,
               fill_price, fill_simulator, slippage_applied, quote_bid, quote_ask,
               ref_source, ref_tick_age_ms
        FROM paper_orders
        WHERE book=? AND exchange=? AND tradingsymbol=?
          AND COALESCE(filled_at, placed_at) >= ?
          AND COALESCE(filled_at, placed_at) <= COALESCE(?, now())
        ORDER BY COALESCE(filled_at, placed_at) ASC, id ASC
        """,
        PaperOrderRepository::mapOrder,
        book,
        exchange,
        tradingsymbol,
        openedAt,
        closedAt);
  }

  private static OrderRow mapOrder(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
    return new OrderRow(
        rs.getLong("id"),
        rs.getObject("signal_id", Long.class),
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
        rs.getBigDecimal("quote_ask"),
        rs.getString("ref_source"),
        rs.getObject("ref_tick_age_ms", Long.class));
  }

  /** Wipes a book's order log ({@code book} null → all books; paper reset). */
  public int deleteAll(String book) {
    return jdbc.update("DELETE FROM paper_orders WHERE (?::text IS NULL OR book = ?)", book, book);
  }
}
