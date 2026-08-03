package in.arthayantra.strategysignal.paper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC access to {@code paper_position_lots} — one row per paper ENTRY fill, recording WHICH signal
 * caused it and how much it contributed (V057).
 *
 * <p>Exists because a second {@code openPosition} on an already-open {@code (book, exchange,
 * tradingsymbol, side)} AVERAGES into the position rather than rejecting — {@code
 * uq_paper_positions_open} guards the row, never the qty — while {@code
 * paper_positions.opening_signal_id} is written on INSERT only and deliberately kept across an add.
 * So a position built by two strategies firing on the same bar credits exactly one of them, and a
 * {@code GROUP BY slug} over {@code opening_signal_id} reports the other at n=0. Lots restore the
 * per-fill truth the position row averages away.
 *
 * <p><b>Attribution arithmetic — FILL-BASIS, not quantity-only.</b> Each lot is credited with the
 * P&amp;L its OWN entry price earned, so a strategy that entered better is reported as having done
 * better. For a lot of {@code q} filled at {@code f}, on a position of {@code Q} units with average
 * entry {@code A} and realized {@code R}:
 *
 * <pre>{@code   attributed = R * (q / Q)  +  sign * (A - f) * q      sign = +1 BUY, -1 SELL}</pre>
 *
 * <p>The first term shares the pooled result (and with it the costs, which scale with quantity); the
 * second is the lot's entry edge against the blended basis. Writing {@code X} for the exit price, a
 * long lot's true gross is {@code (X-f)q} and the position's is {@code (X-A)Q}; substituting shows
 * {@code X CANCELS}, which is why no exit price is needed and no exit-side linkage has to exist.
 *
 * <p>⚠️ <b>The entry-edge terms do NOT sum to zero, and an earlier version of this javadoc claimed
 * they did</b> (cross-vendor review Critical, round 3). {@code Σ(A - f_i)q_i = A·Q - Σf_i·q_i = 0}
 * holds for the EXACT lot-weighted mean — but {@code PaperService.upsertPosition} rounds the average
 * to FOUR DECIMALS before storing it, and the stored {@code A} is what both the ledger's realized
 * P&amp;L and this query use. The algebra was right and the ledger was not consulted. Measured on
 * the reviewer's counterexample, which is a real pyramid shape:
 *
 * <pre>{@code   65 @ 100.00 + 130 @ 100.01  ->  exact mean 100.00666…, stored A = 100.0067
 *   Σ(A - f_i)q_i = 0.0067*65 + (-0.0033)*130 = +0.0065      (NOT zero)}</pre>
 *
 * <p>So the shares summed to {@code R + ₹0.0065}. The fix is a DETERMINISTIC residual allocation:
 * the per-position sum of entry-edge terms is subtracted from ONE lot of that position — the largest
 * by qty, ties broken by lowest id, so the correction lands where it is proportionally smallest and
 * a re-read can never move it. Determinism is the point: a residual scattered by row order would
 * make the same position attribute differently on every read.
 *
 * <p>⚠️ <b>Two earlier cuts were wrong here, each hidden by a TIDY FIXTURE</b>, which is the real
 * lesson. Round 1 allocated by QUANTITY ALONE — two equal lots at ₹100 and ₹120 exiting at ₹110 read
 * IDENTICALLY when one made ₹650 and the other lost ₹650 — and equal-PRICE tests hid it. Round 3 is
 * the rounding residual above, and equal-QUANTITY tests hid that, because equal quantities put the
 * mean exactly on a representable midpoint where the residual is exactly zero. <b>A fixture chosen
 * to be clean is precisely one where both the right and the wrong implementation agree.</b> The two
 * discriminating tests are {@code aPositionWithUnequalFillPricesAttributesTheEntryEdge} and
 * {@code aPositionWithUnequalQuantitiesStillSumsToRealized}.
 *
 * <p>Dividing the pooled term by the POSITION's qty rather than by the summed lots is deliberate — a
 * position that predates V057 and later takes a tagged add is then attributed only its tagged share,
 * with the remainder visible as untagged, instead of the one add silently claiming the whole trade.
 */
@Repository
public class PaperPositionLotRepository {

  /**
   * One strategy's share of a book, decomposed from the lots.
   *
   * <p>{@code slug} is null for a lot with no signal (a manual fill) — reported rather than dropped,
   * so the rows always sum back to the tagged total.
   */
  public record AttributionRow(
      String slug,
      String book,
      int closedPositions,
      long closedQty,
      long openQty,
      BigDecimal attributedRealizedPnl) {}

  /**
   * How much of the book the lots actually cover — the denominator that makes every {@link
   * AttributionRow} readable.
   *
   * <p>Load-bearing, not decoration: no position opened before V057 has lots, so on the day this
   * ships the attribution rows are EMPTY while the book holds 45 real positions. Without coverage
   * beside them an empty decomposition reads as "this book never traded" rather than "this book
   * traded before tagging existed".
   */
  public record Coverage(
      int closedPositions,
      int closedPositionsTagged,
      long closedQty,
      long closedQtyTagged,
      int openPositions,
      int openPositionsTagged,
      long openQty,
      long openQtyTagged) {}

  private final JdbcTemplate jdbc;

  /** Wires the strategy datasource. */
  public PaperPositionLotRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Records one entry fill's contribution to a position.
   *
   * <p>Runs inside the opening transaction on purpose. A lot written afterwards could be lost while
   * its fill committed, and {@code sum(lots.qty) = position.qty} is the whole reason a decomposition
   * can be trusted — a hole in it is indistinguishable from a strategy that simply traded less.
   */
  public long insert(
      long positionId,
      long orderId,
      Long signalId,
      String book,
      String exchange,
      String tradingsymbol,
      String side,
      long qty,
      BigDecimal fillPrice) {
    Long id =
        jdbc.queryForObject(
            """
            INSERT INTO paper_position_lots
              (position_id, order_id, signal_id, book, exchange, tradingsymbol, side, qty, fill_price)
            VALUES (?,?,?,?,?,?,?,?,?) RETURNING id
            """,
            Long.class,
            positionId,
            orderId,
            signalId,
            book,
            exchange,
            tradingsymbol,
            side,
            qty,
            fillPrice);
    return id == null ? 0 : id;
  }

  /**
   * The per-strategy decomposition of a book ({@code book} null → every book).
   *
   * <p>Fill-basis: the pooled share plus each lot's entry edge against the blended basis — see the
   * class javadoc for the identity and for why the exit price cancels out.
   *
   * <p><b>The ledger-rounding residual is allocated deterministically.</b> {@code edge} below is
   * EXACT (multiplication and subtraction of {@code numeric} only), but it does not sum to zero
   * across a position, because the stored {@code avg_entry_price} is rounded to 4dp — see the class
   * javadoc for the measured counterexample. So the per-position sum of {@code edge} is subtracted
   * from exactly ONE lot, picked by {@code qty DESC, id} — largest first so the correction is
   * proportionally smallest, {@code id} to break ties, and both stable across re-reads.
   *
   * <p>What remains inexact is only {@code R·q/Q}, at PostgreSQL {@code numeric} division precision
   * (~16+ significant digits), summed at full precision and rounded once per group. So group totals
   * reconstruct the book's realized P&amp;L to well within a paisa — <b>not bit-for-bit</b>.
   */
  public List<AttributionRow> attribution(String book) {
    return jdbc.query(
        """
        WITH lot_terms AS (
          SELECT l.id            AS lot_id,
                 l.position_id   AS position_id,
                 l.signal_id     AS signal_id,
                 l.book          AS book,
                 p.status        AS status,
                 l.qty           AS qty,
                 CASE WHEN p.status = 'CLOSED'
                      THEN p.realized_pnl * l.qty / p.qty
                      ELSE 0 END AS pooled,
                 CASE WHEN p.status = 'CLOSED'
                      THEN (CASE WHEN p.side = 'BUY' THEN 1 ELSE -1 END)
                           * (p.avg_entry_price - l.fill_price) * l.qty
                      ELSE 0 END AS edge,
                 row_number() OVER (PARTITION BY l.position_id ORDER BY l.qty DESC, l.id)
                                 AS residual_rank
            FROM paper_position_lots l
            JOIN paper_positions p ON p.id = l.position_id
           WHERE (?::text IS NULL OR l.book = ?)
        ),
        allocated AS (
          SELECT position_id, signal_id, book, status, qty,
                 pooled + edge
                   - CASE WHEN residual_rank = 1
                          THEN sum(edge) OVER (PARTITION BY position_id)
                          ELSE 0 END AS attributed
            FROM lot_terms
        )
        SELECT st.slug AS slug,
               a.book  AS book,
               count(DISTINCT a.position_id) FILTER (WHERE a.status = 'CLOSED') AS closed_positions,
               coalesce(sum(a.qty) FILTER (WHERE a.status = 'CLOSED'), 0) AS closed_qty,
               coalesce(sum(a.qty) FILTER (WHERE a.status = 'OPEN'), 0) AS open_qty,
               coalesce(
                 round(sum(a.attributed) FILTER (WHERE a.status = 'CLOSED'), 4),
                 0) AS attributed_realized_pnl
          FROM allocated a
          LEFT JOIN signals s ON s.id = a.signal_id
          LEFT JOIN strategy_versions sv ON sv.id = s.strategy_version_id
          LEFT JOIN strategies st ON st.id = sv.strategy_id
         GROUP BY st.slug, a.book
         ORDER BY a.book, st.slug NULLS LAST
        """,
        PaperPositionLotRepository::mapAttribution,
        book,
        book);
  }

  /**
   * What fraction of the book carries lots at all ({@code book} null → every book).
   *
   * <p>A position counts as TAGGED when it has at least one lot; its tagged QTY is the sum of those
   * lots, which is less than the position's qty exactly when the position predates V057 and later
   * took an add. Both are reported so the gap is visible rather than inferred.
   */
  public Coverage coverage(String book) {
    Coverage row =
        jdbc.queryForObject(
            """
            WITH tagged AS (
              SELECT p.id, p.status, p.qty, coalesce(sum(l.qty), 0) AS lot_qty
                FROM paper_positions p
                LEFT JOIN paper_position_lots l ON l.position_id = p.id
               WHERE (?::text IS NULL OR p.book = ?)
               GROUP BY p.id, p.status, p.qty
            )
            SELECT count(*) FILTER (WHERE status = 'CLOSED') AS closed_positions,
                   count(*) FILTER (WHERE status = 'CLOSED' AND lot_qty > 0) AS closed_positions_tagged,
                   coalesce(sum(qty) FILTER (WHERE status = 'CLOSED'), 0) AS closed_qty,
                   coalesce(sum(lot_qty) FILTER (WHERE status = 'CLOSED'), 0) AS closed_qty_tagged,
                   count(*) FILTER (WHERE status = 'OPEN') AS open_positions,
                   count(*) FILTER (WHERE status = 'OPEN' AND lot_qty > 0) AS open_positions_tagged,
                   coalesce(sum(qty) FILTER (WHERE status = 'OPEN'), 0) AS open_qty,
                   coalesce(sum(lot_qty) FILTER (WHERE status = 'OPEN'), 0) AS open_qty_tagged
              FROM tagged
            """,
            PaperPositionLotRepository::mapCoverage,
            book,
            book);
    return row == null
        ? new Coverage(0, 0, 0L, 0L, 0, 0, 0L, 0L)
        : row;
  }

  private static AttributionRow mapAttribution(ResultSet rs, int rowNum) throws SQLException {
    return new AttributionRow(
        rs.getString("slug"),
        rs.getString("book"),
        rs.getInt("closed_positions"),
        rs.getLong("closed_qty"),
        rs.getLong("open_qty"),
        rs.getBigDecimal("attributed_realized_pnl"));
  }

  private static Coverage mapCoverage(ResultSet rs, int rowNum) throws SQLException {
    return new Coverage(
        rs.getInt("closed_positions"),
        rs.getInt("closed_positions_tagged"),
        rs.getLong("closed_qty"),
        rs.getLong("closed_qty_tagged"),
        rs.getInt("open_positions"),
        rs.getInt("open_positions_tagged"),
        rs.getLong("open_qty"),
        rs.getLong("open_qty_tagged"));
  }
}
