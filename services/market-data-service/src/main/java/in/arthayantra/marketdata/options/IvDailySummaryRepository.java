package in.arthayantra.marketdata.options;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC access to {@code iv_daily_summary} (F-42B) plus the {@code options_chain_snapshots} reads
 * the 16:00 IST rollup consumes. Written ONLY by market-data-service (single writer, D10). The
 * rollup is recomputable retroactively from the archive — upserts bump {@code computed_at} only.
 */
@Repository
public class IvDailySummaryRepository {

  /** One daily IV summary row. */
  public record Summary(
      String underlying,
      LocalDate summaryDate,
      BigDecimal atmIv,
      BigDecimal iv30d,
      BigDecimal spotPrice,
      int snapshotCount,
      OffsetDateTime computedAt) {}

  /** A snapshot leg the rollup reads (one expiry/strike/side at the rollup ts). */
  public record RollupRow(
      LocalDate expiry, BigDecimal strike, String optionType, BigDecimal iv, BigDecimal spotPrice) {}

  private static final String IST = "Asia/Kolkata";

  private final JdbcTemplate jdbc;

  /** Wires the marketdata datasource. */
  public IvDailySummaryRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Distinct IST trading days that have any snapshot for the underlying (oldest first). */
  public List<LocalDate> snapshotDates(String underlying) {
    return jdbc.query(
        "SELECT DISTINCT (ts AT TIME ZONE '"
            + IST
            + "')::date AS d FROM options_chain_snapshots WHERE underlying = ? ORDER BY d",
        (rs, n) -> rs.getObject("d", LocalDate.class),
        underlying);
  }

  /** The last snapshot ts on an IST day (the 16:00-ish rollup snapshot). */
  public Optional<OffsetDateTime> lastTsOn(String underlying, LocalDate date) {
    List<OffsetDateTime> ts =
        jdbc.query(
            "SELECT max(ts) AS t FROM options_chain_snapshots WHERE underlying = ?"
                + " AND (ts AT TIME ZONE '"
                + IST
                + "')::date = ?",
            (rs, n) -> rs.getObject("t", OffsetDateTime.class),
            underlying,
            java.sql.Date.valueOf(date));
    return ts.isEmpty() || ts.get(0) == null ? Optional.empty() : Optional.of(ts.get(0));
  }

  /** Distinct snapshot passes that fed an IST day (the per-day coverage signal). */
  public int snapshotCountOn(String underlying, LocalDate date) {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(DISTINCT ts) FROM options_chain_snapshots WHERE underlying = ?"
                + " AND (ts AT TIME ZONE '"
                + IST
                + "')::date = ?",
            Integer.class,
            underlying,
            java.sql.Date.valueOf(date));
    return count == null ? 0 : count;
  }

  /** Every leg of one snapshot pass (the rollup ts), across all expiries/strikes. */
  public List<RollupRow> rowsAtTs(String underlying, OffsetDateTime ts) {
    return jdbc.query(
        """
        SELECT expiry, strike, option_type, iv, spot_price
        FROM options_chain_snapshots WHERE underlying = ? AND ts = ?
        """,
        (rs, n) ->
            new RollupRow(
                rs.getObject("expiry", LocalDate.class),
                rs.getBigDecimal("strike"),
                rs.getString("option_type"),
                rs.getBigDecimal("iv"),
                rs.getBigDecimal("spot_price")),
        underlying,
        Timestamp.from(ts.toInstant()));
  }

  /** Idempotent upsert — recompute reproduces identical rows, bumping only {@code computed_at}. */
  public void upsert(Summary s) {
    jdbc.update(
        """
        INSERT INTO iv_daily_summary
          (underlying, summary_date, atm_iv, iv_30d, spot_price, snapshot_count, computed_at)
        VALUES (?,?,?,?,?,?, now())
        ON CONFLICT (underlying, summary_date) DO UPDATE SET
          atm_iv = EXCLUDED.atm_iv, iv_30d = EXCLUDED.iv_30d, spot_price = EXCLUDED.spot_price,
          snapshot_count = EXCLUDED.snapshot_count, computed_at = now()
        """,
        s.underlying(),
        java.sql.Date.valueOf(s.summaryDate()),
        s.atmIv(),
        s.iv30d(),
        s.spotPrice(),
        s.snapshotCount());
  }

  /** The daily series for an underlying, oldest first. */
  public List<Summary> series(String underlying) {
    return jdbc.query(
        """
        SELECT underlying, summary_date, atm_iv, iv_30d, spot_price, snapshot_count, computed_at
        FROM iv_daily_summary WHERE underlying = ? ORDER BY summary_date
        """,
        (rs, n) ->
            new Summary(
                rs.getString("underlying"),
                rs.getObject("summary_date", LocalDate.class),
                rs.getBigDecimal("atm_iv"),
                rs.getBigDecimal("iv_30d"),
                rs.getBigDecimal("spot_price"),
                rs.getInt("snapshot_count"),
                rs.getObject("computed_at", OffsetDateTime.class)),
        underlying);
  }
}
