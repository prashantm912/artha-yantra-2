package in.arthayantra.backtest.replay;

import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.EngineSeries;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads candles DIRECTLY from the {@code marketdata} schema read-only (D10) — never over REST. 1m
 * comes from the {@code candles} hypertable; 5m/15m/1h/1d/1w from the continuous aggregates. Buckets
 * are normalized to the IST (+05:30) offset the engine and the golden fixtures use, so replay and
 * live evaluate byte-identical bars.
 */
@Component
public class CandleReader {

  private final JdbcTemplate jdbc;

  /** Wires JDBC (connected as the owner; the marketdata read is via the CD-1 grant model). */
  public CandleReader(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Reads one series at one interval over {@code [from, to)} in bucket order. */
  public List<EngineCandle> read(
      String exchange, String tradingsymbol, String interval, OffsetDateTime from, OffsetDateTime to) {
    String sql =
        "1m".equals(interval)
            ? "SELECT bucket, open, high, low, close, volume, oi FROM marketdata.candles "
                + "WHERE exchange=? AND tradingsymbol=? AND \"interval\"='1m' AND bucket >= ? AND bucket < ? "
                + "ORDER BY bucket"
            : "SELECT bucket, open, high, low, close, volume, oi FROM marketdata.candles_" + interval + " "
                + "WHERE exchange=? AND tradingsymbol=? AND bucket >= ? AND bucket < ? ORDER BY bucket";
    return jdbc.query(
        sql,
        (rs, rowNum) ->
            new EngineCandle(
                rs.getTimestamp("bucket").toInstant().atOffset(EngineSeries.IST),
                rs.getBigDecimal("open"),
                rs.getBigDecimal("high"),
                rs.getBigDecimal("low"),
                rs.getBigDecimal("close"),
                rs.getLong("volume"),
                rs.getBigDecimal("oi")),
        exchange,
        tradingsymbol,
        from,
        to);
  }

  /** Counts present 1m buckets over {@code [from, to)} — the pre-flight coverage numerator. */
  public long count1mBuckets(
      String exchange, String tradingsymbol, OffsetDateTime from, OffsetDateTime to) {
    Long count =
        jdbc.queryForObject(
            "SELECT count(*) FROM marketdata.candles "
                + "WHERE exchange=? AND tradingsymbol=? AND \"interval\"='1m' AND bucket >= ? AND bucket < ?",
            Long.class,
            exchange,
            tradingsymbol,
            from,
            to);
    return count == null ? 0L : count;
  }

  /**
   * Counts present native daily ({@code 1d}) benchmark buckets STRICTLY BEFORE {@code before} — the
   * benchmark warm-up depth numerator for regime attribution (guard 6, §D.4). Reads the base {@code
   * candles} hypertable read-only at {@code interval='1d'} (native daily bars from Kite's daily API
   * live here, alongside the {@code candles_1d} cagg rolled from 1m).
   */
  public long count1dBucketsBefore(
      String exchange, String tradingsymbol, OffsetDateTime before) {
    Long count =
        jdbc.queryForObject(
            "SELECT count(*) FROM marketdata.candles "
                + "WHERE exchange=? AND tradingsymbol=? AND \"interval\"='1d' AND bucket < ?",
            Long.class,
            exchange,
            tradingsymbol,
            before);
    return count == null ? 0L : count;
  }

  /**
   * Reads the benchmark daily series with sufficient warm-up: the {@code warmupSessions} most-recent
   * native {@code 1d} buckets BEFORE {@code from}, then every {@code 1d} bucket in {@code [from, to)}
   * — in bucket order (oldest first). The warm-up prefix feeds the 200-day SMA + 1-year vol median;
   * the in-window suffix carries the labeled entry days. Reads {@code marketdata.candles} read-only
   * at {@code interval='1d'} (D10).
   */
  public List<EngineCandle> readDailyWithWarmup(
      String exchange,
      String tradingsymbol,
      OffsetDateTime from,
      OffsetDateTime to,
      int warmupSessions) {
    List<EngineCandle> warmup =
        jdbc.query(
            "SELECT bucket, open, high, low, close, volume, oi FROM marketdata.candles "
                + "WHERE exchange=? AND tradingsymbol=? AND \"interval\"='1d' AND bucket < ? "
                + "ORDER BY bucket DESC LIMIT ?",
            (rs, rowNum) -> mapDaily(rs),
            exchange,
            tradingsymbol,
            from,
            warmupSessions);
    Collections.reverse(warmup); // back to ascending bucket order
    List<EngineCandle> inWindow =
        jdbc.query(
            "SELECT bucket, open, high, low, close, volume, oi FROM marketdata.candles "
                + "WHERE exchange=? AND tradingsymbol=? AND \"interval\"='1d' AND bucket >= ? "
                + "AND bucket < ? ORDER BY bucket",
            (rs, rowNum) -> mapDaily(rs),
            exchange,
            tradingsymbol,
            from,
            to);
    List<EngineCandle> all = new ArrayList<>(warmup.size() + inWindow.size());
    all.addAll(warmup);
    all.addAll(inWindow);
    return all;
  }

  /**
   * Reads the NATIVE daily ({@code 1d}) series over {@code [from, to)} in bucket order — the SAME
   * dense {@code marketdata.candles}@{@code 1d} source {@link #readDailyWithWarmup} and the regime
   * pre-flight ({@link #count1dBucketsBefore}) read (Kite's daily API + bhavcopy projection), NOT the
   * 1m-rolled {@code candles_1d} cagg that {@link #read} serves (sparse until 1m history accrues on a
   * fresh boot; D7). No warm-up prefix (unlike {@link #readDailyWithWarmup}): exactly the {@code
   * [from, to)} bars, so it is a drop-in source-swap for {@code read(..,"1d",..)} with byte-identical
   * range semantics. Backtest 1d context + benchmark reads use this so they land on the same daily
   * bars the warm-up/regime plane and the chart draw.
   */
  public List<EngineCandle> readDaily(
      String exchange, String tradingsymbol, OffsetDateTime from, OffsetDateTime to) {
    return jdbc.query(
        "SELECT bucket, open, high, low, close, volume, oi FROM marketdata.candles "
            + "WHERE exchange=? AND tradingsymbol=? AND \"interval\"='1d' AND bucket >= ? AND bucket < ? "
            + "ORDER BY bucket",
        (rs, rowNum) -> mapDaily(rs),
        exchange,
        tradingsymbol,
        from,
        to);
  }

  private static EngineCandle mapDaily(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new EngineCandle(
        rs.getTimestamp("bucket").toInstant().atOffset(EngineSeries.IST),
        rs.getBigDecimal("open"),
        rs.getBigDecimal("high"),
        rs.getBigDecimal("low"),
        rs.getBigDecimal("close"),
        rs.getLong("volume"),
        rs.getBigDecimal("oi"));
  }

  /**
   * The latest cached 1m bucket for a series, or {@code null} when nothing is cached — the upper
   * bound of the suggested clean stress window (§D.6). Reads the base {@code candles} hypertable.
   */
  public OffsetDateTime latestCachedBucket(String exchange, String tradingsymbol) {
    return jdbc.query(
            "SELECT max(bucket) AS latest FROM marketdata.candles "
                + "WHERE exchange=? AND tradingsymbol=? AND \"interval\"='1m'",
            (rs, n) -> {
              var ts = rs.getTimestamp("latest");
              return ts == null ? null : ts.toInstant().atOffset(EngineSeries.IST);
            },
            exchange,
            tradingsymbol)
        .stream()
        .findFirst()
        .orElse(null);
  }

  /** Max {@code fetched_at} over the read window — leg of the {@code data_hash} tuple. */
  public OffsetDateTime maxFetchedAt(
      String exchange, String tradingsymbol, OffsetDateTime from, OffsetDateTime to) {
    return jdbc.queryForObject(
        "SELECT max(fetched_at) FROM marketdata.candles "
            + "WHERE exchange=? AND tradingsymbol=? AND \"interval\"='1m' AND bucket >= ? AND bucket < ?",
        (rs, n) -> {
          var ts = rs.getTimestamp(1);
          return ts == null ? null : ts.toInstant().atOffset(EngineSeries.IST);
        },
        exchange,
        tradingsymbol,
        from,
        to);
  }
}
