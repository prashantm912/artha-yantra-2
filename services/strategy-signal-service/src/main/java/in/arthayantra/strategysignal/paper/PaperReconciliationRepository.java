package in.arthayantra.strategysignal.paper;

import java.sql.Array;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC for the nightly paper-ledger reconciliation (audit §8, V5 + V16). All reads are READ-ONLY over
 * the strategy-schema paper/signal tables ({@code paper_positions}, {@code paper_orders},
 * {@code signals}, {@code risk_settings}); the only write is the append-only run row on
 * {@code paper_reconciliation_runs}. Stranded carry is global state and is deliberately not bounded
 * by the V5/V16 window. Every window bound is an EXPLICIT instant (timestamptz), never a
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

  /**
   * Global stranded-carry state: an OPEN position anchored to an ENTRY signal that has a persisted
   * opposite-side EXIT in that anchor's own ENTRY-to-next-ENTRY window. Deliberate pyramid adds remain
   * in the original position's window; signal id breaks ties when candle-derived timestamps are equal.
   * This is deliberately not windowed; the state remains actionable until a human repairs it.
   *
   * <p><b>Both side predicates are SAME-SIDE-SCOPED on purpose, and today both are unreachable.</b>
   * {@code uq_paper_positions_open} (V021:15-16) keys on {@code (book, exchange, tradingsymbol, side)}
   * WHERE status='OPEN', so a BUY and a SELL position MAY structurally coexist on one symbol. No live
   * path reaches that today — {@code SignalRepository.activeEntry:166-178} forces the engine down the
   * exit branch while an anchor is ACTIVE/TAKEN, and {@code SwingBatchEngine.entryPass:277-279} skips a
   * held symbol unless the pyramid policy adds (an add is same-side AND {@code pyramidLot}-tagged, so
   * it is excluded anyway) — and every live ENTRY is BUY, so {@code x.side <> s.side} is currently a
   * tautology. The guards are carried anyway because F9 short-premium would arm short entries, and
   * they must be carried TOGETHER: {@code x.side <> s.side} alone (a sibling's EXIT must not match)
   * without {@code n.side = s.side} (a sibling's ENTRY must not close this anchor's window) is
   * internally inconsistent — the second omission is a FALSE NEGATIVE, which for a safety net is
   * strictly worse than the false positive the first one prevents.
   *
   * <p><b>Known gap (dormant):</b> {@code SwingBatchEngine.openLotsBySymbol:338-346} groups
   * {@code signals.activeEntries()} — a version-less read — by tradingsymbol only, and
   * {@code emitExit:599-600} writes ONE EXIT under the OLDEST lot's {@code strategy_version_id} while
   * expiring every lot in the group. If a non-oldest lot anchors a position, this predicate looks for
   * an EXIT under THAT version and finds none (a false negative). Dormant: live same-symbol overlaps
   * are all minervini_funnel x manas_arora_funnel, which {@code adoptVersion:219-221} filters apart by
   * universeMode, and within a family {@code entryPass} blocks a second lot while pyramiding is
   * disarmed. <b>This predicate assumes one version per (symbol, family).</b>
   */
  public List<Long> strandedCarryPositions() {
    return jdbc.query(
        """
        SELECT p.id
        FROM paper_positions p
        JOIN signals s ON s.id = p.opening_signal_id
        WHERE p.status = 'OPEN'
          AND s.signal_type = 'ENTRY'
          AND EXISTS (
            SELECT 1 FROM signals x
            WHERE x.strategy_version_id = s.strategy_version_id
              AND x.exchange            = s.exchange
              AND x.tradingsymbol       = s.tradingsymbol
              AND x.signal_type         = 'EXIT'
              AND x.side <> s.side
              AND (x.generated_at, x.id) > (s.generated_at, s.id)
              AND NOT EXISTS (
                    SELECT 1 FROM signals n
                    WHERE n.strategy_version_id = s.strategy_version_id
                      AND n.exchange            = s.exchange
                      AND n.tradingsymbol       = s.tradingsymbol
                      AND n.signal_type         = 'ENTRY'
                      AND n.side                = s.side
                      AND NOT (
                            COALESCE(n.minervini_detail ? 'pyramidLot', false)
                            OR COALESCE(n.manas_arora_detail ? 'pyramidLot', false)
                          )
                      AND (n.generated_at, n.id) > (s.generated_at, s.id)
                      AND (n.generated_at, n.id) < (x.generated_at, x.id)
                  )
          );
        """,
        (rs, rowNum) -> rs.getLong("id"));
  }

  /** Reads the stranded-carry ids stored by the most recent prior run, treating missing keys as empty. */
  public List<Long> previousStrandedCarryIds() {
    return jdbc.query(
            """
            SELECT COALESCE((SELECT array_agg(v::bigint)
                             FROM jsonb_array_elements_text(detail->'strandedCarry'->'positions') v), '{}')
            FROM paper_reconciliation_runs ORDER BY ran_at DESC, id DESC LIMIT 1
            """,
            (rs, rowNum) -> {
              Array array = rs.getArray(1);
              if (array == null) {
                return List.<Long>of();
              }
              Object raw = array.getArray();
              if (!(raw instanceof Object[] values)) {
                return List.<Long>of();
              }
              return Arrays.stream(values)
                  .map(value -> value instanceof Number n ? n.longValue() : Long.parseLong(value.toString()))
                  .toList();
            })
        .stream()
        .findFirst()
        .orElseGet(List::of);
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
