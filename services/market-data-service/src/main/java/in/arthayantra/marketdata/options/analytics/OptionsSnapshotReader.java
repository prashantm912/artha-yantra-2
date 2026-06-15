package in.arthayantra.marketdata.options.analytics;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketdata.options.OiInterval;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Query-time downsample of option_chain_snapshots (NO cagg — decision 2026-06-15). */
@Repository
public class OptionsSnapshotReader {

  private final JdbcTemplate jdbc;

  public OptionsSnapshotReader(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** One downsampled point per (bucket, strike, optionType): last() of each point-in-time stat. */
  public record StrikePoint(
      OffsetDateTime bucket,
      BigDecimal strike,
      String optionType,
      BigDecimal ltp,
      Long oi,
      Long oiChange,
      BigDecimal iv,
      BigDecimal spot) {}

  public List<StrikePoint> series(
      String underlying,
      LocalDate expiry,
      OiInterval interval,
      OffsetDateTime from,
      OffsetDateTime to) {
    String sql =
        "SELECT public.time_bucket(INTERVAL '"
            + interval.pgInterval()
            + "', ts, 'Asia/Kolkata') AS b, "
            + "  strike, option_type, "
            + "  public.last(ltp, ts) AS ltp, public.last(oi, ts) AS oi, "
            + "  public.last(oi_change, ts) AS oi_change, public.last(iv, ts) AS iv, "
            + "  public.last(spot_price, ts) AS spot "
            + "FROM options_chain_snapshots "
            + "WHERE underlying = ? AND expiry = ? AND ts >= ? AND ts < ? "
            + "GROUP BY b, strike, option_type "
            + "ORDER BY b, strike, option_type";
    return jdbc.query(
        sql,
        (rs, n) ->
            new StrikePoint(
                rs.getObject("b", OffsetDateTime.class),
                rs.getBigDecimal("strike"),
                rs.getString("option_type"),
                rs.getBigDecimal("ltp"),
                rs.getObject("oi", Long.class),
                rs.getObject("oi_change", Long.class),
                rs.getBigDecimal("iv"),
                rs.getBigDecimal("spot")),
        underlying,
        java.sql.Date.valueOf(expiry),
        Timestamp.from(from.toInstant()),
        Timestamp.from(to.toInstant()));
  }

  /**
   * The most recent snapshot bucket's rows (for "current" analytics). Anchors on the bucket
   * CONTAINING max(ts) — bucket-aligned via the same IST {@code time_bucket} as {@link #series} —
   * so the window is exactly ONE bucket and a strike's point-in-time OI is never double-counted
   * across two adjacent buckets (which a rolling {@code [maxTs - width, maxTs]} window would do
   * when the snapshot cadence is not bucket-aligned). Empty if none.
   */
  public List<StrikePoint> latest(String underlying, LocalDate expiry, OiInterval interval) {
    return latest(underlying, expiry, interval, null);
  }

  /**
   * As {@link #latest(String, LocalDate, OiInterval)} but {@code date}-scoped: when {@code date}
   * is non-null the anchor is the newest bucket WITHIN that IST day (history mode); {@code null}
   * anchors on the newest bucket overall (live).
   */
  public List<StrikePoint> latest(
      String underlying, LocalDate expiry, OiInterval interval, LocalDate date) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT public.time_bucket(INTERVAL '"
                + interval.pgInterval()
                + "', max(ts), 'Asia/Kolkata') AS b "
                + "FROM options_chain_snapshots WHERE underlying = ? AND expiry = ?");
    List<Object> args = new ArrayList<>();
    args.add(underlying);
    args.add(java.sql.Date.valueOf(expiry));
    appendDayFilter(sql, args, date);
    List<OffsetDateTime> bucket =
        jdbc.query(
            sql.toString(), (rs, n) -> rs.getObject("b", OffsetDateTime.class), args.toArray());
    OffsetDateTime bucketStart = bucket.isEmpty() ? null : bucket.get(0);
    if (bucketStart == null) {
      return List.of();
    }
    return series(underlying, expiry, interval, bucketStart, bucketStart.plus(interval.bucket()));
  }

  /**
   * Rows for the two most-recent snapshot buckets (newest + the prior captured bucket), used to
   * compute interval deltas (LTP-delta, OI-delta) for spurt. Robust to gaps: it picks the two
   * most-recent buckets that ACTUALLY hold data, not two wall-clock-adjacent slots. Empty if no
   * snapshot; a single bucket if only one exists (the caller then has no prior to diff against).
   */
  public List<StrikePoint> latestPair(String underlying, LocalDate expiry, OiInterval interval) {
    return latestPair(underlying, expiry, interval, null);
  }

  /** As {@link #latestPair(String, LocalDate, OiInterval)} but {@code date}-scoped (history mode). */
  public List<StrikePoint> latestPair(
      String underlying, LocalDate expiry, OiInterval interval, LocalDate date) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT DISTINCT public.time_bucket(INTERVAL '"
                + interval.pgInterval()
                + "', ts, 'Asia/Kolkata') AS b "
                + "FROM options_chain_snapshots WHERE underlying = ? AND expiry = ?");
    List<Object> args = new ArrayList<>();
    args.add(underlying);
    args.add(java.sql.Date.valueOf(expiry));
    appendDayFilter(sql, args, date);
    sql.append(" ORDER BY b DESC LIMIT 2");
    List<OffsetDateTime> buckets =
        jdbc.query(
            sql.toString(), (rs, n) -> rs.getObject("b", OffsetDateTime.class), args.toArray());
    if (buckets.isEmpty()) {
      return List.of();
    }
    OffsetDateTime newest = buckets.get(0);
    OffsetDateTime earliest = buckets.get(buckets.size() - 1);
    return series(underlying, expiry, interval, earliest, newest.plus(interval.bucket()));
  }

  /** Appends an IST-day window predicate (history mode) when {@code date} is non-null. */
  private static void appendDayFilter(StringBuilder sql, List<Object> args, LocalDate date) {
    if (date == null) {
      return;
    }
    OffsetDateTime start = date.atStartOfDay().atOffset(Ist.OFFSET);
    sql.append(" AND ts >= ? AND ts < ?");
    args.add(Timestamp.from(start.toInstant()));
    args.add(Timestamp.from(start.plusDays(1).toInstant()));
  }
}
