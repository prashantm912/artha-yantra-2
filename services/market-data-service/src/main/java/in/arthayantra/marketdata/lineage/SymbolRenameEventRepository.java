package in.arthayantra.marketdata.lineage;

import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Write access for {@code marketdata.symbol_rename_events} — the "Change in Name" corporate actions
 * the bhavcopy CA sync used to drop on the floor.
 */
@Repository
public class SymbolRenameEventRepository {

  private final JdbcTemplate jdbc;

  public SymbolRenameEventRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Idempotent upsert keyed by {@code (exchange, symbol, ex_date)}. */
  public void upsert(
      String exchange,
      String symbol,
      LocalDate exDate,
      String isin,
      String fromName,
      String toName,
      String subject,
      String source) {
    jdbc.update(
        """
        INSERT INTO symbol_rename_events
          (exchange, symbol, ex_date, isin, from_name, to_name, subject, source, detected_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())
        ON CONFLICT (exchange, symbol, ex_date) DO UPDATE SET
          isin = EXCLUDED.isin,
          from_name = EXCLUDED.from_name,
          to_name = EXCLUDED.to_name,
          subject = EXCLUDED.subject,
          source = EXCLUDED.source
        """,
        exchange, symbol, exDate, isin, fromName, toName, subject, source);
  }

  /** How many rename events are on record for {@code exchange} (the detector's evidence probe). */
  public int count(String exchange) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM symbol_rename_events WHERE exchange = ?", Integer.class, exchange);
    return n == null ? 0 : n;
  }
}
