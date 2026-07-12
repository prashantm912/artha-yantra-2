package in.arthayantra.backtest.provenance;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Reads and appends the {@code dataset_epochs} ledger (§11.3) — the small append-only record of
 * data-rewrite events (CA re-adjust / authoritative re-fetch / backfill / bhavcopy fill / manual). The
 * monotonic {@code id} is the epoch head a run stamps at execution; {@link #affecting} answers the re-run
 * policy's scope-intersection question. Connected as the owner (like every backtest repo); the ledger
 * lives in this service's own {@code backtest} schema.
 */
@Repository
public class DatasetEpochRepository {

  private final JdbcTemplate jdbc;

  /** Wires JDBC. */
  public DatasetEpochRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static final RowMapper<DatasetEpoch> MAPPER =
      (rs, n) ->
          new DatasetEpoch(
              rs.getLong("id"),
              rs.getString("reason"),
              symbols(rs),
              rs.getString("exchange"),
              tsString(rs, "window_start"),
              tsString(rs, "window_end"),
              rs.getString("interval"),
              rs.getString("job_link"),
              rs.getString("source"),
              rs.getString("note"),
              tsString(rs, "created_at"));

  private static final String COLS =
      "id, reason, symbols, exchange, window_start, window_end, \"interval\", job_link, source, "
          + "note, created_at";

  /** The current epoch head (max id), or {@code 0} when the ledger is empty — the value a run stamps. */
  public long head() {
    Long head = jdbc.queryForObject("SELECT coalesce(max(id), 0) FROM dataset_epochs", Long.class);
    return head == null ? 0L : head;
  }

  /** Records one data-rewrite epoch and returns the persisted row (with its assigned id + timestamp). */
  public DatasetEpoch record(
      String reason,
      List<String> symbols,
      String exchange,
      OffsetDateTime windowStart,
      OffsetDateTime windowEnd,
      String interval,
      String jobLink,
      String source,
      String note) {
    return jdbc.query(
            con -> {
              var ps =
                  con.prepareStatement(
                      "INSERT INTO dataset_epochs "
                          + "(reason, symbols, exchange, window_start, window_end, \"interval\", "
                          + "job_link, source, note) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                          + "RETURNING " + COLS);
              ps.setString(1, reason);
              ps.setArray(
                  2,
                  symbols == null || symbols.isEmpty()
                      ? null
                      : con.createArrayOf("text", symbols.toArray()));
              ps.setString(3, exchange);
              ps.setObject(4, windowStart);
              ps.setObject(5, windowEnd);
              ps.setString(6, interval);
              ps.setString(7, jobLink);
              ps.setString(8, source);
              ps.setString(9, note);
              return ps;
            },
            MAPPER)
        .get(0);
  }

  /** The recorded epochs, newest first, bounded page. */
  public List<DatasetEpoch> list(int limit, int offset) {
    return jdbc.query(
        "SELECT " + COLS + " FROM dataset_epochs ORDER BY id DESC LIMIT ? OFFSET ?",
        MAPPER,
        limit,
        offset);
  }

  /**
   * The epochs recorded AFTER {@code afterEpoch} whose scope intersects a run's {@code (exchange,
   * tradingsymbol)} over {@code [runStart, runEnd)} — the re-run policy's staleness set. Scope match: a
   * global-scope epoch ({@code symbols} NULL) OR the symbol is in the epoch's list; the epoch exchange is
   * NULL or equal; and the epoch window is whole-history (NULL) or overlaps the run window. Ordered by id.
   */
  public List<DatasetEpoch> affecting(
      long afterEpoch,
      String exchange,
      String tradingsymbol,
      OffsetDateTime runStart,
      OffsetDateTime runEnd) {
    return jdbc.query(
        "SELECT " + COLS + " FROM dataset_epochs WHERE id > ? "
            + "AND (symbols IS NULL OR ?::text = ANY(symbols)) "
            + "AND (exchange IS NULL OR exchange = ?) "
            + "AND (window_start IS NULL OR window_end IS NULL OR (window_start < ? AND ? < window_end)) "
            + "ORDER BY id",
        MAPPER,
        afterEpoch,
        tradingsymbol,
        exchange,
        runEnd,
        runStart);
  }

  private static List<String> symbols(ResultSet rs) throws SQLException {
    Array array = rs.getArray("symbols");
    if (array == null) {
      return null;
    }
    return List.of((String[]) array.getArray());
  }

  private static String tsString(ResultSet rs, String column) throws SQLException {
    OffsetDateTime ts = rs.getObject(column, OffsetDateTime.class);
    return ts == null ? null : ts.toString();
  }
}
