package in.arthayantra.marketdata.dataquality;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC persistence for the nightly per-symbol completeness artifact ({@code data_quality_days}). */
@Repository
public class DataQualityRepository {

  /** One persisted completeness verdict for a trading-day scope and symbol. */
  public record DataQualityRow(
      LocalDate tradingDate,
      String scope,
      String symbol,
      long expected,
      long present,
      BigDecimal coveragePct,
      boolean ok,
      String detail,
      OffsetDateTime computedAt) {}

  private static final String UPSERT =
      """
      INSERT INTO data_quality_days
        (trading_date, scope, symbol, expected, present, coverage_pct, ok, detail, computed_at)
      VALUES (?,?,?,?,?,?,?,?,?)
      ON CONFLICT (trading_date, scope, symbol) DO UPDATE SET
        expected = EXCLUDED.expected, present = EXCLUDED.present,
        coverage_pct = EXCLUDED.coverage_pct, ok = EXCLUDED.ok,
        detail = EXCLUDED.detail, computed_at = EXCLUDED.computed_at
      """;

  private final JdbcTemplate jdbc;

  /** Wires the market-data JDBC template. */
  public DataQualityRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Upserts every row for a computed day and returns the number of rows written. */
  public int upsertAll(LocalDate day, List<DataQualityRow> rows) {
    if (rows.isEmpty()) {
      return 0;
    }
    jdbc.batchUpdate(
        UPSERT,
        rows,
        rows.size(),
        (ps, row) -> {
          ps.setObject(1, day);
          ps.setString(2, row.scope());
          ps.setString(3, row.symbol());
          ps.setLong(4, row.expected());
          ps.setLong(5, row.present());
          ps.setObject(6, row.coveragePct());
          ps.setBoolean(7, row.ok());
          ps.setString(8, row.detail());
          ps.setObject(9, row.computedAt());
        });
    return rows.size();
  }

  /** Whether the nightly/boot dedup watermark already has any rows for {@code day}. */
  public boolean hasRowsFor(LocalDate day) {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM data_quality_days WHERE trading_date = ?", Integer.class, day);
    return count != null && count > 0;
  }

  /** Reads one report day in stable scope/symbol order. */
  public List<DataQualityRow> findByDate(LocalDate day) {
    return jdbc.query(
        "SELECT trading_date, scope, symbol, expected, present, coverage_pct, ok, detail,"
            + " computed_at FROM data_quality_days WHERE trading_date = ? ORDER BY scope, symbol",
        DataQualityRepository::mapRow,
        day);
  }

  /** Latest report day, or empty before the first successful run. */
  public Optional<LocalDate> latestDate() {
    LocalDate latest =
        jdbc.query(
            "SELECT max(trading_date) AS d FROM data_quality_days",
            rs -> rs.next() ? rs.getObject("d", LocalDate.class) : null);
    return Optional.ofNullable(latest);
  }

  private static DataQualityRow mapRow(java.sql.ResultSet rs, int rowNum)
      throws java.sql.SQLException {
    return new DataQualityRow(
        rs.getObject("trading_date", LocalDate.class),
        rs.getString("scope"),
        rs.getString("symbol"),
        rs.getLong("expected"),
        rs.getLong("present"),
        rs.getBigDecimal("coverage_pct"),
        rs.getBoolean("ok"),
        rs.getString("detail"),
        rs.getObject("computed_at", OffsetDateTime.class));
  }
}
