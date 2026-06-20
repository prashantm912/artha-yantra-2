package in.arthayantra.marketdata.candles;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC access to the candles hypertable (B-7). Writes are idempotent upserts on the natural PK
 * with the B-6 merge math: {@code high=GREATEST}, {@code low=LEAST}, close from the newest write,
 * open kept from the first — replayed ticks can never double-count because volume is a
 * cumulative-day delta computed upstream.
 */
@Repository
public class CandleRepository {

  private static final String UPSERT =
      """
      INSERT INTO candles
        (exchange, tradingsymbol, "interval", bucket, open, high, low, close, volume, oi, source, fetched_at)
      VALUES (?,?,?,?,?,?,?,?,?,?,?, now())
      ON CONFLICT (exchange, tradingsymbol, "interval", bucket) DO UPDATE SET
        high = GREATEST(candles.high, EXCLUDED.high),
        low = LEAST(candles.low, EXCLUDED.low),
        close = EXCLUDED.close,
        volume = EXCLUDED.volume,
        oi = COALESCE(EXCLUDED.oi, candles.oi),
        source = EXCLUDED.source,
        fetched_at = now()
      """;

  private final JdbcTemplate jdbc;

  /** Wires the marketdata datasource. */
  public CandleRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Upserts one bar. */
  public void upsert(Candle bar) {
    jdbc.update(
        UPSERT,
        bar.exchange(), bar.tradingsymbol(), bar.interval(),
        Timestamp.from(bar.bucket().toInstant()),
        bar.open(), bar.high(), bar.low(), bar.close(),
        bar.volume(), bar.oi(), bar.source());
  }

  /** Batched upsert for fetch pipelines (Phase 11). */
  public void upsertAll(List<Candle> bars) {
    jdbc.batchUpdate(
        UPSERT,
        bars,
        500,
        (ps, bar) -> {
          ps.setString(1, bar.exchange());
          ps.setString(2, bar.tradingsymbol());
          ps.setString(3, bar.interval());
          ps.setTimestamp(4, Timestamp.from(bar.bucket().toInstant()));
          ps.setBigDecimal(5, bar.open());
          ps.setBigDecimal(6, bar.high());
          ps.setBigDecimal(7, bar.low());
          ps.setBigDecimal(8, bar.close());
          ps.setLong(9, bar.volume());
          ps.setObject(10, bar.oi());
          ps.setString(11, bar.source());
        });
  }

  private static final String INSERT_IGNORE =
      """
      INSERT INTO candles
        (exchange, tradingsymbol, "interval", bucket, open, high, low, close, volume, oi, source, fetched_at)
      VALUES (?,?,?,?,?,?,?,?,?,?,?, now())
      ON CONFLICT (exchange, tradingsymbol, "interval", bucket) DO NOTHING
      """;

  /**
   * Batched insert that NEVER overwrites an existing bar (ON CONFLICT DO NOTHING) — the bulk EOD
   * bhavcopy projection (Phase A/B). {@code source} is not part of the candles PK, so a BHAVCOPY
   * 1d row and a KITE 1d row collide on {@code (exchange, tradingsymbol, "interval", bucket)};
   * DO NOTHING lets bhavcopy fill the universe long-tail without ever clobbering a Kite/live bar
   * that already owns that bucket.
   */
  public int insertIgnoreAll(List<Candle> bars) {
    int[][] counts =
        jdbc.batchUpdate(
            INSERT_IGNORE,
            bars,
            500,
            (ps, bar) -> {
              ps.setString(1, bar.exchange());
              ps.setString(2, bar.tradingsymbol());
              ps.setString(3, bar.interval());
              ps.setTimestamp(4, Timestamp.from(bar.bucket().toInstant()));
              ps.setBigDecimal(5, bar.open());
              ps.setBigDecimal(6, bar.high());
              ps.setBigDecimal(7, bar.low());
              ps.setBigDecimal(8, bar.close());
              ps.setLong(9, bar.volume());
              ps.setObject(10, bar.oi());
              ps.setString(11, bar.source());
            });
    int inserted = 0;
    for (int[] batch : counts) {
      for (int n : batch) {
        if (n > 0) {
          inserted += n;
        }
      }
    }
    return inserted;
  }

  /** Range read from the base hypertable (1m/1d rows). */
  public List<Candle> range(
      String exchange, String tradingsymbol, String interval, OffsetDateTime from, OffsetDateTime to) {
    return jdbc.query(
        """
        SELECT * FROM candles
        WHERE exchange = ? AND tradingsymbol = ? AND "interval" = ? AND bucket >= ? AND bucket < ?
        ORDER BY bucket
        """,
        (rs, n) ->
            new Candle(
                rs.getString("exchange"),
                rs.getString("tradingsymbol"),
                rs.getString("interval"),
                rs.getObject("bucket", OffsetDateTime.class),
                rs.getBigDecimal("open"),
                rs.getBigDecimal("high"),
                rs.getBigDecimal("low"),
                rs.getBigDecimal("close"),
                rs.getLong("volume"),
                rs.getObject("oi", Long.class),
                rs.getString("source")),
        exchange, tradingsymbol, interval,
        Timestamp.from(from.toInstant()), Timestamp.from(to.toInstant()));
  }

  /** Range read from a continuous aggregate ({@code candles_5m} … {@code candles_1w}). */
  public List<Candle> rangeFromAggregate(
      String view, String exchange, String tradingsymbol, OffsetDateTime from, OffsetDateTime to) {
    if (!List.of("candles_5m", "candles_15m", "candles_1h", "candles_1d", "candles_1w").contains(view)) {
      throw new IllegalArgumentException("unknown aggregate view " + view);
    }
    return jdbc.query(
        "SELECT * FROM " + view
            + " WHERE exchange = ? AND tradingsymbol = ? AND bucket >= ? AND bucket < ? ORDER BY bucket",
        (rs, n) ->
            new Candle(
                rs.getString("exchange"),
                rs.getString("tradingsymbol"),
                view.substring("candles_".length()),
                rs.getObject("bucket", OffsetDateTime.class),
                rs.getBigDecimal("open"),
                rs.getBigDecimal("high"),
                rs.getBigDecimal("low"),
                rs.getBigDecimal("close"),
                rs.getLong("volume"),
                rs.getObject("oi", Long.class),
                null),
        exchange, tradingsymbol,
        Timestamp.from(from.toInstant()), Timestamp.from(to.toInstant()));
  }

  /** Distinct present 1m bucket starts in a range (gap detection, Phase 11). */
  public List<OffsetDateTime> presentBuckets(
      String exchange, String tradingsymbol, String interval, OffsetDateTime from, OffsetDateTime to) {
    return jdbc.query(
        """
        SELECT bucket FROM candles
        WHERE exchange = ? AND tradingsymbol = ? AND "interval" = ? AND bucket >= ? AND bucket < ?
        ORDER BY bucket
        """,
        (rs, n) -> rs.getObject("bucket", OffsetDateTime.class),
        exchange, tradingsymbol, interval,
        Timestamp.from(from.toInstant()), Timestamp.from(to.toInstant()));
  }

  /**
   * Explicitly refreshes the derived aggregates over a backfilled window (B-17 step 3 applied to
   * gap backfill): once a cagg's policy has run, its watermark sits near {@code now} and history
   * inserted BEHIND it is neither in the materialization (policies only cover their
   * {@code start_offset} window) nor in the real-time union — without this, backfilled bars
   * vanish from 5m/15m/1h/1d/1w reads. Parents refresh before children (1d before 1w); the ±8-day
   * pad guarantees every view sees at least one full bucket. Runs on autocommit — {@code CALL}
   * refuses transactions.
   */
  public void refreshDerivedAggregates(OffsetDateTime from, OffsetDateTime to) {
    String start = Timestamp.from(from.minusDays(8).toInstant()).toInstant().toString();
    String end = Timestamp.from(to.plusDays(8).toInstant()).toInstant().toString();
    for (String view : List.of("candles_5m", "candles_15m", "candles_1h", "candles_1d", "candles_1w")) {
      jdbc.execute(
          "CALL public.refresh_continuous_aggregate('"
              + view
              + "', '"
              + start
              + "'::timestamptz, '"
              + end
              + "'::timestamptz)");
    }
  }

  /**
   * Copies one contract's bars (1m + 1d) into a CONT synthetic symbol for a date window —
   * UNADJUSTED, idempotent (B-19: the stitch is local arithmetic, never Kite's roll-unaware
   * {@code continuous=true} concatenation).
   */
  public int stitchInto(
      String contSymbol, String exchange, String fromSymbol, OffsetDateTime from, OffsetDateTime to) {
    return jdbc.update(
        """
        INSERT INTO candles
          (exchange, tradingsymbol, "interval", bucket, open, high, low, close, volume, oi, source, fetched_at)
        SELECT exchange, ?, "interval", bucket, open, high, low, close, volume, oi, source, now()
        FROM candles
        WHERE exchange = ? AND tradingsymbol = ? AND "interval" IN ('1m','1d')
          AND bucket >= ? AND bucket < ?
        ON CONFLICT (exchange, tradingsymbol, "interval", bucket) DO NOTHING
        """,
        contSymbol, exchange, fromSymbol,
        Timestamp.from(from.toInstant()), Timestamp.from(to.toInstant()));
  }

  /**
   * Purges every cached bar of one symbol — ONLY the Phase-16A corporate-action remediation may
   * call this (amendment A8, the single sanctioned exception to closed-bars-immutable).
   */
  public int purgeSymbol(String exchange, String tradingsymbol) {
    return jdbc.update(
        "DELETE FROM candles WHERE exchange = ? AND tradingsymbol = ?", exchange, tradingsymbol);
  }

  /** Hypertable size in bytes (the ay_hypertable_bytes gauge). */
  public long hypertableBytes() {
    Long candlesBytes =
        jdbc.queryForObject("SELECT public.hypertable_size('candles')", Long.class);
    return candlesBytes == null ? 0 : candlesBytes;
  }

  /** Total row count for an interval (tests/ops). */
  public long count(String interval) {
    Long count =
        jdbc.queryForObject(
            "SELECT count(*) FROM candles WHERE \"interval\" = ?", Long.class, interval);
    return count == null ? 0 : count;
  }

  /**
   * Whether a symbol has ANY non-BHAVCOPY 1d bar. {@code CorporateActionJob} remediates only such
   * symbols (purge + Kite re-fetch) — a BHAVCOPY-only equity is adjusted by the read-time split/bonus
   * adjuster instead, and sweeping every one of them would fire a Kite fetch for the whole ~22k
   * equity universe daily.
   */
  public boolean hasNonBhavcopyDaily(String exchange, String tradingsymbol) {
    Boolean exists =
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM candles WHERE exchange = ? AND tradingsymbol = ?"
                + " AND \"interval\" = '1d' AND source <> 'BHAVCOPY')",
            Boolean.class,
            exchange,
            tradingsymbol);
    return Boolean.TRUE.equals(exists);
  }

  /** The latest close at or before a bucket (Phase 15B/16A consumers). */
  public BigDecimal closeAt(
      String exchange, String tradingsymbol, String interval, OffsetDateTime bucket) {
    List<BigDecimal> rows =
        jdbc.query(
            """
            SELECT close FROM candles
            WHERE exchange = ? AND tradingsymbol = ? AND "interval" = ? AND bucket <= ?
            ORDER BY bucket DESC LIMIT 1
            """,
            (rs, n) -> rs.getBigDecimal(1),
            exchange, tradingsymbol, interval, Timestamp.from(bucket.toInstant()));
    return rows.isEmpty() ? null : rows.get(0);
  }
}
