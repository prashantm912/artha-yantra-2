package in.arthayantra.marketdata.futures.analytics;

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

/** Query-time downsample of futures_oi_snapshots, per contract. */
@Repository
public class FuturesSnapshotReader {

  private final JdbcTemplate jdbc;

  public FuturesSnapshotReader(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public record FutPoint(
      OffsetDateTime bucket,
      String tradingsymbol,
      BigDecimal ltp,
      Long oi,
      Long oiChange,
      BigDecimal dayOpen,
      BigDecimal dayHigh,
      BigDecimal dayLow,
      BigDecimal prevClose) {}

  public List<FutPoint> series(
      String underlying, OiInterval interval, OffsetDateTime from, OffsetDateTime to) {
    String sql =
        "SELECT public.time_bucket(INTERVAL '"
            + interval.pgInterval()
            + "', ts, 'Asia/Kolkata') AS b, "
            + "  tradingsymbol, public.last(ltp, ts) AS ltp, public.last(oi, ts) AS oi, "
            + "  public.last(oi_change, ts) AS oi_change, "
            + "  public.last(day_open, ts) AS day_open, public.last(day_high, ts) AS day_high, "
            + "  public.last(day_low, ts) AS day_low, public.last(prev_close, ts) AS prev_close "
            + "FROM futures_oi_snapshots "
            + "WHERE underlying = ? AND ts >= ? AND ts < ? "
            + "GROUP BY b, tradingsymbol ORDER BY b, tradingsymbol";
    return jdbc.query(
        sql,
        (rs, n) ->
            new FutPoint(
                rs.getObject("b", OffsetDateTime.class),
                rs.getString("tradingsymbol"),
                rs.getBigDecimal("ltp"),
                rs.getObject("oi", Long.class),
                rs.getObject("oi_change", Long.class),
                rs.getBigDecimal("day_open"),
                rs.getBigDecimal("day_high"),
                rs.getBigDecimal("day_low"),
                rs.getBigDecimal("prev_close")),
        underlying,
        Timestamp.from(from.toInstant()),
        Timestamp.from(to.toInstant()));
  }

  /**
   * Most recent snapshot bucket per contract (clock-independent: anchors on the bucket CONTAINING
   * max(ts), bucket-aligned via the same IST {@code time_bucket} as {@link #series}, so it returns
   * exactly one bucket and never double-counts a contract's OI across two adjacent buckets).
   */
  public List<FutPoint> latest(String underlying, OiInterval interval) {
    return latest(underlying, interval, null);
  }

  /**
   * As {@link #latest(String, OiInterval)} but {@code date}-scoped: when {@code date} is non-null
   * the anchor is the newest bucket WITHIN that IST day (history mode); {@code null} = newest
   * overall (live).
   */
  public List<FutPoint> latest(String underlying, OiInterval interval, LocalDate date) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT public.time_bucket(INTERVAL '"
                + interval.pgInterval()
                + "', max(ts), 'Asia/Kolkata') AS b "
                + "FROM futures_oi_snapshots WHERE underlying = ?");
    List<Object> args = new ArrayList<>();
    args.add(underlying);
    appendDayFilter(sql, args, date);
    List<OffsetDateTime> bucket =
        jdbc.query(
            sql.toString(), (rs, n) -> rs.getObject("b", OffsetDateTime.class), args.toArray());
    OffsetDateTime bucketStart = bucket.isEmpty() ? null : bucket.get(0);
    if (bucketStart == null) {
      return List.of();
    }
    return series(underlying, interval, bucketStart, bucketStart.plus(interval.bucket()));
  }

  /** Appends an IST-day window predicate (history mode) when {@code date} is non-null. */
  static void appendDayFilter(StringBuilder sql, List<Object> args, LocalDate date) {
    if (date == null) {
      return;
    }
    OffsetDateTime start = date.atStartOfDay().atOffset(Ist.OFFSET);
    sql.append(" AND ts >= ? AND ts < ?");
    args.add(Timestamp.from(start.toInstant()));
    args.add(Timestamp.from(start.plusDays(1).toInstant()));
  }
}
