package in.arthayantra.strategysignal.paper;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC for the nightly paper-ledger reconciliation (audit §8, V5 + V16). All reads are READ-ONLY over
 * the strategy-schema paper/signal tables ({@code paper_positions}, {@code paper_orders},
 * {@code signals}, {@code risk_settings}); the only write is the append-only run row on
 * {@code paper_reconciliation_runs}. Every window bound is an EXPLICIT instant (timestamptz), never a
 * {@code ::date = CURRENT_DATE} predicate (in-container {@code now()} is UTC — off-by-one across IST
 * midnight).
 *
 * <p><b>Positions ↔ orders join (V5).</b> There is no position-id FK on order rows. An order is tied
 * to a position by the §F.6 open key {@code (book, exchange, tradingsymbol, side)} — the exact join the
 * live paths use ({@link PaperPositionRepository#signalIdsFor}, {@code intradayOpen}, {@code
 * openForSignal}) — and TIME-SCOPED to the position's own lifetime {@code [opened_at, closed_at]} (the
 * V026 backfill's rule, so a re-opened key is attributed to the right lifetime). The ENTRY legs carry
 * the position's own side ({@code PaperService.openOrder}); the EXIT leg carries the OPPOSITE side and a
 * NULL {@code signal_id} ({@code PaperService.doSettle}). So per closed position: Σ(same-side leg qty)
 * must equal the position qty, and ≥ 1 opposite-side leg must exist.
 */
@Repository
public class PaperReconciliationRepository {

  /**
   * One CLOSED position's V5 reconciliation tallies: the summed ENTRY-leg qty and the EXIT-leg count,
   * both time-scoped to {@code [opened_at, closed_at]}, against the position's own qty.
   */
  public record ClosedPositionRecon(long positionId, long entryQty, long positionQty, long exitCount) {}

  private final JdbcTemplate jdbc;

  /** Wires the strategy datasource. */
  public PaperReconciliationRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Every CLOSED position closed within the window, with its ENTRY-leg qty sum and EXIT-leg count
   * (both time-scoped to the position lifetime, matched on the §F.6 open key). Only CLOSED positions
   * are checked — an OPEN position legitimately has no exit leg yet. The caller classifies each row
   * into the V5 discrepancy classes (missing-entry / qty-mismatch / missing-exit).
   */
  public List<ClosedPositionRecon> closedPositionReconciliation(OffsetDateTime from, OffsetDateTime to) {
    return jdbc.query(
        """
        SELECT p.id AS position_id,
               COALESCE(e.entry_qty, 0) AS entry_qty,
               p.qty                    AS position_qty,
               COALESCE(x.exit_count, 0) AS exit_count
        FROM paper_positions p
        LEFT JOIN LATERAL (
          SELECT COALESCE(SUM(o.qty), 0) AS entry_qty
          FROM paper_orders o
          WHERE o.book = p.book AND o.exchange = p.exchange AND o.tradingsymbol = p.tradingsymbol
            AND o.side = p.side
            AND COALESCE(o.filled_at, o.placed_at) BETWEEN p.opened_at AND p.closed_at
        ) e ON true
        LEFT JOIN LATERAL (
          SELECT COUNT(*) AS exit_count
          FROM paper_orders o
          WHERE o.book = p.book AND o.exchange = p.exchange AND o.tradingsymbol = p.tradingsymbol
            AND o.side <> p.side
            AND COALESCE(o.filled_at, o.placed_at) BETWEEN p.opened_at AND p.closed_at
        ) x ON true
        WHERE p.status = 'CLOSED' AND p.closed_at >= ? AND p.closed_at <= ?
        """,
        (rs, n) ->
            new ClosedPositionRecon(
                rs.getLong("position_id"),
                rs.getLong("entry_qty"),
                rs.getLong("position_qty"),
                rs.getLong("exit_count")),
        from,
        to);
  }

  /**
   * How many TAKEN signals generated within the window were EXPECTED to open a position — i.e. carried a
   * positive {@code suggested_qty} (the auto-paper path only takes a signal that has one;
   * {@link AutoPaperListener}). The denominator for the V16 take-side check.
   */
  public int takenSignalsExpectedToOpen(OffsetDateTime from, OffsetDateTime to) {
    Integer c =
        jdbc.queryForObject(
            """
            SELECT count(*) FROM signals s
            WHERE s.status = 'TAKEN'
              AND s.suggested_qty IS NOT NULL AND s.suggested_qty > 0
              AND s.generated_at >= ? AND s.generated_at <= ?
            """,
            Integer.class,
            from,
            to);
    return c == null ? 0 : c;
  }

  /**
   * V16 take-side orphans: TAKEN signals in the window that were expected to open a position
   * ({@code suggested_qty > 0}) yet have NO {@code paper_orders.signal_id} row — the auto-paper open
   * threw after the CAS take (the A1 residual). {@code suggested_qty > 0} excludes a deliberate manual
   * "take without qty" ack (never expected to open a position — {@code SignalsController.taken}).
   */
  public List<Long> takenSignalsWithoutOrder(OffsetDateTime from, OffsetDateTime to) {
    return jdbc.queryForList(
        """
        SELECT s.id FROM signals s
        WHERE s.status = 'TAKEN'
          AND s.suggested_qty IS NOT NULL AND s.suggested_qty > 0
          AND s.generated_at >= ? AND s.generated_at <= ?
          AND NOT EXISTS (SELECT 1 FROM paper_orders o WHERE o.signal_id = s.id)
        ORDER BY s.id
        """,
        Long.class,
        from,
        to);
  }

  /**
   * V16 position-side inverse: positions opened in the window on an AUTO-PAPER book (a book whose
   * {@code auto_paper_trade} risk toggle is ON — data-driven, not a hardcoded family list) carrying NO
   * {@code opening_signal_id}. On an automated book every open should trace to a signal; a NULL there is
   * a linkage gap. A {@code manual}/{@code other} book (toggle OFF) legitimately carries hand orders with
   * no signal linkage and is excluded by the join.
   */
  public List<Long> autoPaperPositionsWithoutSignal(OffsetDateTime from, OffsetDateTime to) {
    return jdbc.queryForList(
        """
        SELECT p.id FROM paper_positions p
        JOIN risk_settings rs
          ON rs.book = p.book AND rs.key = 'auto_paper_trade'
          AND COALESCE((rs.value->>'enabled')::boolean, false) = true
        WHERE p.opening_signal_id IS NULL
          AND p.opened_at >= ? AND p.opened_at <= ?
        ORDER BY p.id
        """,
        Long.class,
        from,
        to);
  }

  /** Appends the run row (audit output). Returns the generated id. */
  public long insertRun(
      OffsetDateTime windowStart,
      OffsetDateTime windowEnd,
      int positionsChecked,
      int takenSignalsChecked,
      int v5Discrepancies,
      int v16Discrepancies,
      String detailJson) {
    Long id =
        jdbc.queryForObject(
            """
            INSERT INTO paper_reconciliation_runs
              (window_start, window_end, positions_checked, taken_signals_checked,
               v5_discrepancies, v16_discrepancies, total_discrepancies, detail)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb) RETURNING id
            """,
            Long.class,
            windowStart,
            windowEnd,
            positionsChecked,
            takenSignalsChecked,
            v5Discrepancies,
            v16Discrepancies,
            v5Discrepancies + v16Discrepancies,
            detailJson);
    return id == null ? 0 : id;
  }
}
