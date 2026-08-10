package in.arthayantra.marketdata.lineage;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Read + upsert access for {@code marketdata.symbol_lineage}. */
@Repository
public class SymbolLineageRepository {

  private static final RowMapper<SymbolLineage> MAPPER =
      (rs, n) ->
          new SymbolLineage(
              rs.getString("exchange"),
              rs.getString("predecessor_symbol"),
              rs.getString("successor_symbol"),
              rs.getObject("switch_date", LocalDate.class),
              (Integer) rs.getObject("gap_sessions"),
              rs.getBigDecimal("boundary_price"),
              rs.getString("confidence"),
              rs.getString("evidence"),
              rs.getString("status"),
              rs.getString("status_reason"),
              rs.getString("source"));

  private static final String SELECT =
      """
      SELECT exchange, predecessor_symbol, successor_symbol, switch_date, gap_sessions,
             boundary_price, confidence, evidence, status, status_reason, source
      FROM symbol_lineage
      """;

  private final JdbcTemplate jdbc;

  public SymbolLineageRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Every link for {@code exchange}, newest switch first (nulls — policy rows — last). */
  public List<SymbolLineage> all(String exchange) {
    return jdbc.query(
        SELECT + " WHERE exchange = ? ORDER BY switch_date DESC NULLS LAST, successor_symbol",
        MAPPER,
        exchange);
  }

  /** Only the links a lineage-expanded reader may stitch. */
  public List<SymbolLineage> active(String exchange) {
    return jdbc.query(
        SELECT
            + " WHERE exchange = ? AND status = 'ACTIVE'"
            + " ORDER BY switch_date DESC NULLS LAST, successor_symbol",
        MAPPER,
        exchange);
  }

  private static final String REFUTED_REASON =
      "Refuted by BSE: both tickers are listed there under DIFFERENT scrip_codes, so BSE"
          + " contradicts the NSE price continuity. Set by the detector, not by an owner.";

  /**
   * Idempotent upsert of one DETECTED link.
   *
   * <p><b>Status is a ONE-WAY RATCHET, and the asymmetry is the whole safety property.</b>
   *
   * <ul>
   *   <li><b>On INSERT</b> the detector chooses the status: {@code WITHHELD} when BSE refuted the
   *       pair, else the column default {@code ACTIVE}.
   *   <li><b>On CONFLICT</b> the {@code DO UPDATE SET} list refreshes the derived columns and
   *       demotes {@code ACTIVE → WITHHELD} if this run refutes the pair. It can never move a row
   *       the other way: the {@code CASE} returns the EXISTING status in every other branch.
   * </ul>
   *
   * <p>So an owner's WITHHELD verdict is permanent — no detector run promotes it — while a pair
   * that BSE data only later contradicts stops being stitched without anyone having to notice.
   * {@code status_reason} is written only when this call is the thing that withholds, so an owner's
   * hand-written reason is never overwritten either. {@code source} is likewise preserved, so a row
   * that began as {@code owner-policy} keeps saying so.
   *
   * @param refuted BSE can see both tickers and says they are different listings
   * @return true when this call inserted a link that was not there before
   */
  public boolean upsertDetected(
      String exchange,
      String predecessor,
      String successor,
      LocalDate switchDate,
      int gapSessions,
      BigDecimal boundaryPrice,
      String confidence,
      String evidence,
      String source,
      boolean refuted) {
    Integer inserted =
        jdbc.queryForObject(
            """
            INSERT INTO symbol_lineage
              (exchange, predecessor_symbol, successor_symbol, switch_date, gap_sessions,
               boundary_price, confidence, evidence, status, status_reason, source,
               detected_at, refreshed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?,
                    CASE WHEN ? THEN 'WITHHELD' ELSE 'ACTIVE' END,
                    CASE WHEN ? THEN ? ELSE NULL END,
                    ?, now(), now())
            ON CONFLICT (exchange, predecessor_symbol) DO UPDATE SET
              successor_symbol = EXCLUDED.successor_symbol,
              switch_date      = EXCLUDED.switch_date,
              gap_sessions     = EXCLUDED.gap_sessions,
              boundary_price   = EXCLUDED.boundary_price,
              confidence       = EXCLUDED.confidence,
              evidence         = EXCLUDED.evidence,
              status           = CASE WHEN EXCLUDED.status = 'WITHHELD'
                                      THEN 'WITHHELD' ELSE symbol_lineage.status END,
              status_reason    = CASE WHEN EXCLUDED.status = 'WITHHELD'
                                           AND symbol_lineage.status <> 'WITHHELD'
                                      THEN EXCLUDED.status_reason
                                      ELSE symbol_lineage.status_reason END,
              refreshed_at     = now()
            RETURNING CASE WHEN xmax = 0 THEN 1 ELSE 0 END
            """,
            Integer.class,
            exchange,
            predecessor,
            successor,
            switchDate,
            gapSessions,
            boundaryPrice,
            confidence,
            evidence,
            refuted,
            refuted,
            REFUTED_REASON,
            source);
    return inserted != null && inserted == 1;
  }
}
