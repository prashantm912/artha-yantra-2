package in.arthayantra.strategysignal.swing;

import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Durable symbol-level refusal evidence for pinned swing replays (V049). */
@Repository
public class SwingBatchRefusalRepository {

  private final JdbcTemplate jdbc;

  public SwingBatchRefusalRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void record(String batch, LocalDate sessionDate, String symbol, String reason) {
    jdbc.update(
        """
        INSERT INTO swing_batch_refusals (batch, session_date, tradingsymbol, reason)
        VALUES (?, ?, ?, ?)
        ON CONFLICT (batch, session_date, tradingsymbol, reason) DO NOTHING
        """,
        batch,
        java.sql.Date.valueOf(sessionDate),
        symbol,
        reason);
  }
}
