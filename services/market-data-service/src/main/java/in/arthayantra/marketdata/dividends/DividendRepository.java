package in.arthayantra.marketdata.dividends;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Write access for dividend cash-flow records captured from corporate-action feeds. */
@Repository
public class DividendRepository {

  private final JdbcTemplate jdbc;

  public DividendRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Idempotent upsert keyed by {@code (exchange, tradingsymbol, ex_date)}. */
  public void upsert(
      String exchange,
      String tradingsymbol,
      LocalDate exDate,
      BigDecimal amount,
      String subject,
      String isin,
      String source) {
    jdbc.update(
        """
        INSERT INTO dividends
          (exchange, tradingsymbol, ex_date, amount, subject, isin, source, detected_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, now())
        ON CONFLICT (exchange, tradingsymbol, ex_date) DO UPDATE SET
          amount = EXCLUDED.amount,
          subject = EXCLUDED.subject,
          isin = EXCLUDED.isin,
          source = EXCLUDED.source
        """,
        exchange, tradingsymbol, exDate, amount, subject, isin, source);
  }
}
