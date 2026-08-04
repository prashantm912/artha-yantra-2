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

  /**
   * Idempotent upsert of one DETECTED link.
   *
   * <p>⚠️ {@code status} and {@code status_reason} are deliberately ABSENT from the {@code DO UPDATE
   * SET} list. That omission is the whole mechanism by which an owner's WITHHELD judgement survives
   * every re-detection: the detector re-attaches its evidence to the row and leaves the verdict
   * alone. {@code source} is likewise preserved, so a row that began as {@code owner-policy} keeps
   * saying so.
   *
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
      String source) {
    Integer inserted =
        jdbc.queryForObject(
            """
            INSERT INTO symbol_lineage
              (exchange, predecessor_symbol, successor_symbol, switch_date, gap_sessions,
               boundary_price, confidence, evidence, source, detected_at, refreshed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())
            ON CONFLICT (exchange, predecessor_symbol) DO UPDATE SET
              successor_symbol = EXCLUDED.successor_symbol,
              switch_date      = EXCLUDED.switch_date,
              gap_sessions     = EXCLUDED.gap_sessions,
              boundary_price   = EXCLUDED.boundary_price,
              confidence       = EXCLUDED.confidence,
              evidence         = EXCLUDED.evidence,
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
            source);
    return inserted != null && inserted == 1;
  }
}
