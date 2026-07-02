package in.arthayantra.marketdata.options;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC access to the {@code options_chain_snapshots} hypertable (B-7). Raw quote fields persist
 * UNCONDITIONALLY; IV/Greeks trim to NUMERIC(12,6); provenance (price_source, forward_price,
 * risk_free_rate) lands on every row so every stored IV is exactly recomputable.
 */
@Repository
public class OptionsSnapshotRepository {

  /** One snapshot row — raw quote always, solver outputs nullable with their reason. */
  public record SnapshotRow(
      OffsetDateTime ts,
      String underlying,
      LocalDate expiry,
      BigDecimal strike,
      String optionType,
      String tradingsymbol,
      BigDecimal ltp,
      BigDecimal bid,
      BigDecimal ask,
      Long volume,
      Long oi,
      Long oiChange,
      BigDecimal spotPrice,
      BigDecimal iv,
      BigDecimal delta,
      BigDecimal gamma,
      BigDecimal theta,
      BigDecimal vega,
      BigDecimal rho,
      String ivReason,
      String priceSource,
      BigDecimal forwardPrice,
      BigDecimal riskFreeRate) {}

  private final JdbcTemplate jdbc;

  /** Wires the marketdata datasource. */
  public OptionsSnapshotRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Batch-inserts one LIVE snapshot pass (source defaults to {@code LIVE}); idempotent on the PK. */
  public void insertAll(List<SnapshotRow> rows) {
    jdbc.batchUpdate(
        """
        INSERT INTO options_chain_snapshots
          (ts, underlying, expiry, strike, option_type, tradingsymbol, ltp, bid, ask, volume, oi,
           oi_change, spot_price, iv, delta, gamma, theta, vega, rho, iv_reason, price_source,
           forward_price, risk_free_rate)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        ON CONFLICT (ts, underlying, expiry, strike, option_type) DO NOTHING
        """,
        rows,
        500,
        (ps, row) -> bind(ps, row));
  }

  /**
   * Batch-inserts BACKFILL-provenance rows (the OI-backfill importer, data-foundation milestone) —
   * identical columns/binding, but {@code source='BACKFILL'} so backfilled OI is never confused with
   * live capture (or purged). Idempotent on the PK, so a re-run is a no-op.
   */
  public void insertBackfill(List<SnapshotRow> rows) {
    jdbc.batchUpdate(
        """
        INSERT INTO options_chain_snapshots
          (ts, underlying, expiry, strike, option_type, tradingsymbol, ltp, bid, ask, volume, oi,
           oi_change, spot_price, iv, delta, gamma, theta, vega, rho, iv_reason, price_source,
           forward_price, risk_free_rate, source)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'BACKFILL')
        ON CONFLICT (ts, underlying, expiry, strike, option_type) DO NOTHING
        """,
        rows,
        500,
        (ps, row) -> bind(ps, row));
  }

  /**
   * Batch-inserts UPSTOX-1M-provenance rows (the on-demand stock-chain warm, audit §9.3 Wave 5) —
   * identical columns/binding, {@code source='UPSTOX_1M'} so candle-derived stock rows are never
   * confused with live quote capture. Idempotent on the PK, so a re-warm only adds NEW buckets
   * (incremental top-up).
   */
  public int insertUpstoxDerived(List<SnapshotRow> rows) {
    int[][] counts =
        jdbc.batchUpdate(
            """
            INSERT INTO options_chain_snapshots
              (ts, underlying, expiry, strike, option_type, tradingsymbol, ltp, bid, ask, volume, oi,
               oi_change, spot_price, iv, delta, gamma, theta, vega, rho, iv_reason, price_source,
               forward_price, risk_free_rate, source)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'UPSTOX_1M')
            ON CONFLICT (ts, underlying, expiry, strike, option_type) DO NOTHING
            """,
            rows,
            500,
            (ps, row) -> bind(ps, row));
    int inserted = 0;
    for (int[] batch : counts) {
      for (int c : batch) {
        inserted += Math.max(0, c); // DO NOTHING reports 0 for a conflicting row
      }
    }
    return inserted;
  }

  /** Binds the 23 raw/solver columns of one snapshot row (shared by live + backfill inserts). */
  private static void bind(java.sql.PreparedStatement ps, SnapshotRow row) throws java.sql.SQLException {
    ps.setTimestamp(1, Timestamp.from(row.ts().toInstant()));
    ps.setString(2, row.underlying());
    ps.setDate(3, java.sql.Date.valueOf(row.expiry()));
    ps.setBigDecimal(4, row.strike());
    ps.setString(5, row.optionType());
    ps.setString(6, row.tradingsymbol());
    ps.setBigDecimal(7, row.ltp());
    ps.setBigDecimal(8, row.bid());
    ps.setBigDecimal(9, row.ask());
    ps.setObject(10, row.volume());
    ps.setObject(11, row.oi());
    ps.setObject(12, row.oiChange());
    ps.setBigDecimal(13, row.spotPrice());
    ps.setBigDecimal(14, scale6(row.iv()));
    ps.setBigDecimal(15, scale6(row.delta()));
    ps.setBigDecimal(16, scale6(row.gamma()));
    ps.setBigDecimal(17, scale6(row.theta()));
    ps.setBigDecimal(18, scale6(row.vega()));
    ps.setBigDecimal(19, scale6(row.rho()));
    ps.setString(20, row.ivReason());
    ps.setString(21, row.priceSource());
    ps.setBigDecimal(22, row.forwardPrice());
    ps.setBigDecimal(23, scale6(row.riskFreeRate()));
  }

  /** The stored snapshot timestamp nearest to {@code at} (either side). */
  public Optional<OffsetDateTime> nearestSnapshotTs(
      String underlying, LocalDate expiry, OffsetDateTime at) {
    List<OffsetDateTime> ts =
        jdbc.query(
            """
            SELECT ts FROM options_chain_snapshots
            WHERE underlying = ? AND expiry = ?
            ORDER BY ABS(EXTRACT(EPOCH FROM (ts - ?::timestamptz))) LIMIT 1
            """,
            (rs, n) -> rs.getObject("ts", OffsetDateTime.class),
            underlying,
            java.sql.Date.valueOf(expiry),
            Timestamp.from(at.toInstant()));
    return ts.isEmpty() ? Optional.empty() : Optional.of(ts.get(0));
  }

  /** Every row of one stored snapshot. */
  public List<SnapshotRow> rowsAt(String underlying, LocalDate expiry, OffsetDateTime ts) {
    return jdbc.query(
        """
        SELECT * FROM options_chain_snapshots
        WHERE underlying = ? AND expiry = ? AND ts = ?
        ORDER BY strike, option_type
        """,
        (rs, n) ->
            new SnapshotRow(
                rs.getObject("ts", OffsetDateTime.class),
                rs.getString("underlying"),
                rs.getDate("expiry").toLocalDate(),
                rs.getBigDecimal("strike"),
                rs.getString("option_type"),
                rs.getString("tradingsymbol"),
                rs.getBigDecimal("ltp"),
                rs.getBigDecimal("bid"),
                rs.getBigDecimal("ask"),
                rs.getObject("volume", Long.class),
                rs.getObject("oi", Long.class),
                rs.getObject("oi_change", Long.class),
                rs.getBigDecimal("spot_price"),
                rs.getBigDecimal("iv"),
                rs.getBigDecimal("delta"),
                rs.getBigDecimal("gamma"),
                rs.getBigDecimal("theta"),
                rs.getBigDecimal("vega"),
                rs.getBigDecimal("rho"),
                rs.getString("iv_reason"),
                rs.getString("price_source"),
                rs.getBigDecimal("forward_price"),
                rs.getBigDecimal("risk_free_rate")),
        underlying,
        java.sql.Date.valueOf(expiry),
        Timestamp.from(ts.toInstant()));
  }

  private static BigDecimal scale6(BigDecimal value) {
    return value == null ? null : value.setScale(6, RoundingMode.HALF_UP);
  }
}
