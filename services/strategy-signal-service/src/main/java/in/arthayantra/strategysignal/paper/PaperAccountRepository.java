package in.arthayantra.strategysignal.paper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC access to the single-row {@code paper_account} (A12). Equity is computed, never read here. */
@Repository
public class PaperAccountRepository {

  /** The owner-set capital base. */
  public record Account(BigDecimal startingCapital, BigDecimal cash, OffsetDateTime updatedAt) {}

  private final JdbcTemplate jdbc;

  /** Wires the strategy datasource. */
  public PaperAccountRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** The single account row. */
  public Account get() {
    return jdbc.queryForObject(
        "SELECT starting_capital, cash, updated_at FROM paper_account WHERE id=1",
        (rs, n) ->
            new Account(
                rs.getBigDecimal("starting_capital"),
                rs.getBigDecimal("cash"),
                rs.getObject("updated_at", OffsetDateTime.class)));
  }

  /** Owner edit of the starting capital (resets free cash to it). */
  public void updateStartingCapital(BigDecimal startingCapital) {
    jdbc.update(
        "UPDATE paper_account SET starting_capital=?, cash=?, updated_at=now() WHERE id=1",
        startingCapital,
        startingCapital);
  }
}
