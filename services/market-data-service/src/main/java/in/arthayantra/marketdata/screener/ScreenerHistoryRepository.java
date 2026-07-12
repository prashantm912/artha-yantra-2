package in.arthayantra.marketdata.screener;

import in.arthayantra.marketdata.screener.ScreenerHistory.AttritionCounts;
import in.arthayantra.marketdata.screener.ScreenerHistory.DiffRow;
import in.arthayantra.marketdata.screener.ScreenerHistory.GateCount;
import in.arthayantra.marketdata.screener.ScreenerHistory.ScreenDate;
import in.arthayantra.marketdata.screener.ScreenerHistory.ScreenDiff;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Compute-on-read screener time-machine queries (audit §6.7 / §2.6), shared by both screeners. Both
 * screen tables carry the same {@code (screen_date, symbol, passes_all, rs_rank)} substrate + {@code
 * gateN} booleans, so the SQL is parameterized only by the {@link Family} descriptor (table name +
 * gate count — both compile-time constants, never user input, so the string interpolation is safe).
 * Read-only over already-persisted rows: no writes, no migration.
 */
@Repository
public class ScreenerHistoryRepository {

  /** The two screener families and their persisted-screen table + gate count. */
  public enum Family {
    MINERVINI("minervini_screen_results", 8),
    MANAS("manas_arora_screen_results", 6);

    private final String table;
    private final int gates;

    Family(String table, int gates) {
      this.table = table;
      this.gates = gates;
    }
  }

  private final JdbcTemplate jdbc;

  public ScreenerHistoryRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** The most recent {@code limit} distinct screen dates (newest first) + scanned/passer counts. */
  public List<ScreenDate> recentDates(Family f, int limit) {
    return jdbc.query(
        "SELECT screen_date, count(*) AS coverage, "
            + " count(*) FILTER (WHERE passes_all) AS passers "
            + "FROM " + f.table + " GROUP BY screen_date ORDER BY screen_date DESC LIMIT ?",
        (rs, n) ->
            new ScreenDate(
                rs.getObject("screen_date", LocalDate.class),
                rs.getInt("coverage"),
                rs.getInt("passers")),
        Math.max(1, limit));
  }

  /**
   * The distinct persisted screen date immediately BEFORE {@code date} (the natural diff baseline),
   * or {@code null} when {@code date} is the earliest screen on record.
   */
  public LocalDate priorScreenDate(Family f, LocalDate date) {
    return jdbc.query(
        "SELECT max(screen_date) AS d FROM " + f.table + " WHERE screen_date < ?",
        rs -> rs.next() ? rs.getObject("d", LocalDate.class) : null,
        Date.valueOf(date));
  }

  /**
   * The day-over-day passer diff between {@code priorDate} and {@code date}. {@code priorDate} null ⇒
   * no earlier screen: every passer on {@code date} is "entered", nothing "left".
   */
  public ScreenDiff diff(Family f, LocalDate date, LocalDate priorDate) {
    List<DiffRow> entered = passersEnteredSince(f, date, priorDate);
    List<DiffRow> left =
        priorDate == null ? List.of() : passersEnteredSince(f, priorDate, date);
    return new ScreenDiff(date, priorDate, entered.size(), left.size(), entered, left);
  }

  /**
   * Passers on {@code date} that were NOT passers on {@code baselineDate} (ranked by RS-rank). When
   * {@code baselineDate} is null every passer on {@code date} qualifies (no baseline to subtract).
   */
  private List<DiffRow> passersEnteredSince(Family f, LocalDate date, LocalDate baselineDate) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT symbol, rs_rank FROM " + f.table + " WHERE screen_date = ? AND passes_all = TRUE");
    List<Object> args = new ArrayList<>();
    args.add(Date.valueOf(date));
    if (baselineDate != null) {
      sql.append(
          " AND symbol NOT IN (SELECT symbol FROM " + f.table
              + " WHERE screen_date = ? AND passes_all = TRUE)");
      args.add(Date.valueOf(baselineDate));
    }
    sql.append(" ORDER BY rs_rank DESC NULLS LAST, symbol ASC");
    return jdbc.query(
        sql.toString(),
        (rs, n) -> new DiffRow(rs.getString("symbol"), rs.getBigDecimal("rs_rank")),
        args.toArray());
  }

  /**
   * Scanned count, per-individual-gate pass counts, and the all-gates-pass count for a screen date —
   * the raw attrition the screen table can answer alone (the funnel buckets are joined by the caller).
   */
  public AttritionCounts attrition(Family f, LocalDate date) {
    StringBuilder select = new StringBuilder("SELECT count(*) AS scanned");
    for (int g = 1; g <= f.gates; g++) {
      select.append(", count(*) FILTER (WHERE gate").append(g).append(") AS g").append(g);
    }
    select.append(", count(*) FILTER (WHERE passes_all) AS passes_all FROM " + f.table)
        .append(" WHERE screen_date = ?");
    final int gateCount = f.gates;
    return jdbc.queryForObject(
        select.toString(),
        (rs, n) -> {
          List<GateCount> gates = new ArrayList<>(gateCount);
          for (int g = 1; g <= gateCount; g++) {
            gates.add(new GateCount(g, rs.getLong("g" + g)));
          }
          return new AttritionCounts(rs.getLong("scanned"), gates, rs.getLong("passes_all"));
        },
        Date.valueOf(date));
  }
}
