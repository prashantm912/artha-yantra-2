package in.arthayantra.marketdata.candles;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC access to the candles hypertable (B-7). Two idempotent write paths on the natural PK: the
 * live tick-agg upsert with the B-6 merge math ({@code high=GREATEST}, {@code low=LEAST}, close
 * from the newest write, open kept from the first — replayed ticks can never double-count because
 * volume is a cumulative-day delta computed upstream), and the authoritative replace for FETCHED
 * history ({@link #upsertAuthoritativeAll}) where the upstream bar supersedes whatever is cached.
 */
@Repository
public class CandleRepository {

  private static final Logger log = LoggerFactory.getLogger(CandleRepository.class);

  /**
   * Max span (days) for ONE {@code refresh_continuous_aggregate} CALL. A full ~12-year corporate-
   * action re-backfill ({@code rebackfill-days-1m=4400}) issued as a SINGLE
   * {@code CALL('candles_5m', 2014→2026)} SIGKILLed the 4GB live TimescaleDB backend 3× on
   * 2026-07-10 (crash-recovery each). Slicing the range into ≤92-day (~3-month) windows bounds the
   * decompression + materialization each CALL performs.
   */
  static final int MAX_REFRESH_WINDOW_DAYS = 92;

  /**
   * Adjacent refresh windows OVERLAP by this many days. {@code refresh_continuous_aggregate}
   * EXCLUDES any bucket that does not COMPLETELY fit the window (TimescaleDB docs), so contiguous
   * cuts would silently drop every bucket that straddles an interior cut. The overlap must exceed
   * the coarsest bucket (candles_1w = 7 days) so a straddling bucket is fully contained in the
   * earlier window; a bucket re-refreshed inside the overlap is idempotent.
   */
  static final int REFRESH_WINDOW_OVERLAP_DAYS = 8;

  /**
   * Purge DELETE window span. A naive {@code DELETE FROM candles WHERE exchange=? AND
   * tradingsymbol=?} on a fully-COMPRESSED liquid equity decompresses the whole symbol in one DML —
   * INFY hit 1,058,355 tuples &gt; the 100k {@code max_tuples_decompressed_per_dml_transaction}
   * limit and aborted every sweep (2026-07-10). Deleting in 6-month windows keeps each DML at ~47k
   * 1m rows (candles is segmentby {@code (exchange,tradingsymbol,interval)}, so only this symbol's
   * segments in the touched 7-day chunks decompress) — comfortably under the limit, and WITHOUT
   * unbounding {@code max_tuples_decompressed_per_dml_transaction} (its own memory spike).
   */
  static final int PURGE_WINDOW_MONTHS = 6;

  /** A half-open time window {@code [from, to)} — the unit of a chunked refresh / windowed purge. */
  record Window(OffsetDateTime from, OffsetDateTime to) {}

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
        -- provenance (2026-07-03 ledger #8): an upsert that changes NOTHING (a re-fetch landing
        -- the identical bar) keeps the original source instead of re-stamping last-writer, so
        -- "did tick aggregation produce this bar" stays answerable; a value-changing write
        -- honestly takes the new source (the row now IS that source's data).
        source = CASE
          WHEN candles.close = EXCLUDED.close
           AND candles.volume = EXCLUDED.volume
           AND GREATEST(candles.high, EXCLUDED.high) = candles.high
           AND LEAST(candles.low, EXCLUDED.low) = candles.low
           AND COALESCE(EXCLUDED.oi, candles.oi) IS NOT DISTINCT FROM candles.oi
          THEN candles.source ELSE EXCLUDED.source END,
        fetched_at = now()
      """;

  private final JdbcTemplate jdbc;

  /** Wires the marketdata datasource. */
  public CandleRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Upserts one bar with the B-6 merge — the live tick-agg path ({@code BarWriter}) only. */
  public void upsert(Candle bar) {
    jdbc.update(
        UPSERT,
        bar.exchange(), bar.tradingsymbol(), bar.interval(),
        Timestamp.from(bar.bucket().toInstant()),
        bar.open(), bar.high(), bar.low(), bar.close(),
        bar.volume(), bar.oi(), bar.source());
  }

  private static final String UPSERT_AUTHORITATIVE =
      """
      INSERT INTO candles
        (exchange, tradingsymbol, "interval", bucket, open, high, low, close, volume, oi, source, fetched_at)
      VALUES (?,?,?,?,?,?,?,?,?,?,?, now())
      ON CONFLICT (exchange, tradingsymbol, "interval", bucket) DO UPDATE SET
        open = EXCLUDED.open,
        high = EXCLUDED.high,
        low = EXCLUDED.low,
        close = EXCLUDED.close,
        volume = EXCLUDED.volume,
        oi = EXCLUDED.oi,
        -- same provenance rule as the merge upsert (2026-07-03 ledger #8), on replace semantics:
        -- a value-identical re-fetch keeps the original source, a correcting write takes the new one
        source = CASE
          WHEN candles.open = EXCLUDED.open
           AND candles.high = EXCLUDED.high
           AND candles.low = EXCLUDED.low
           AND candles.close = EXCLUDED.close
           AND candles.volume = EXCLUDED.volume
           AND candles.oi IS NOT DISTINCT FROM EXCLUDED.oi
          THEN candles.source ELSE EXCLUDED.source END,
        fetched_at = now()
      """;

  /**
   * Batched upsert for the fetch/backfill pipelines (Phase 11), where the fetched bar is
   * AUTHORITATIVE for its bucket: ON CONFLICT replaces o/h/l/c/volume/oi outright instead of the
   * B-6 GREATEST/LEAST merge — under the merge a poisoned tick-agg spike (e.g. a bad high) could
   * NEVER be corrected by a re-fetch. The B-4 10-min recency window routes the in-progress
   * bucket's re-fetch through here too, which is correct: a fresher Kite partial bar supersedes
   * the tick-agg partial.
   */
  public void upsertAuthoritativeAll(List<Candle> bars) {
    jdbc.batchUpdate(
        UPSERT_AUTHORITATIVE,
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

  /**
   * Read-time 1m→{@code N}-minute rollup for an interval that has NO materialised continuous aggregate
   * (today only 3m): {@code time_bucket}s the base 1m rows on the fly. We do NOT create/refresh a
   * {@code candles_3m} cagg because re-aggregating the stitched CONT / expired-contract 1m series
   * (~106k contracts) OOM-crashed the live DB twice — so the 3m PRIMARY the scalpers signal on is
   * derived read-time, exactly like the historical-OI derivation. Bucketed in {@code Asia/Kolkata} so
   * 09:15 IST is a bucket boundary (the same grid the 5m/15m caggs use), OHLCV+OI rolled the standard
   * way (open=first, high=max, low=min, close=last, volume=sum, oi=last).
   */
  public List<Candle> rangeRolledFromOneMinute(
      String exchange, String tradingsymbol, int minutes, OffsetDateTime from, OffsetDateTime to) {
    return jdbc.query(
        "SELECT public.time_bucket(INTERVAL '" + minutes + " minutes', bucket, 'Asia/Kolkata') AS b, "
            + "public.first(open, bucket) AS open, max(high) AS high, min(low) AS low, "
            + "public.last(close, bucket) AS close, sum(volume) AS volume, public.last(oi, bucket) AS oi "
            + "FROM candles WHERE exchange = ? AND tradingsymbol = ? AND \"interval\" = '1m' "
            + "AND bucket >= ? AND bucket < ? GROUP BY b ORDER BY b",
        (rs, n) ->
            new Candle(
                exchange,
                tradingsymbol,
                minutes + "m",
                rs.getObject("b", OffsetDateTime.class),
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
    OffsetDateTime start = from.minusDays(8);
    OffsetDateTime end = to.plusDays(8);
    List<Window> windows =
        refreshWindows(start, end, MAX_REFRESH_WINDOW_DAYS, REFRESH_WINDOW_OVERLAP_DAYS);
    for (String view : List.of("candles_5m", "candles_15m", "candles_1h", "candles_1d", "candles_1w")) {
      for (Window w : windows) {
        jdbc.execute(
            "CALL public.refresh_continuous_aggregate('"
                + view
                + "', '"
                + w.from().toInstant()
                + "'::timestamptz, '"
                + w.to().toInstant()
                + "'::timestamptz)");
      }
      // one line per view, not per window: a 12-yr rebuild is ~48 windows × 5 views (2026-07-10 OOM)
      log.info(
          "refreshed derived aggregate {} over {} window(s) [{} .. {}]",
          view, windows.size(), start.toInstant(), end.toInstant());
    }
  }

  /**
   * OVERLAPPING refresh windows over {@code [start, end)}: {@code stepDays}-day cuts, each window
   * extended {@code overlapDays} past its cut so a bucket straddling an interior cut is fully
   * contained in the earlier window (see {@link #REFRESH_WINDOW_OVERLAP_DAYS}). The union covers
   * {@code [start, end)}; the last window is clamped at {@code end}.
   */
  static List<Window> refreshWindows(
      OffsetDateTime start, OffsetDateTime end, int stepDays, int overlapDays) {
    List<Window> windows = new ArrayList<>();
    OffsetDateTime cursor = start;
    while (cursor.isBefore(end)) {
      OffsetDateTime windowEnd = cursor.plusDays((long) stepDays + overlapDays);
      if (windowEnd.isAfter(end)) {
        windowEnd = end;
      }
      windows.add(new Window(cursor, windowEnd));
      cursor = cursor.plusDays(stepDays);
    }
    if (windows.isEmpty()) {
      windows.add(new Window(start, end)); // start >= end (degenerate) — one window
    }
    return windows;
  }

  /**
   * CONTIGUOUS non-overlapping windows over {@code [start, end)} of ≤{@code step} each (the last is
   * the remainder). The windowed purge uses this — a {@code DELETE ... AND bucket >= ? AND bucket
   * < ?} is exact per row, so overlap is neither needed nor wanted (it would re-scan rows).
   */
  static List<Window> purgeWindows(OffsetDateTime start, OffsetDateTime end, Period step) {
    List<Window> windows = new ArrayList<>();
    OffsetDateTime cursor = start;
    while (cursor.isBefore(end)) {
      OffsetDateTime next = cursor.plus(step);
      if (next.isAfter(end)) {
        next = end;
      }
      windows.add(new Window(cursor, next));
      cursor = next;
    }
    if (windows.isEmpty()) {
      windows.add(new Window(start, end)); // start >= end (degenerate) — one window
    }
    return windows;
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
    // bucket span for this symbol; +1 day on the high side so the DELETE's exclusive upper bound
    // still catches the last bucket. Clock-free (min/max from the data), so a windowed purge is
    // provably complete regardless of the caller's clock.
    Window bounds =
        jdbc.query(
            "SELECT min(bucket) AS lo, max(bucket) AS hi FROM candles"
                + " WHERE exchange = ? AND tradingsymbol = ?",
            rs -> {
              if (!rs.next()) {
                return null;
              }
              OffsetDateTime lo = rs.getObject("lo", OffsetDateTime.class);
              OffsetDateTime hi = rs.getObject("hi", OffsetDateTime.class);
              return lo == null ? null : new Window(lo, hi.plusDays(1));
            },
            exchange,
            tradingsymbol);
    if (bounds == null) {
      return 0; // nothing cached for this symbol
    }
    int deleted = 0;
    for (Window w : purgeWindows(bounds.from(), bounds.to(), Period.ofMonths(PURGE_WINDOW_MONTHS))) {
      deleted +=
          jdbc.update(
              "DELETE FROM candles WHERE exchange = ? AND tradingsymbol = ?"
                  + " AND bucket >= ? AND bucket < ?",
              exchange,
              tradingsymbol,
              Timestamp.from(w.from().toInstant()),
              Timestamp.from(w.to().toInstant()));
    }
    return deleted;
  }

  /** {@code candles} hypertable size in bytes (the {@code ay_hypertable_bytes} gauge). */
  public long hypertableBytes() {
    Long candlesBytes =
        jdbc.queryForObject("SELECT public.hypertable_size('candles')", Long.class);
    return candlesBytes == null ? 0 : candlesBytes;
  }

  /**
   * Whole-database size in bytes (the {@code ay_database_size_bytes} gauge). The 50 GB retention
   * review trigger (docs/retention.md, Q4/A2) is a WHOLE-DB volume, but {@link #hypertableBytes()}
   * measures only {@code candles} (~half the DB), so a candles-only read under-reports the trigger
   * by ~2x (audit AYDB-03). This is the DB total {@code ay status} surfaces — caggs, other
   * hypertables, plain tables, indexes and catalogs all roll in via {@code pg_database_size}.
   */
  public long databaseSizeBytes() {
    Long dbBytes = jdbc.queryForObject("SELECT pg_database_size(current_database())", Long.class);
    return dbBytes == null ? 0 : dbBytes;
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

  /**
   * The last 1m close in {@code [from, to)} — the roll-gap fallback for HISTORICAL futures stitched
   * from {@code expired_contracts}, which carry per-minute bars but NO native 1d (so {@link #closeAt}
   * with {@code "1d"} returns null). Live contracts have 1d, so this is never reached on that path.
   */
  public BigDecimal lastIntradayClose(
      String exchange, String tradingsymbol, OffsetDateTime from, OffsetDateTime to) {
    List<BigDecimal> rows =
        jdbc.query(
            """
            SELECT close FROM candles
            WHERE exchange = ? AND tradingsymbol = ? AND "interval" = '1m'
              AND bucket >= ? AND bucket < ?
            ORDER BY bucket DESC LIMIT 1
            """,
            (rs, n) -> rs.getBigDecimal(1),
            exchange,
            tradingsymbol,
            Timestamp.from(from.toInstant()),
            Timestamp.from(to.toInstant()));
    return rows.isEmpty() ? null : rows.get(0);
  }
}
