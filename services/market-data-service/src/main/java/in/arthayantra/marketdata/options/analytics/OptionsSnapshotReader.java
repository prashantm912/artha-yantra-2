package in.arthayantra.marketdata.options.analytics;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.options.OiInterval;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Query-time downsample of option_chain_snapshots (NO cagg — decision 2026-06-15). */
@Repository
public class OptionsSnapshotReader {

  private final JdbcTemplate jdbc;
  private final MarketCalendar calendar;

  public OptionsSnapshotReader(JdbcTemplate jdbc, MarketCalendar calendar) {
    this.jdbc = jdbc;
    this.calendar = calendar;
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
      BigDecimal spot,
      Long volume) {}

  /**
   * Session OHLC of the per-strike option premium ({@code ltp}) plus volume, for Open=High grading
   * (Siva #2). One row per (strike, optionType) over a single IST trading {@code session}, derived
   * from that session's snapshots time_bucketed to {@code intervalMinutes}.
   *
   * <p>{@code volume} in options_chain_snapshots is the broker's CUMULATIVE day volume (Kite
   * quote.volume); hence {@code dayVolume = last - first} cumulative diff and {@code declineVolume}
   * sums per-bucket cumulative diffs over the falling buckets — see {@link #sessionStats}.
   */
  public record PerStrikeSessionStat(
      BigDecimal strike,
      String optionType,
      BigDecimal open,
      BigDecimal high,
      BigDecimal low,
      BigDecimal last,
      Long dayVolume,
      Long declineVolume,
      BigDecimal prevClose) {}

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
            + "  public.last(spot_price, ts) AS spot, public.last(volume, ts) AS volume "
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
                rs.getBigDecimal("spot"),
                rs.getObject("volume", Long.class)),
        underlying,
        java.sql.Date.valueOf(expiry),
        Timestamp.from(from.toInstant()),
        Timestamp.from(to.toInstant()));
  }

  /**
   * Per-strike session OHLC of the option premium ({@code ltp}) + volume, for Open=High grading.
   *
   * <p>For the IST trading {@code session}, each (strike, optionType)'s session buckets (downsampled
   * to {@code intervalMinutes}, oldest-first) yield: {@code open} = first bucket ltp, {@code high}/
   * {@code low} = max/min bucket ltp, {@code last} = newest bucket ltp.
   *
   * <p>{@code volume} is CUMULATIVE day volume (Kite quote.volume — verified in
   * OptionsSnapshotService.addRow, which writes {@code leg.volume()} straight from the quote), so
   * {@code dayVolume = last - first} of the bucketed cumulative volume (guarded for nulls).
   * {@code declineVolume} sums the per-bucket INTERVAL volume ({@code volume[i] - volume[i-1]}) over
   * the buckets whose ltp dropped below the running max-so-far (the "premium fell on volume"
   * candles); the first bucket has no prior so contributes no interval volume.
   *
   * <p>{@code prevClose} = the newest bucket ltp of the PRIOR trading session (via
   * {@link MarketCalendar#previousTradingDay}) for that strike, or null if none.
   *
   * <p>{@code intervalMinutes} can only bucket COARSER than the ~5-min capture cadence; a finer
   * value just yields ~1-point buckets (a bucket then approximates one capture). Empty list if the
   * session holds no snapshots.
   */
  public List<PerStrikeSessionStat> sessionStats(
      String underlying, LocalDate expiry, LocalDate session, int intervalMinutes) {
    OiInterval interval = OiInterval.parse(intervalMinutes + "m");
    List<StrikePoint> cur = dayBuckets(underlying, expiry, session, interval);
    if (cur.isEmpty()) {
      return List.of();
    }
    Map<String, BigDecimal> prevClose =
        prevCloseByStrike(underlying, expiry, session, interval);

    // group by (strike, optionType) preserving the oldest-first bucket order from series()
    Map<String, List<StrikePoint>> byStrike = new LinkedHashMap<>();
    for (StrikePoint p : cur) {
      byStrike.computeIfAbsent(key(p.strike(), p.optionType()), k -> new ArrayList<>()).add(p);
    }

    List<PerStrikeSessionStat> out = new ArrayList<>();
    for (List<StrikePoint> pts : byStrike.values()) {
      StrikePoint first = pts.get(0);
      StrikePoint lastPt = pts.get(pts.size() - 1);
      BigDecimal high = first.ltp();
      BigDecimal low = first.ltp();
      BigDecimal runningHigh = first.ltp();
      Long declineVolume = null;
      for (int i = 0; i < pts.size(); i++) {
        BigDecimal ltp = pts.get(i).ltp();
        if (ltp != null) {
          if (high == null || ltp.compareTo(high) > 0) {
            high = ltp;
          }
          if (low == null || ltp.compareTo(low) < 0) {
            low = ltp;
          }
        }
        if (i > 0 && ltp != null && runningHigh != null && ltp.compareTo(runningHigh) < 0) {
          Long iv = intervalVolume(pts.get(i - 1).volume(), pts.get(i).volume());
          // Clamp non-monotone cumulative volume (broker reset / stale last()): a negative
          // interval would wrongly SUBTRACT from declineVolume, so floor it at zero.
          if (iv != null) {
            iv = Math.max(0L, iv);
          }
          declineVolume = add(declineVolume, iv);
        }
        if (ltp != null && (runningHigh == null || ltp.compareTo(runningHigh) > 0)) {
          runningHigh = ltp;
        }
      }
      Long dayVolume = intervalVolume(first.volume(), lastPt.volume());
      out.add(
          new PerStrikeSessionStat(
              first.strike(),
              first.optionType(),
              first.ltp(),
              high,
              low,
              lastPt.ltp(),
              dayVolume,
              declineVolume,
              prevClose.get(key(first.strike(), first.optionType()))));
    }
    return out;
  }

  /** Session buckets oldest-first via the IST-day window of {@link #series}. */
  private List<StrikePoint> dayBuckets(
      String underlying, LocalDate expiry, LocalDate session, OiInterval interval) {
    OffsetDateTime start = session.atStartOfDay().atOffset(Ist.OFFSET);
    return series(underlying, expiry, interval, start, start.plusDays(1));
  }

  /** Newest-bucket ltp per (strike, optionType) of the prior trading session — empty map if none. */
  private Map<String, BigDecimal> prevCloseByStrike(
      String underlying, LocalDate expiry, LocalDate session, OiInterval interval) {
    LocalDate prior;
    try {
      prior = calendar.previousTradingDay(session);
    } catch (IllegalArgumentException uncoveredYear) {
      return Map.of();
    }
    Map<String, BigDecimal> close = new LinkedHashMap<>();
    for (StrikePoint p : dayBuckets(underlying, expiry, prior, interval)) {
      // series() is oldest-first, so the last write per strike is the newest bucket
      close.put(key(p.strike(), p.optionType()), p.ltp());
    }
    return close;
  }

  private static String key(BigDecimal strike, String optionType) {
    return strike.stripTrailingZeros().toPlainString() + "|" + optionType;
  }

  /** Cumulative diff {@code b - a}, null if either side is null. */
  private static Long intervalVolume(Long a, Long b) {
    if (a == null || b == null) {
      return null;
    }
    return b - a;
  }

  private static Long add(Long acc, Long v) {
    if (v == null) {
      return acc;
    }
    return acc == null ? v : acc + v;
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
