package in.arthayantra.marketdata.constituents;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Append-only accrual + point-in-time reads (S8). INSERT .. ON CONFLICT DO NOTHING is the whole
 * write surface — prior snapshots are physically untouchable from here. The canonical order for
 * reads (and the checksum) is {@code ORDER BY exchange, tradingsymbol}.
 */
@Repository
public class IndexConstituentsRepository {

  private final JdbcTemplate jdbc;

  /** Wires the marketdata datasource. */
  public IndexConstituentsRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Accrues one snapshot (idempotent per PK; appends only). */
  public int accrue(
      String indexName, LocalDate asOf, List<IndexConstituentsFetcher.Constituent> rows) {
    int inserted = 0;
    for (IndexConstituentsFetcher.Constituent row : rows) {
      inserted +=
          jdbc.update(
              """
              INSERT INTO index_constituents (index_name, as_of_date, exchange, tradingsymbol)
              VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING
              """,
              indexName, Date.valueOf(asOf), row.exchange(), row.tradingsymbol());
    }
    return inserted;
  }

  /** The most recent snapshot date for an index. */
  public Optional<LocalDate> latestAsOf(String indexName) {
    List<Date> dates =
        jdbc.queryForList(
            "SELECT max(as_of_date) FROM index_constituents WHERE index_name = ?",
            Date.class,
            indexName);
    return dates.isEmpty() || dates.get(0) == null
        ? Optional.empty()
        : Optional.of(dates.get(0).toLocalDate());
  }

  /** Membership at an exact snapshot date, canonical order. */
  public List<IndexConstituentsFetcher.Constituent> membership(String indexName, LocalDate asOf) {
    return jdbc.query(
        """
        SELECT exchange, tradingsymbol FROM index_constituents
        WHERE index_name = ? AND as_of_date = ?
        ORDER BY exchange, tradingsymbol
        """,
        (rs, n) ->
            new IndexConstituentsFetcher.Constituent(
                rs.getString("exchange"), rs.getString("tradingsymbol")),
        indexName, Date.valueOf(asOf));
  }

  /** Distinct snapshot dates (IT assertions). */
  public List<LocalDate> snapshotDates(String indexName) {
    return jdbc.query(
        "SELECT DISTINCT as_of_date FROM index_constituents WHERE index_name = ? ORDER BY as_of_date",
        (rs, n) -> rs.getDate(1).toLocalDate(),
        indexName);
  }

  /** SHA-256 over the canonical ordered list ({@code EXCHANGE:SYMBOL} joined by newlines). */
  public static String checksum(List<IndexConstituentsFetcher.Constituent> rows) {
    StringBuilder sb = new StringBuilder();
    for (IndexConstituentsFetcher.Constituent row : rows) {
      sb.append(row.exchange()).append(':').append(row.tradingsymbol()).append('\n');
    }
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of()
          .formatHex(digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
