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
    return insertFilled(
        book, signalId, exchange, tradingsymbol, side, qty, fillPrice, fillSimulator, slippageApplied,
        quoteBid, quoteAsk, clientOrderId, refSource, refTickAgeMs, signalGeneratedAt, ENTRY_LEG, null);
  }

  /**
   * {@code leg_kind} for an ENTRY fill. Every overload above this one delegates with it, which is a
   * FACT rather than a convention: {@code PaperService.doSettle} is the only exit writer in the
   * service and it calls the widest form directly with {@link #EXIT_LEG}. A future third writer must
   * pick explicitly — that is why the widest form takes the value instead of deriving it from
   * {@code settlesPositionId != null}, which would silently mislabel any new caller.
   */
  static final String ENTRY_LEG = "ENTRY";

  /** {@code leg_kind} for a SETTLE fill — {@code doSettle} only. */
  static final String EXIT_LEG = "EXIT";

  /**
   * The full form, adding the V059 exit linkage and leg discriminator. {@code settlesPositionId} is
   * the position this order closes, non-null on the settle path ONLY ({@code PaperService.doSettle},
   * which CAS-closes the position before inserting this row and therefore already holds the id).
   * Every ENTRY fill passes null — its order row is minted before the position exists, and its
   * per-signal attribution lives in {@code paper_position_lots} (V057) instead.
   *
   * <p>{@code legKind} is the separate, load-bearing half: it is what distinguishes a post-migration
   * ENTRY fill from a genuine pre-migration LEGACY row, since both carry a null link. Without it the
   * reconciler's fallback cannot tell them apart and a manual SELL entry keeps masking a BUY
   * position's missing exit — reachable today through {@code POST /api/v1/paper/orders}, which
   * accepts a caller-chosen side. It is a STORED FACT on purpose: the tempting inference "an order
   * with a {@code paper_position_lots} row is an entry" is unsound, because that lot write is
   * deliberately fail-soft, so a missing lot is an expected state rather than evidence.
   *
   * <p>Both values ride the INSERT rather than a follow-up statement, so they add no failure mode of
   * their own: unlike V057's lot write they cannot fail independently of the fill they describe, and
   * there is consequently no fail-soft/hard decision to take on the money-path close. The FK cannot
   * reject the link either — the referenced row was CAS-updated by this same transaction two
   * statements earlier.
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
      OffsetDateTime signalGeneratedAt,
      String legKind,
      Long settlesPositionId) {
    InsertedOrder inserted =
        jdbc.queryForObject(
            """
            INSERT INTO paper_orders
              (book, signal_id, exchange, tradingsymbol, side, qty, order_type, status, placed_at,
                filled_at, fill_price, fill_simulator, slippage_applied, quote_bid, quote_ask,
                client_order_id, ref_source, ref_tick_age_ms, leg_kind, settles_position_id,
                tick_to_fill_ms)
            VALUES (?,?,?,?,?,?, 'MARKET', 'FILLED', now(), now(), ?,?,?,?,?,?,?,?,?,?,
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
            legKind,
            settlesPositionId,
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

  /**
   * The STRATEGY that placed a {@code clientOrderId}'s order, via its signal (V058, option D) —
   * {@code empty} for a hand ticket with no signal linkage. Used to scope the idempotent-replay
   * read-back so a retry can never resolve to a co-firing sibling's position.
   */
  public Optional<java.util.UUID> strategyIdForClientOrderId(String book, String clientOrderId) {
    return jdbc
        .query(
            """
            SELECT sv.strategy_id
            FROM paper_orders o
            JOIN signals s ON s.id = o.signal_id
            JOIN strategy_versions sv ON sv.id = s.strategy_version_id
            WHERE o.book=? AND o.client_order_id=?
            LIMIT 1
            """,
            (rs, n) -> rs.getObject("strategy_id", java.util.UUID.class),
            book,
            clientOrderId)
        .stream()
        .filter(java.util.Objects::nonNull)
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
    return legsForPosition(book, exchange, tradingsymbol, openedAt, closedAt, null);
  }

  /**
   * The V058 scoped form (round-2 cross-vendor review Major 2). {@code strategyId} null keeps the
   * pre-V058 behaviour; non-null restricts the SIGNAL-CARRYING legs to that strategy's own.
   *
   * <p>This is a position-ATTRIBUTION query that lives entirely in {@code paper_orders} — which is
   * why the first V058 sweep missed it: that sweep enumerated SQL touching {@code paper_positions},
   * so a defect attributing orders to a position without naming the table was outside the search
   * space by construction. The unit is "queries that attribute rows to a position", not "queries
   * that touch paper_positions".
   *
   * <p>Armed, overlapping twins otherwise make {@code GET /api/v1/paper/positions/{id}} render the
   * SIBLING's entry fills as part of this position's trade chain — an externally observable
   * attribution error on exactly the co-fired input this feature enables.
   *
   * <p><b>V059 completes the settle half that V058 had to leave shared.</b> V058's note here said the
   * remainder was "blocked on PR #1259's V057 … add an exact linkage for exit orders once V057 is on
   * main" — that linkage now exists as {@code settles_position_id}, so a settle leg is rendered in
   * the trade chain of the position it actually closed and nowhere else. Passing {@code positionId}
   * null keeps the pre-V059 shape for any caller that has no id in hand.
   *
   * <p>The legacy fallback is deliberate and is why the filter reads "no link OR my link" rather than
   * "my link": every settle order written before V059 carries no link, and demanding one would blank
   * the exit row of every historical position's detail pane — trading a shared leg for a missing one.
   *
   * <p>⚠️ <b>STILL OPEN — the ENTRY half of V058's instruction, which V059 did NOT do.</b> V058's note
   * asked for two things: an exact linkage for exit orders (done above) and entry attribution read
   * "through {@code paper_position_lots.position_id}" (NOT done). The strategy filter below passes
   * {@code signal_id IS NULL} UNCONDITIONALLY, and a MANUAL hand ticket carries no signal — so on a
   * scoped book a manual sibling's ENTRY fill still renders inside this position's trade chain. That
   * is reachable today through {@code POST /api/v1/paper/orders}, the same surface that makes the
   * SELL-entry route live, and it is a DIFFERENT query from the reconciliation entry lateral this
   * PR's notes also list as open — do not read one as covering the other.
   *
   * <p>This pin is restored deliberately: V059 replaced V058's note with a "closes the settle half"
   * heading, which deleted the only record of the entry half while reading as completeness. The
   * remedy is the one V058 named — {@code paper_position_lots.position_id} — and it must be used as a
   * POSITIVE exclusion only (exclude an entry whose lot names ANOTHER position; keep one with no lot),
   * because the lot write is fail-soft and "no lot" is an expected state, never evidence.
   */
  public List<OrderRow> legsForPosition(
      String book,
      String exchange,
      String tradingsymbol,
      OffsetDateTime openedAt,
      OffsetDateTime closedAt,
      java.util.UUID strategyId) {
    return legsForPosition(book, exchange, tradingsymbol, openedAt, closedAt, strategyId, null);
  }

  /** The V059 form: additionally attributes LINKED settle legs to {@code positionId} alone. */
  public List<OrderRow> legsForPosition(
      String book,
      String exchange,
      String tradingsymbol,
      OffsetDateTime openedAt,
      OffsetDateTime closedAt,
      java.util.UUID strategyId,
      Long positionId) {
    return jdbc.query(
        """
        SELECT id, signal_id, exchange, tradingsymbol, side, qty, status, placed_at, filled_at,
               fill_price, fill_simulator, slippage_applied, quote_bid, quote_ask,
               ref_source, ref_tick_age_ms
        FROM paper_orders
        WHERE book=? AND exchange=? AND tradingsymbol=?
          AND COALESCE(filled_at, placed_at) >= ?
          AND COALESCE(filled_at, placed_at) <= COALESCE(?, now())
          AND (
            CAST(? AS uuid) IS NULL
            OR signal_id IS NULL
            OR EXISTS (
                 SELECT 1 FROM signals sg
                 JOIN strategy_versions sv ON sv.id = sg.strategy_version_id
                 WHERE sg.id = paper_orders.signal_id AND sv.strategy_id = CAST(? AS uuid))
          )
          -- V059: a settle leg that carries an exact link belongs to exactly ONE position, so a
          -- sibling's settle no longer appears in this position's trade chain. Unlinked rows (every
          -- pre-V059 order) still match on book + instrument + lifetime, so no historical detail pane
          -- loses its exit leg.
          AND (
            CAST(? AS bigint) IS NULL
            OR settles_position_id IS NULL
            OR settles_position_id = CAST(? AS bigint)
          )
        ORDER BY COALESCE(filled_at, placed_at) ASC, id ASC
        """,
        PaperOrderRepository::mapOrder,
        book,
        exchange,
        tradingsymbol,
        openedAt,
        closedAt,
        strategyId,
        strategyId,
        positionId,
        positionId);
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
