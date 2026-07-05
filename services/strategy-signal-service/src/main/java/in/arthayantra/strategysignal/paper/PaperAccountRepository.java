package in.arthayantra.strategysignal.paper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC access to the per-book {@code paper_account} rows (A12 / books). Equity is computed elsewhere. */
@Repository
public class PaperAccountRepository {

  /** The owner-set capital base. */
  public record Account(BigDecimal startingCapital, BigDecimal cash, OffsetDateTime updatedAt) {}

  private final JdbcTemplate jdbc;

  /** Wires the strategy datasource. */
  public PaperAccountRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** One book's account row ({@code book} null → the aggregate across all books). */
  public Account get(String book) {
    if (book == null) {
      return jdbc.queryForObject(
          "SELECT COALESCE(SUM(starting_capital),0) AS starting_capital,"
              + " COALESCE(SUM(cash),0) AS cash, max(updated_at) AS updated_at FROM paper_account",
          PaperAccountRepository::map);
    }
    // An unrecognised book (e.g. 'other' — a strategy in no known family) has no row: return a
    // zero-capital account rather than throwing, so sizing yields 0 and the book never auto-papers.
    return jdbc
        .query(
            "SELECT starting_capital, cash, updated_at FROM paper_account WHERE book=?",
            PaperAccountRepository::map,
            book)
        .stream()
        .findFirst()
        .orElse(new Account(BigDecimal.ZERO, BigDecimal.ZERO, null));
  }

  private static Account map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
    return new Account(
        rs.getBigDecimal("starting_capital"),
        rs.getBigDecimal("cash"),
        rs.getObject("updated_at", OffsetDateTime.class));
  }

  /** Owner edit of a book's starting capital (resets that book's free cash to it). */
  public void updateStartingCapital(String book, BigDecimal startingCapital) {
    jdbc.update(
        "UPDATE paper_account SET starting_capital=?, cash=?, updated_at=now() WHERE book=?",
        startingCapital,
        startingCapital,
        book);
  }
}
