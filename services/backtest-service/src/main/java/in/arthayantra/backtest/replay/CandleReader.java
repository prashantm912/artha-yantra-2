package in.arthayantra.backtest.replay;

import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.EngineSeries;
import java.time.OffsetDateTime;
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
