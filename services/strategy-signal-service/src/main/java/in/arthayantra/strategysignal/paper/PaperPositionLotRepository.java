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
 * second is the lot's entry edge against the blended basis. <b>This is exact, not an
 * approximation.</b> Writing {@code X} for the exit price, a long lot's true gross is {@code (X-f)q}
 * and the position's is {@code (X-A)Q}; substituting shows {@code X CANCELS}, which is why no exit
 * price is needed and no exit-side linkage has to exist. The shares also sum back to {@code R}
 * exactly, since {@code Σ(A - f_i)q_i = A·Q - Σf_i·q_i = 0} by the definition of the average.
 *
 * <p>⚠️ <b>An earlier cut allocated by QUANTITY ALONE and was wrong</b> (cross-vendor review
 * Critical 1). Two equal lots at ₹100 and ₹120 exiting at ₹110 were reported IDENTICALLY, when one
 * made ₹650 and the other lost ₹650 — erasing precisely the entry-quality difference a keep/cut
 * verdict turns on. The integration test masked it by filling both lots at the SAME price, which is
 * the live scalper shape; {@code aPositionWithUnequalFillPricesAttributesTheEntryEdge} is the
 * discriminating case that now pins it.
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

  /** Every lot of one position, oldest fill first — the per-position decomposition. */
  public List<AttributionRow> lotsForPosition(long positionId) {
    return jdbc.query(
        """
        SELECT st.slug AS slug,
               l.book AS book,
               CASE WHEN p.status = 'CLOSED' THEN 1 ELSE 0 END AS closed_positions,
               CASE WHEN p.status = 'CLOSED' THEN l.qty ELSE 0 END AS closed_qty,
               CASE WHEN p.status = 'OPEN' THEN l.qty ELSE 0 END AS open_qty,
               CASE WHEN p.status = 'CLOSED'
                    THEN round(
                           p.realized_pnl * l.qty / p.qty
                             + CASE WHEN p.side = 'BUY' THEN 1 ELSE -1 END
                               * (p.avg_entry_price - l.fill_price) * l.qty,
                           4)
                    ELSE 0 END AS attributed_realized_pnl
          FROM paper_position_lots l
          JOIN paper_positions p ON p.id = l.position_id
          LEFT JOIN signals s ON s.id = l.signal_id
          LEFT JOIN strategy_versions sv ON sv.id = s.strategy_version_id
          LEFT JOIN strategies st ON st.id = sv.strategy_id
         WHERE l.position_id = ?
         ORDER BY l.filled_at, l.id
        """,
        PaperPositionLotRepository::mapAttribution,
        positionId);
  }

  /**
   * The per-strategy decomposition of a book ({@code book} null → every book).
   *
   * <p>Fill-basis: the pooled share plus each lot's entry edge against the blended basis — see the
   * class javadoc for the identity and for why the exit price cancels out.
   *
   * <p>The per-lot terms are summed at PostgreSQL {@code numeric}'s full precision and rounded once
   * per group, so a group figure is accurate to its last paise; only {@code R·q/Q} is inexact when
   * {@code Q} does not divide {@code q}, and the entry-edge term is exact. Group totals therefore
   * reconstruct the book's realized P&amp;L up to sub-paise rounding, not bit-for-bit.
   */
  public List<AttributionRow> attribution(String book) {
    return jdbc.query(
        """
        SELECT st.slug AS slug,
               l.book AS book,
               count(DISTINCT p.id) FILTER (WHERE p.status = 'CLOSED') AS closed_positions,
               coalesce(sum(l.qty) FILTER (WHERE p.status = 'CLOSED'), 0) AS closed_qty,
               coalesce(sum(l.qty) FILTER (WHERE p.status = 'OPEN'), 0) AS open_qty,
               coalesce(
                 round(
                   sum(
                     p.realized_pnl * l.qty / p.qty
                       + CASE WHEN p.side = 'BUY' THEN 1 ELSE -1 END
                         * (p.avg_entry_price - l.fill_price) * l.qty
                   ) FILTER (WHERE p.status = 'CLOSED'),
                   4),
                 0) AS attributed_realized_pnl
          FROM paper_position_lots l
          JOIN paper_positions p ON p.id = l.position_id
          LEFT JOIN signals s ON s.id = l.signal_id
          LEFT JOIN strategy_versions sv ON sv.id = s.strategy_version_id
          LEFT JOIN strategies st ON st.id = sv.strategy_id
         WHERE (?::text IS NULL OR l.book = ?)
         GROUP BY st.slug, l.book
         ORDER BY l.book, st.slug NULLS LAST
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
