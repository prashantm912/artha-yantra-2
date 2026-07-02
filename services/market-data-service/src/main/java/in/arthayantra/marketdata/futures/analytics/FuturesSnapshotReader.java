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

/**
 * Query-time downsample of futures_oi_snapshots, per contract.
 *
 * <p>End-of-window bucketing (audit 2026-07-02 §9.1, T2): buckets shift {@code ts} back one second,
 * so a boundary-aligned capture labels into the window it TERMINATES — same convention as
 * {@code OptionsSnapshotReader} (see its class doc for the full rationale). Legacy mid-bucket
 * captures keep their buckets; the raw-ts window predicates shift the same second.
 */
@Repository
public class FuturesSnapshotReader {

  private final JdbcTemplate jdbc;

  public FuturesSnapshotReader(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public record FutPoint(
      OffsetDateTime bucket,
      String underlying,
      String tradingsymbol,
      BigDecimal ltp,
      Long oi,
      Long oiChange,
      BigDecimal dayOpen,
      BigDecimal dayHigh,
      BigDecimal dayLow,
      BigDecimal prevClose,
      Long volume,
      LocalDate expiry) {}

  /** One contract's per-IST-day EOD rollup (from the intraday snapshots). */
  public record EodRow(
      String tradingsymbol,
      LocalDate tradeDate,
      BigDecimal open,
      BigDecimal high,
      BigDecimal low,
      BigDecimal close,
      long oiClose,
      long oiChange,
      long volume) {}

  public List<FutPoint> series(
      String underlying, OiInterval interval, OffsetDateTime from, OffsetDateTime to) {
    return seriesAll(List.of(underlying), interval, from, to);
  }

  /**
   * As {@link #series} but across a SET of underlyings in one query (the bank-stock grid reads the
   * whole sector at once). Each {@link FutPoint} carries its {@code underlying}, so callers can fold
   * the front contract per underlying.
   */
  public List<FutPoint> seriesAll(
      List<String> underlyings, OiInterval interval, OffsetDateTime from, OffsetDateTime to) {
    if (underlyings.isEmpty()) {
      return List.of();
    }
    String placeholders = String.join(",", java.util.Collections.nCopies(underlyings.size(), "?"));
    String sql =
        "SELECT public.time_bucket(INTERVAL '"
            + interval.pgInterval()
            + "', ts - INTERVAL '1 second', 'Asia/Kolkata') AS b, "
            + "  underlying, tradingsymbol, public.last(ltp, ts) AS ltp, public.last(oi, ts) AS oi, "
            + "  public.last(oi_change, ts) AS oi_change, "
            + "  public.last(day_open, ts) AS day_open, public.last(day_high, ts) AS day_high, "
            + "  public.last(day_low, ts) AS day_low, public.last(prev_close, ts) AS prev_close, "
            + "  public.last(volume, ts) AS volume, public.last(expiry, ts) AS expiry "
            + "FROM futures_oi_snapshots "
            + "WHERE underlying IN ("
            + placeholders
            + ") AND ts >= ? AND ts < ? "
            + "GROUP BY b, underlying, tradingsymbol ORDER BY b, underlying, tradingsymbol";
    List<Object> args = new ArrayList<>(underlyings);
    args.add(Timestamp.from(from.plusSeconds(1).toInstant()));
    args.add(Timestamp.from(to.plusSeconds(1).toInstant()));
    return jdbc.query(
        sql,
        (rs, n) ->
            new FutPoint(
                rs.getObject("b", OffsetDateTime.class),
                rs.getString("underlying"),
                rs.getString("tradingsymbol"),
                rs.getBigDecimal("ltp"),
                rs.getObject("oi", Long.class),
                rs.getObject("oi_change", Long.class),
                rs.getBigDecimal("day_open"),
                rs.getBigDecimal("day_high"),
                rs.getBigDecimal("day_low"),
                rs.getBigDecimal("prev_close"),
                rs.getObject("volume", Long.class),
                rs.getObject("expiry", LocalDate.class)),
        args.toArray());
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
                + "', max(ts) - INTERVAL '1 second', 'Asia/Kolkata') AS b "
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

  /** Two most-recent snapshot buckets per contract (newest + the prior that holds data). */
  public List<FutPoint> latestPair(String underlying, OiInterval interval) {
    return latestPair(underlying, interval, null);
  }

  /** As {@link #latestPair(String, OiInterval)} but {@code date}-scoped (history mode). */
  public List<FutPoint> latestPair(String underlying, OiInterval interval, LocalDate date) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT DISTINCT public.time_bucket(INTERVAL '"
                + interval.pgInterval()
                + "', ts - INTERVAL '1 second', 'Asia/Kolkata') AS b "
                + "FROM futures_oi_snapshots WHERE underlying = ?");
    List<Object> args = new ArrayList<>();
    args.add(underlying);
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
    return series(underlying, interval, earliest, newest.plus(interval.bucket()));
  }

  /**
   * Two most-recent snapshot buckets across a SET of underlyings (the bank-stock grid). All
   * underlyings ride the same capture pass, so the global newest-two buckets hold every contract's
   * newest + prior point; {@link #seriesAll} then returns them tagged with {@code underlying}.
   */
  public List<FutPoint> latestPairAll(
      List<String> underlyings, OiInterval interval, LocalDate date) {
    if (underlyings.isEmpty()) {
      return List.of();
    }
    String placeholders = String.join(",", java.util.Collections.nCopies(underlyings.size(), "?"));
    StringBuilder sql =
        new StringBuilder(
            "SELECT DISTINCT public.time_bucket(INTERVAL '"
                + interval.pgInterval()
                + "', ts - INTERVAL '1 second', 'Asia/Kolkata') AS b "
                + "FROM futures_oi_snapshots WHERE underlying IN ("
                + placeholders
                + ")");
    List<Object> args = new ArrayList<>(underlyings);
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
    return seriesAll(underlyings, interval, earliest, newest.plus(interval.bucket()));
  }

  /**
   * Per-contract per-IST-day EOD rollup over [{@code from}, {@code to}] (inclusive): open/high/low
   * (from the captured day range), close = last ltp, OI close, net OI-change and day volume.
   */
  public List<EodRow> eod(String underlying, LocalDate from, LocalDate to) {
    OffsetDateTime start = from.atStartOfDay().atOffset(Ist.OFFSET);
    OffsetDateTime end = to.plusDays(1).atStartOfDay().atOffset(Ist.OFFSET);
    String sql =
        "SELECT tradingsymbol, (ts AT TIME ZONE 'Asia/Kolkata')::date AS d, "
            + "  public.last(day_open, ts) AS o, max(day_high) AS h, min(day_low) AS l, "
            + "  public.last(ltp, ts) AS c, public.last(oi, ts) AS oi_close, "
            + "  COALESCE(sum(oi_change), 0) AS oi_chg, public.last(volume, ts) AS vol "
            + "FROM futures_oi_snapshots "
            + "WHERE underlying = ? AND ts >= ? AND ts < ? "
            + "GROUP BY tradingsymbol, d ORDER BY d, tradingsymbol";
    return jdbc.query(
        sql,
        (rs, n) ->
            new EodRow(
                rs.getString("tradingsymbol"),
                rs.getObject("d", LocalDate.class),
                rs.getBigDecimal("o"),
                rs.getBigDecimal("h"),
                rs.getBigDecimal("l"),
                rs.getBigDecimal("c"),
                rs.getLong("oi_close"),
                rs.getLong("oi_chg"),
                rs.getLong("vol")),
        underlying,
        Timestamp.from(start.toInstant()),
        Timestamp.from(end.toInstant()));
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
