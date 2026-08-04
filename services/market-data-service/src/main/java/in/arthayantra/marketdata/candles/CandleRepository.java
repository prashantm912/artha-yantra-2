package in.arthayantra.marketdata.candles;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
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
   * TimescaleDB's per-DML decompression cap (default 100000). Since V049 (2026-07-19) the five
   * candle caggs are themselves COMPRESSED hypertables, and {@code refresh_continuous_aggregate}
   * DELETEs a window's existing materialized rows before re-inserting them — a DML whose
   * {@code ModifyHypertable} node decompresses every touched batch and errors
   * {@code tuple decompression limit exceeded by operation} past the cap. On a fully-compressed 2015
   * window that aborted the CHEVIOT + ULTRACEMCO corporate-action rebuilds (2026-07-30), leaving
   * base {@code candles} CA-adjusted while the caggs stayed stale.
   *
   * <p>ONLY {@link #refreshDerivedAggregatesForRebuild} touches this. The default refresh path keeps
   * the DB's guard in force — gap backfill and the futures roller refresh RECENT windows, which sit
   * inside every cagg's uncompressed hot window (5m/15m/1h compress after 30d, 1d 90d, 1w 180d) and
   * so decompress nothing. Raising it for them would weaken the same guard
   * {@link #PURGE_WINDOW_MONTHS} deliberately preserves, on the far more frequent caller.
   */
  static final String MAX_TUPLES_DECOMPRESSED_GUC =
      "timescaledb.max_tuples_decompressed_per_dml_transaction";

  /**
   * The raised — but FINITE — decompression cap for a corporate-action rebuild, ~50× the 100000
   * default. NOT {@code 0}/unlimited: a ≤{@link #MAX_REFRESH_WINDOW_DAYS}+{@link
   * #REFRESH_WINDOW_OVERLAP_DAYS}-day window bounds TIME, not tuples or memory, so unlimited would
   * let one DML decompress an arbitrarily large window into the row store — the 2026-07-10 SIGKILL
   * class of failure, and a refresh killed mid-flight leaves the window DELETED but not
   * re-materialized (the procedure commits across several transactions), i.e. an empty cagg range:
   * exactly the damage being repaired.
   *
   * <p>Sized from the densest CALL. Every cagg is ULTIMATELY sourced from 1m candles, so only
   * symbols with 1m history count — four read {@code candles WHERE "interval" = '1m'} directly,
   * while {@code candles_1w} reads {@code candles_1d} (V004), which inherits the same restriction
   * transitively. (Cross-vendor review corrected an earlier "every cagg reads candles directly"
   * here; the symbol-cardinality conclusion is unchanged, the derivation is just honest now.) {@code candles_5m} dominates at 375 trading minutes / 5 = 75
   * buckets per symbol per session × ~69 sessions in a 100-day window ≈ 5175 rows per symbol, so
   * 5000000 covers ~960 such symbols in one window — comfortably past the deep-history (2015)
   * windows that actually failed, where only CA-remediated equities carry 1m bars at all. At ~100
   * bytes per cagg row that is ~500 MB of decompression churn per DML, survivable on the 4 GB
   * database.
   *
   * <p>The symbol count in a DENSE recent window is NOT measured — that number is not derivable from
   * the repo, and this ceiling is deliberately the kind that fails loudly rather than churns
   * silently: TimescaleDB's error carries {@code errdetail("… tuples decompressed: %lld")}, so the
   * first trip hands us the real figure to re-size against, with the rebuild's existing FAILED
   * status + urgent ntfy already surfacing it.
   */
  static final int REBUILD_DECOMPRESSED_TUPLE_CEILING = 5_000_000;

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
   *
   * <p>Leaves {@link #MAX_TUPLES_DECOMPRESSED_GUC} at the database default: this path serves gap
   * backfill and the futures roller over RECENT windows, which decompress nothing. Only the
   * corporate-action rebuild reaches back into compressed chunks — see
   * {@link #refreshDerivedAggregatesForRebuild}.
   */
  public void refreshDerivedAggregates(OffsetDateTime from, OffsetDateTime to) {
    refresh(from, to, false);
  }

  /**
   * {@link #refreshDerivedAggregates} for the corporate-action rebuild, which spans ~12 years and so
   * re-materializes windows whose cagg chunks are COMPRESSED — decompressing far past the 100000
   * default and aborting the rebuild (CHEVIOT + ULTRACEMCO, 2026-07-30). The EXPLICIT opt-in raises
   * the cap to {@link #REBUILD_DECOMPRESSED_TUPLE_CEILING} for this refresh only; every other caller
   * keeps the database guard.
   */
  public void refreshDerivedAggregatesForRebuild(OffsetDateTime from, OffsetDateTime to) {
    refresh(from, to, true);
  }

  /**
   * The shared refresh. Every statement rides ONE pinned connection ({@link ConnectionCallback}) so
   * that a raised cap actually covers the CALLs: {@code JdbcTemplate} takes a pooled connection PER
   * {@code execute}, so a {@code SET} issued as its own {@code jdbc.execute(...)} could land on a
   * session that never runs the refresh — the code would read correct and change nothing. The
   * session-level {@code SET} (never {@code SET LOCAL} — {@code refresh_continuous_aggregate}
   * commits internally) survives those internal transactions.
   */
  private void refresh(OffsetDateTime from, OffsetDateTime to, boolean raiseDecompressionCap) {
    OffsetDateTime start = from.minusDays(8);
    OffsetDateTime end = to.plusDays(8);
    List<Window> windows =
        refreshWindows(start, end, MAX_REFRESH_WINDOW_DAYS, REFRESH_WINDOW_OVERLAP_DAYS);
    jdbc.execute(
        (ConnectionCallback<Void>)
            connection -> {
              try (Statement st = connection.createStatement()) {
                if (!raiseDecompressionCap) {
                  refreshEachView(st, windows, start, end);
                  return null;
                }
                st.execute(
                    "SET " + MAX_TUPLES_DECOMPRESSED_GUC + " = " + REBUILD_DECOMPRESSED_TUPLE_CEILING);
                Exception primary = null;
                try {
                  refreshEachView(st, windows, start, end);
                } catch (SQLException | RuntimeException e) {
                  primary = e;
                  throw e;
                } finally {
                  try {
                    st.execute("RESET " + MAX_TUPLES_DECOMPRESSED_GUC);
                  } catch (SQLException | RuntimeException cleanup) {
                    // the session still carries the raised cap and cleanup could not be confirmed:
                    // kill the physical connection so the pool can never hand it on, and never let
                    // the cleanup failure hide the refresh failure that caused it
                    abortQuietly(connection);
                    if (primary == null) {
                      throw cleanup;
                    }
                    primary.addSuppressed(cleanup);
                  }
                }
              }
              return null;
            });
  }

  /** Destroys the physical connection whose {@link #MAX_TUPLES_DECOMPRESSED_GUC} could not be reset. */
  private static void abortQuietly(Connection connection) {
    try {
      connection.abort(Runnable::run);
    } catch (SQLException | RuntimeException e) {
      log.error("could not abort the connection after a failed {} reset", MAX_TUPLES_DECOMPRESSED_GUC, e);
    }
  }

  /** The view × window CALL grid, on the caller's pinned statement. */
  private static void refreshEachView(
      Statement st, List<Window> windows, OffsetDateTime start, OffsetDateTime end)
      throws SQLException {
    for (String view : List.of("candles_5m", "candles_15m", "candles_1h", "candles_1d", "candles_1w")) {
      for (Window w : windows) {
        st.execute(
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
   * Purges every cached bar of one symbol, every interval.
   *
   * <p>This WAS the Phase-16A corporate-action remediation's first step (amendment A8, the single
   * sanctioned exception to closed-bars-immutable). It is not any more: the remediation destroyed
   * ~12 years of history before proving it could re-fetch a replacement, so it now stages the
   * re-fetch and swaps it in through {@link #swapStaged}, whose delete is scoped to the verified
   * staged span. Nothing on the production path calls this today — it survives as a test fixture
   * helper (seeding a symbol from empty), and any new production caller would be reintroducing
   * exactly the unguarded destruction that V054 exists to prevent.
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

  private static final String STAGE_INSERT =
      """
      INSERT INTO candle_rebuild_staging
        (exchange, tradingsymbol, "interval", bucket, open, high, low, close, volume, oi, source)
      VALUES (?,?,?,?,?,?,?,?,?,?,?)
      ON CONFLICT (exchange, tradingsymbol, "interval", bucket) DO UPDATE SET
        open = EXCLUDED.open,
        high = EXCLUDED.high,
        low = EXCLUDED.low,
        close = EXCLUDED.close,
        volume = EXCLUDED.volume,
        oi = EXCLUDED.oi,
        source = EXCLUDED.source
      """;

  /**
   * Batched insert into the corporate-action rebuild STAGING buffer (V054). Plain replace-on-conflict
   * — the staged rows are one authoritative Kite re-fetch, so a re-fetched overlapping page simply
   * supersedes itself; there is no provenance rule to preserve because nothing here is live data yet.
   */
  public void stageAll(List<Candle> bars) {
    jdbc.batchUpdate(
        STAGE_INSERT,
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

  /** Empties one symbol's rebuild staging buffer; run at the start AND the end of every attempt. */
  public int clearStaging(String exchange, String tradingsymbol) {
    return jdbc.update(
        "DELETE FROM candle_rebuild_staging WHERE exchange = ? AND tradingsymbol = ?",
        exchange,
        tradingsymbol);
  }

  /**
   * What a staged re-fetch covers, measured against the live series it is about to replace — the
   * evidence {@code CorporateActionJob} judges BEFORE it deletes anything.
   *
   * @param stagedBars staged row count for this interval; {@code 0} means the fetch returned nothing
   * @param stagedFrom oldest staged bucket, {@code null} iff {@code stagedBars == 0}
   * @param stagedTo newest staged bucket, {@code null} iff {@code stagedBars == 0}
   * @param cachedBarsNotStaged live buckets INSIDE {@code [stagedFrom, stagedTo]} that the staged
   *     series does not carry — every one of these would be deleted with no replacement
   * @param cachedTo newest live bucket, {@code null} iff the symbol has nothing cached
   */
  public record StagedCoverage(
      long stagedBars,
      OffsetDateTime stagedFrom,
      OffsetDateTime stagedTo,
      long cachedBarsNotStaged,
      OffsetDateTime cachedTo) {}

  /**
   * Measures {@link StagedCoverage} for one (symbol, interval).
   *
   * <p>Every aggregate here is a bare-column {@code min}/{@code max} with no {@code ORDER BY} /
   * {@code DISTINCT} / {@code LIMIT} over a computed expression — deliberately the shape that is
   * SAFE on a compressed hypertable under TimescaleDB 2.18.2, whose sorted-merge planner assertion
   * took the OI chain pages down on 2026-07-20.
   */
  public StagedCoverage stagedCoverage(String exchange, String tradingsymbol, String interval) {
    return jdbc.query(
        """
        SELECT s.n AS staged_n, s.lo AS staged_lo, s.hi AS staged_hi, c.hi AS cached_hi,
               (SELECT count(*) FROM candles k
                 WHERE k.exchange = ? AND k.tradingsymbol = ? AND k."interval" = ?
                   AND k.bucket >= s.lo AND k.bucket <= s.hi
                   AND NOT EXISTS (SELECT 1 FROM candle_rebuild_staging g
                                    WHERE g.exchange = k.exchange
                                      AND g.tradingsymbol = k.tradingsymbol
                                      AND g."interval" = k."interval"
                                      AND g.bucket = k.bucket)) AS not_staged
        FROM (SELECT count(*) AS n, min(bucket) AS lo, max(bucket) AS hi
                FROM candle_rebuild_staging
               WHERE exchange = ? AND tradingsymbol = ? AND "interval" = ?) s,
             (SELECT max(bucket) AS hi FROM candles
               WHERE exchange = ? AND tradingsymbol = ? AND "interval" = ?) c
        """,
        rs -> {
          if (!rs.next()) {
            return new StagedCoverage(0, null, null, 0, null);
          }
          return new StagedCoverage(
              rs.getLong("staged_n"),
              rs.getObject("staged_lo", OffsetDateTime.class),
              rs.getObject("staged_hi", OffsetDateTime.class),
              rs.getLong("not_staged"),
              rs.getObject("cached_hi", OffsetDateTime.class));
        },
        exchange, tradingsymbol, interval,
        exchange, tradingsymbol, interval,
        exchange, tradingsymbol, interval);
  }

  /**
   * The SWAP: replaces one (symbol, interval)'s live bars over EXACTLY the staged span with the
   * staged ones. Returns the rows inserted.
   *
   * <p>Scoped to the staged span rather than the whole symbol on purpose — the invariant this whole
   * change buys is "every bucket deleted has a verified replacement", and outside the staged span
   * there is none. That is also strictly less destructive than the {@link #purgeSymbol} it replaces
   * on this path, which deleted bars older than Kite's serving depth that nothing could ever restore.
   *
   * <p>Windowed at {@link #PURGE_WINDOW_MONTHS} for the same reason the purge is: {@code candles} is
   * segmentby {@code (exchange, tradingsymbol, interval)}, so an unwindowed DELETE over a fully
   * compressed liquid equity decompresses the whole symbol in one DML and blows
   * {@code max_tuples_decompressed_per_dml_transaction}. Adding {@code "interval" = ?} to the
   * predicate narrows the touched segments FURTHER than the all-interval purge did, so this
   * decompresses strictly less per window than the code it replaces. No continuous aggregate is
   * touched here: the caller still refreshes them afterwards through the existing chunked
   * {@link #refreshDerivedAggregatesForRebuild}, at its unchanged
   * {@link #MAX_REFRESH_WINDOW_DAYS}-day per-CALL bound.
   */
  public int swapStaged(String exchange, String tradingsymbol, String interval) {
    Window bounds =
        jdbc.query(
            "SELECT min(bucket) AS lo, max(bucket) AS hi FROM candle_rebuild_staging"
                + " WHERE exchange = ? AND tradingsymbol = ? AND \"interval\" = ?",
            rs -> {
              if (!rs.next()) {
                return null;
              }
              OffsetDateTime lo = rs.getObject("lo", OffsetDateTime.class);
              OffsetDateTime hi = rs.getObject("hi", OffsetDateTime.class);
              return lo == null ? null : new Window(lo, hi.plusDays(1));
            },
            exchange,
            tradingsymbol,
            interval);
    if (bounds == null) {
      return 0; // nothing staged for this interval
    }
    int inserted = 0;
    for (Window w : purgeWindows(bounds.from(), bounds.to(), Period.ofMonths(PURGE_WINDOW_MONTHS))) {
      Timestamp from = Timestamp.from(w.from().toInstant());
      Timestamp to = Timestamp.from(w.to().toInstant());
      jdbc.update(
          "DELETE FROM candles WHERE exchange = ? AND tradingsymbol = ? AND \"interval\" = ?"
              + " AND bucket >= ? AND bucket < ?",
          exchange, tradingsymbol, interval, from, to);
      inserted +=
          jdbc.update(
              """
              INSERT INTO candles
                (exchange, tradingsymbol, "interval", bucket, open, high, low, close, volume, oi,
                 source, fetched_at)
              SELECT exchange, tradingsymbol, "interval", bucket, open, high, low, close, volume, oi,
                     source, now()
              FROM candle_rebuild_staging
              WHERE exchange = ? AND tradingsymbol = ? AND "interval" = ?
                AND bucket >= ? AND bucket < ?
              ON CONFLICT (exchange, tradingsymbol, "interval", bucket) DO UPDATE SET
                open = EXCLUDED.open,
                high = EXCLUDED.high,
                low = EXCLUDED.low,
                close = EXCLUDED.close,
                volume = EXCLUDED.volume,
                oi = EXCLUDED.oi,
                source = EXCLUDED.source,
                fetched_at = now()
              """,
              exchange, tradingsymbol, interval, from, to);
    }
    // fetched_at is stamped now() rather than copied: the Stage-D dataHash reads it to flag
    // pre-event backtest runs as not-like-for-like, which is exactly what a rebuild must trigger.
    return inserted;
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
