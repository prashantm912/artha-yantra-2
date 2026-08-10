package in.arthayantra.marketdata.candles;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.Period;
import java.util.ArrayList;
import java.util.Collection;
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
   * materialization each CALL performs.
   *
   * <p>This bounds TIME only. It is the sole window bound on the DEFAULT path (which refreshes
   * recent, uncompressed windows and so decompresses nothing) and an OUTER bound on the rebuild
   * path, where {@link #REBUILD_WINDOW_TUPLE_BUDGET} additionally bounds tuples — 92 days of a dense
   * {@code candles_5m} region is ~7.4M tuples, comfortably past what any DML may decompress.
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
   *
   * <p><b>That paragraph was an ASSUMPTION, not an invariant, and the futures roller violated it
   * for as long as it existed</b> (found 2026-08-04). {@code ContinuousFuturesRoller.stitch} passed
   * its REQUESTED window — which begins at its {@code STITCH_EPOCH} of 2000-01-01 — rather than the
   * range it had actually stitched, so the nightly roll asked for a ~26-year refresh and died on
   * this cap for all six index roots ({@code tuples decompressed: 341820} on {@code candles_5m}).
   * The premise was restored by narrowing the window at the caller ({@link #stitchInto} now returns
   * the inserted range) — deliberately NOT by adding the roller to the raised-cap path, which would
   * have bought a passing roll at the price of the guard this paragraph is arguing for. The
   * compression half of the claim was re-verified live and is accurate: {@code compress_after} on
   * the 5m/15m/1h materialization hypertables really is 30 days. What was never true is "the
   * futures roller refreshes recent windows" — nothing enforced it, so nothing caught it. Something
   * does now: {@code ContinuousFuturesStitchRefreshTest} asserts the refresh window the roller
   * passes, not merely that it passes one.
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
   * <p>⚠️ The original sizing argument for this number was WRONG, and it is kept here only because
   * the correction is the whole point of {@link #REBUILD_WINDOW_TUPLE_BUDGET}. It read: {@code
   * candles_5m} dominates at 75 buckets per symbol per session × ~69 sessions in a 100-day window ≈
   * 5175 rows per symbol, so 5000000 covers ~960 such symbols — and closed by admitting the symbol
   * count in a DENSE recent window was NOT measured, on the theory that TimescaleDB's {@code
   * errdetail("… tuples decompressed: %lld")} would hand us the real figure on the first trip. The
   * first trip came (13 symbols REFRESH_FAILED, 2026-07-21..07-31) and the errdetail did NOT
   * survive it — containers were recreated and {@code corporate_action_events.details} carries no
   * error text. Measured instead against the live materialization hypertables (2026-08-04): a
   * 100-day window over {@code candles_5m} decompresses up to 9,543,253 tuples — 191% of this
   * ceiling — because the live universe is ~6164 symbols per chunk, not ~960. The ceiling was never
   * the defect; the window was.
   *
   * <p>The ceiling itself is DELIBERATELY NOT raised. At ~100 bytes per cagg row 5000000 is already
   * ~500 MB of decompression churn per DML on a 4 GB database, and the covering-the-worst-case
   * figure would be ~1 GB. Windows are made to fit the ceiling instead — see {@link
   * #REBUILD_WINDOW_TUPLE_BUDGET}.
   */
  static final int REBUILD_DECOMPRESSED_TUPLE_CEILING = 5_000_000;

  /**
   * Per-window tuple budget the rebuild plans its refresh windows AGAINST — a quarter of {@link
   * #REBUILD_DECOMPRESSED_TUPLE_CEILING}, because the budget covers only the tuples INSIDE the
   * window and a real DML also pays edge spill.
   *
   * <p>Spill is the load-bearing measurement here and it is not obvious. Decompression is per
   * matching BATCH, not per chunk touched: the five cagg materialization hypertables are all {@code
   * segmentby (exchange, tradingsymbol), orderby bucket}, so a refresh's window-scoped DELETE
   * (which carries NO symbol predicate — a refresh re-materializes every symbol) prunes batches by
   * the {@code orderby} min/max metadata alone. A batch STRADDLING a window edge therefore
   * decompresses IN FULL for the sake of the few rows inside. Measured on the live {@code
   * candles_5m} materialization hypertable 2026-08-04, worst case over every window start:
   *
   * <pre>
   *   span     tuples decompressed   % of ceiling
   *    14 d          2,990,258           59.8
   *    22 d          3,818,126           76.4
   *    30 d          4,476,813           89.5
   *    38 d          4,967,129           99.3
   *    70 d          7,396,205          147.9
   *   100 d          9,543,253          190.9   ← what this class used to generate
   * </pre>
   *
   * A 14-day window holds only ~1.1M tuples of content yet costs ~3.0M, so ~1.9M of every window is
   * spill — which is why the budget is a QUARTER of the ceiling rather than all of it, and why
   * simply narrowing a fixed day-count has sharply diminishing returns.
   *
   * <p>A tuple budget, unlike a day count, does not decay as the universe grows: {@link
   * #planRebuildWindows} re-derives the cut points from the CURRENT chunk load on every rebuild, so
   * windows tighten automatically. At the 2026-08-04 load the densest {@code candles_5m} chunk
   * (5,558,488 tuples over 70 days) plans into 5 windows of ~14 days — 59.8% of the ceiling — while
   * {@code candles_1w} (19,076 tuples in its densest chunk, 60,769 in TOTAL across 12 years) plans
   * into a single window instead of paying {@code candles_5m}'s window count for nothing.
   */
  static final long REBUILD_WINDOW_TUPLE_BUDGET = REBUILD_DECOMPRESSED_TUPLE_CEILING / 4;

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

  /**
   * The derived candle aggregates, in REFRESH ORDER — parents before children, because {@code
   * candles_1w} reads {@code candles_1d} (V004) and would otherwise re-materialize from a stale
   * parent. Declaration order IS the contract; a subset preserves it.
   */
  public enum DerivedAggregate {
    /** 5-minute buckets — by far the densest cagg (46.3M materialized rows on 2026-08-04). */
    CANDLES_5M("candles_5m", 1),
    /** 15-minute buckets. */
    CANDLES_15M("candles_15m", 1),
    /** 1-hour buckets (V027 moved 3m to a read-time rollup; 1h is the coarsest intraday cagg). */
    CANDLES_1H("candles_1h", 1),
    /** Daily buckets — the parent of {@link #CANDLES_1W}. */
    CANDLES_1D("candles_1d", 1),
    /** Weekly buckets, read from {@code candles_1d} — the only cagg needing a multi-day overlap. */
    CANDLES_1W("candles_1w", REFRESH_WINDOW_OVERLAP_DAYS);

    private final String viewName;
    private final int overlapDays;

    DerivedAggregate(String viewName, int overlapDays) {
      this.viewName = viewName;
      this.overlapDays = overlapDays;
    }

    /** The cagg's view name as {@code refresh_continuous_aggregate} takes it. */
    public String viewName() {
      return viewName;
    }

    /**
     * Days of overlap this cagg's windows need at an interior cut, ≥ its own bucket width.
     *
     * <p>{@link #REFRESH_WINDOW_OVERLAP_DAYS} is 8 because ONE shared window list had to satisfy
     * the COARSEST bucket. Planning per view retires that: a 5-minute bucket cannot straddle a cut
     * by more than 5 minutes, so a single day is already generous, and the 7 days it used to carry
     * were pure re-materialization — on {@code candles_5m} 8 days of overlap is ~630,000 extra
     * tuples decompressed per window, half the budget spent re-refreshing what the previous window
     * had just written. Only {@code candles_1w}, whose bucket really is 7 days, keeps the 8.
     */
    int overlapDays() {
      return overlapDays;
    }
  }

  /** Every derived aggregate, in refresh order — the default subset, unchanged behaviour. */
  public static final List<DerivedAggregate> ALL_DERIVED_AGGREGATES =
      List.of(DerivedAggregate.values());

  /**
   * One materialization-hypertable chunk of a cagg, clipped to the refresh range: the span a window
   * cut may fall inside, and the tuples a DML touching that span must decompress.
   */
  record ChunkLoad(OffsetDateTime from, OffsetDateTime to, long tuples) {}

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
   * {@link #refreshDerivedAggregatesForRebuild}. <b>Callers own that "recent" claim</b>: pass the
   * range you actually INVALIDATED, never a nominal request window that merely contains it. The
   * futures roller passed the latter and turned this into a ~26-year refresh (2026-08-04) — the
   * caller is the only layer that knows the difference, so this method cannot defend itself.
   */
  public void refreshDerivedAggregates(OffsetDateTime from, OffsetDateTime to) {
    refresh(from, to, false, ALL_DERIVED_AGGREGATES);
  }

  /**
   * {@link #refreshDerivedAggregates} for the corporate-action rebuild, which spans ~12 years and so
   * re-materializes windows whose cagg chunks are COMPRESSED — decompressing far past the 100000
   * default and aborting the rebuild (CHEVIOT + ULTRACEMCO, 2026-07-30). The EXPLICIT opt-in raises
   * the cap to {@link #REBUILD_DECOMPRESSED_TUPLE_CEILING} for this refresh only; every other caller
   * keeps the database guard.
   *
   * <p>Raising the cap was NOT ENOUGH, which is the second thing this path does differently: the
   * fixed 100-day windows decompressed up to 9,543,253 tuples — 191% of the raised ceiling — so the
   * rebuild aborted anyway for 13 symbols over 2026-07-21..07-31, deterministically rather than by
   * bad luck. Windows here are therefore planned per view from the CURRENT chunk load ({@link
   * #planRebuildWindows}) instead of a day count.
   */
  public void refreshDerivedAggregatesForRebuild(OffsetDateTime from, OffsetDateTime to) {
    refreshDerivedAggregatesForRebuild(from, to, ALL_DERIVED_AGGREGATES);
  }

  /**
   * {@link #refreshDerivedAggregatesForRebuild} over a SUBSET of the caggs. A repair that only needs
   * the intraday planes back (5m/15m/1h) should not pay for {@code candles_1d} — whose dense native
   * counterpart {@code candles}@1d is what {@code readDailyWithWarmup} actually serves — nor for
   * {@code candles_1w}. Callers that want everything keep the two-argument form and behave
   * byte-identically to before this overload existed.
   *
   * <p>An EXPLICIT argument, deliberately: routing this through a config property would let a
   * rebuild silently refresh less than the caller believed, and the resulting hole in a cagg is
   * indistinguishable from the failure this whole path exists to repair.
   *
   * @param views the caggs to refresh; refreshed in {@link DerivedAggregate} declaration order
   *     (parents before children) regardless of the order given
   */
  public void refreshDerivedAggregatesForRebuild(
      OffsetDateTime from, OffsetDateTime to, Collection<DerivedAggregate> views) {
    refresh(from, to, true, views);
  }

  /**
   * The shared refresh. Every statement rides ONE pinned connection ({@link ConnectionCallback}) so
   * that a raised cap actually covers the CALLs: {@code JdbcTemplate} takes a pooled connection PER
   * {@code execute}, so a {@code SET} issued as its own {@code jdbc.execute(...)} could land on a
   * session that never runs the refresh — the code would read correct and change nothing. The
   * session-level {@code SET} (never {@code SET LOCAL} — {@code refresh_continuous_aggregate}
   * commits internally) survives those internal transactions.
   */
  private void refresh(
      OffsetDateTime from,
      OffsetDateTime to,
      boolean raiseDecompressionCap,
      Collection<DerivedAggregate> views) {
    OffsetDateTime start = from.minusDays(8);
    OffsetDateTime end = to.plusDays(8);
    List<DerivedAggregate> ordered =
        ALL_DERIVED_AGGREGATES.stream().filter(views::contains).toList();
    jdbc.execute(
        (ConnectionCallback<Void>)
            connection -> {
              try (Statement st = connection.createStatement()) {
                if (!raiseDecompressionCap) {
                  refreshEachView(st, ordered, start, end, false);
                  return null;
                }
                st.execute(
                    "SET " + MAX_TUPLES_DECOMPRESSED_GUC + " = " + REBUILD_DECOMPRESSED_TUPLE_CEILING);
                Exception primary = null;
                try {
                  refreshEachView(st, ordered, start, end, true);
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

  /**
   * The view × window CALL grid, on the caller's pinned statement. Window PLANNING is per view when
   * {@code planFromChunkLoad}: the five caggs differ in density by three orders of magnitude
   * (2026-08-04: {@code candles_5m} 46.3M materialized rows, {@code candles_1w} 60,769), so one
   * shared day-count either starves the dense view or wastes CALLs on the sparse ones.
   */
  private static void refreshEachView(
      Statement st,
      List<DerivedAggregate> views,
      OffsetDateTime start,
      OffsetDateTime end,
      boolean planFromChunkLoad)
      throws SQLException {
    List<Window> shared =
        refreshWindows(start, end, MAX_REFRESH_WINDOW_DAYS, REFRESH_WINDOW_OVERLAP_DAYS);
    for (DerivedAggregate view : views) {
      int windowCount =
          planFromChunkLoad
              ? refreshReplanning(st, view, start, end)
              : callEach(st, view, shared);
      // one line per view, not per window: a 12-yr rebuild is ~48 windows × 5 views (2026-07-10 OOM)
      log.info(
          "refreshed derived aggregate {} over {} window(s) [{} .. {}]",
          view.viewName(), windowCount, start.toInstant(), end.toInstant());
    }
  }

  /** Issues one CALL per precomputed window — the plain day-count path, unchanged. */
  private static int callEach(Statement st, DerivedAggregate view, List<Window> windows)
      throws SQLException {
    for (Window w : windows) {
      call(st, view, w);
    }
    return windows.size();
  }

  /**
   * Refreshes {@code [start, end)} one window at a time, REPLANNING each from the chunk load over
   * the remaining span.
   *
   * <p>⚠️ Planning the whole list from ONE read is a stale-operand bug: the load a window was sized
   * against moves between the read and the CALL — V049 compresses these caggs on a schedule, a
   * concurrent backfill adds rows, and an UNCOMPRESSED chunk honestly reports 0 today and
   * decompresses in full once a policy compresses it. The window does not shrink to compensate;
   * Timescale aborts at the 5,000,000 ceiling, burning one of the refresh's bounded attempts and
   * reproducing the outage this sizing exists to end. Cross-vendor review, 2026-08-11.
   *
   * <p>⚠️ AND THE CURSOR ADVANCES BY {@code to - overlap}, NOT BY {@code to}. {@link #windowsFrom}
   * extends every window PAST its cut, and the next window is meant to begin at the CUT — so
   * advancing to the extended end makes consecutive CALLs merely abut, and a cagg bucket straddling
   * that boundary is fully contained in NEITHER and silently stays unmaterialized. That is exactly
   * the damage the overlap exists to prevent, and the first version of this loop reintroduced it
   * while fixing the staleness above (same review, next round). Pinned by
   * {@code CandleRepositoryWindowsTest}'s execution-sequence assertion.
   */
  private static int refreshReplanning(
      Statement st, DerivedAggregate view, OffsetDateTime start, OffsetDateTime end)
      throws SQLException {
    OffsetDateTime cursor = start;
    int executed = 0;
    while (cursor.isBefore(end)) {
      List<Window> plan =
          planRebuildWindows(
              chunkLoad(st, view, cursor, end),
              cursor,
              end,
              REBUILD_WINDOW_TUPLE_BUDGET,
              MAX_REFRESH_WINDOW_DAYS,
              view.overlapDays());
      if (plan.isEmpty()) {
        break; // nothing left to materialise over [cursor, end)
      }
      call(st, view, plan.get(0));
      executed++;
      // ⚠️ TAKE THE PLANNER'S OWN NEXT CUT — never re-derive it as `to - overlapDays`.
      //
      // The plan's second window ALREADY starts at the cut the first window was extended past, so
      // this is the same number by construction and cannot drift from it. Re-deriving it looked
      // equivalent and was not: when a window's cut lands within overlapDays of the cursor,
      // `to - overlap` is <= cursor, and the no-spin guard that protected against that then BROKE
      // THE LOOP — silently abandoning the rest of the span unmaterialised. Caught by
      // consecutiveRebuildCallsOverlapRatherThanMerelyAbut, whose coverage assertion failed with
      // the last CALL ending 8 days short of `end` (2026-08-11).
      //
      // Progress is guaranteed without a guard: plan.get(1).from() is a strictly later cut than
      // plan.get(0).from(), which is the cursor.
      if (plan.size() == 1) {
        break; // the single window was clamped at end — the span is covered
      }
      cursor = plan.get(1).from();
    }
    return executed;
  }

  private static void call(Statement st, DerivedAggregate view, Window w) throws SQLException {
    st.execute(
        "CALL public.refresh_continuous_aggregate('"
            + view.viewName()
            + "', '"
            + w.from().toInstant()
            + "'::timestamptz, '"
            + w.to().toInstant()
            + "'::timestamptz)");
  }

  /**
   * The chunk load of ONE cagg's materialization hypertable over {@code [start, end)} — the input
   * {@link #planRebuildWindows} sizes windows from. Read on the caller's pinned statement via
   * {@code executeQuery}, so it neither takes a second pooled connection nor disturbs the session
   * {@code SET} that must cover the CALLs.
   *
   * <p>{@code approximate_row_count} on a COMPRESSED chunk is exact, not an estimate — it sums the
   * per-batch {@code _ts_meta_count}. Verified against the live database 2026-08-04: it returned
   * 4706633 and 5112833 for two {@code candles_5m} chunks whose batch-metadata sums are the same
   * two numbers to the digit. On an UNCOMPRESSED chunk it reads {@code reltuples} and so reports 0
   * until something ANALYZEs it — which is the CORRECT input for this budget rather than a bug to
   * work around: an uncompressed chunk decompresses nothing, so it should contribute nothing to a
   * decompression budget, and {@link #MAX_REFRESH_WINDOW_DAYS} still bounds its materialization.
   *
   * <p>An EMPTY result is the honest "no chunks here" answer (a fresh database, or a range ahead of
   * the data) and plans the plain day-count windows. A query that FAILS is left to propagate,
   * because silently falling back would restore exactly the 100-day window this method exists to
   * replace.
   */
  static List<ChunkLoad> chunkLoad(
      Statement st, DerivedAggregate view, OffsetDateTime start, OffsetDateTime end)
      throws SQLException {
    String sql =
        // public-qualified for the same reason the CALL above is: services connect with a
        // currentSchema that does NOT include public, so the bare name resolves to nothing
        "SELECT c.range_start, c.range_end,"
            + " public.approximate_row_count("
            + "format('%I.%I', c.chunk_schema, c.chunk_name)::regclass)"
            + " FROM timescaledb_information.continuous_aggregates a"
            + " JOIN timescaledb_information.chunks c"
            + " ON c.hypertable_schema = a.materialization_hypertable_schema"
            + " AND c.hypertable_name = a.materialization_hypertable_name"
            + " WHERE a.view_name = '" + view.viewName() + "'"
            + " AND c.range_end > '" + start.toInstant() + "'::timestamptz"
            + " AND c.range_start < '" + end.toInstant() + "'::timestamptz"
            + " ORDER BY c.range_start";
    List<ChunkLoad> loads = new ArrayList<>();
    try (ResultSet rs = st.executeQuery(sql)) {
      while (rs != null && rs.next()) {
        loads.add(
            new ChunkLoad(
                rs.getObject(1, OffsetDateTime.class),
                rs.getObject(2, OffsetDateTime.class),
                rs.getLong(3)));
      }
    }
    return loads;
  }

  /**
   * Refresh windows for {@code [start, end)} sized so NO single {@code refresh_continuous_aggregate}
   * DML decompresses more than {@code tupleBudget} tuples of window CONTENT, and no window spans
   * more than {@code maxSpanDays}.
   *
   * <p>Two independent bounds, and both are load-bearing. The TUPLE bound is what the 2026-07-30
   * failure needed: {@code chunks} carries the CURRENT materialized load, so a chunk denser than the
   * budget is cut into equal parts and consecutive chunks cheaper than it SHARE a window. The DAY
   * bound is the original 2026-07-10 protection and must survive: a range with no chunks at all
   * (nothing compressed, so zero tuples) would otherwise plan as ONE window spanning the whole
   * rebuild — the 12-year single CALL that SIGKILLed the backend. Tuples bound decompression; days
   * bound materialization; neither substitutes for the other.
   *
   * <p>The result keeps {@link #refreshWindows}' contract exactly: the union covers {@code [start,
   * end)}, every interior cut is followed by {@code overlapDays} of overlap so a bucket straddling
   * it is fully contained in the earlier window, and the last window is clamped at {@code end}.
   */
  static List<Window> planRebuildWindows(
      List<ChunkLoad> chunks,
      OffsetDateTime start,
      OffsetDateTime end,
      long tupleBudget,
      int maxSpanDays,
      int overlapDays) {
    if (!start.isBefore(end)) {
      return List.of(new Window(start, end)); // degenerate — same guard as refreshWindows
    }
    List<OffsetDateTime> cuts = new ArrayList<>();
    cuts.add(start);
    // A window's overlap spills PAST its cut, i.e. into the NEXT chunk — which may be denser than
    // the one being sliced (live 2026-08-04: chunk 1909 is 56,165 tuples/day, 1910 is 67,238). The
    // overlap is therefore charged at the range's PEAK density, never the local one; sizing it
    // locally under-charges exactly at a rising boundary and puts the window back over budget.
    double peakPerDay = 0;
    for (ChunkLoad chunk : chunks) {
      long span = Math.max(1, Duration.between(chunk.from(), chunk.to()).toDays());
      peakPerDay = Math.max(peakPerDay, (double) chunk.tuples() / span);
    }
    long overlapCost = Math.round(peakPerDay * overlapDays);
    long carried = 0;
    for (ChunkLoad chunk : chunks) {
      OffsetDateTime from = chunk.from().isBefore(start) ? start : chunk.from();
      OffsetDateTime to = chunk.to().isAfter(end) ? end : chunk.to();
      if (!from.isBefore(to)) {
        continue;
      }
      long tuples = clippedTuples(chunk, from, to);
      long days = Math.max(1, Duration.between(from, to).toDays());
      double perDay = (double) tuples / days;
      if (carried + tuples + overlapCost <= tupleBudget) {
        carried += tuples; // cheap enough to ride the window already open
        continue;
      }
      if (from.isAfter(cuts.get(cuts.size() - 1))) {
        cuts.add(from); // this chunk starts a window of its own
      }
      // widest span whose content PLUS the overlap still fits; ≤0 means the overlap alone exceeds
      // the budget, which no amount of splitting can fix — fall back to the 1-day floor
      double affordableDays = (tupleBudget - overlapCost) / perDay;
      int parts =
          affordableDays <= 0
              ? (int) days
              : (int) Math.min(days, Math.max(1, Math.ceil(days / affordableDays)));
      long spanSeconds = Duration.between(from, to).getSeconds();
      for (int k = 1; k < parts; k++) {
        cuts.add(from.plusSeconds(spanSeconds * k / parts));
      }
      carried = tuples / parts; // the tail part is what the NEXT chunk may share a window with
    }
    if (end.isAfter(cuts.get(cuts.size() - 1))) {
      cuts.add(end);
    }
    return windowsFrom(capSpan(cuts, maxSpanDays), end, overlapDays);
  }

  /** A partially-covered chunk contributes its tuples pro-rata to the covered fraction. */
  private static long clippedTuples(ChunkLoad chunk, OffsetDateTime from, OffsetDateTime to) {
    long whole = Duration.between(chunk.from(), chunk.to()).getSeconds();
    long clipped = Duration.between(from, to).getSeconds();
    return whole <= 0 || clipped >= whole ? chunk.tuples() : chunk.tuples() * clipped / whole;
  }

  private static long ceilDiv(long numerator, long denominator) {
    return (numerator + denominator - 1) / denominator;
  }

  /** Subdivides any segment longer than {@code maxSpanDays} into equal parts within the bound. */
  private static List<OffsetDateTime> capSpan(List<OffsetDateTime> cuts, int maxSpanDays) {
    List<OffsetDateTime> capped = new ArrayList<>();
    for (int i = 0; i < cuts.size() - 1; i++) {
      OffsetDateTime from = cuts.get(i);
      OffsetDateTime to = cuts.get(i + 1);
      capped.add(from);
      long seconds = Duration.between(from, to).getSeconds();
      long parts = ceilDiv(seconds, Duration.ofDays(maxSpanDays).getSeconds());
      for (long k = 1; k < parts; k++) {
        capped.add(from.plusSeconds(seconds * k / parts));
      }
    }
    capped.add(cuts.get(cuts.size() - 1));
    return capped;
  }

  /** Contiguous cuts → OVERLAPPING windows, each extended {@code overlapDays} past its cut. */
  private static List<Window> windowsFrom(
      List<OffsetDateTime> cuts, OffsetDateTime end, int overlapDays) {
    List<Window> windows = new ArrayList<>();
    for (int i = 0; i < cuts.size() - 1; i++) {
      OffsetDateTime to = cuts.get(i + 1).plusDays(overlapDays);
      windows.add(new Window(cuts.get(i), to.isAfter(end) ? end : to));
    }
    return windows;
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
   * What a {@link #stitchInto} call actually INSERTED: the row count and the bucket range those
   * rows span ({@code null} bounds when {@code rows == 0}). The range is the stitch's real cagg
   * invalidation — it is NOT the requested window, and the gap between the two is the whole point
   * (see {@link #stitchInto}).
   */
  public record StitchedRange(int rows, OffsetDateTime firstBucket, OffsetDateTime lastBucket) {

    /** Nothing was inserted — nothing was invalidated, so nothing needs refreshing. */
    static final StitchedRange NONE = new StitchedRange(0, null, null);
  }

  /**
   * Copies one contract's bars (1m + 1d) into a CONT synthetic symbol for a date window —
   * UNADJUSTED, idempotent (B-19: the stitch is local arithmetic, never Kite's roll-unaware
   * {@code continuous=true} concatenation).
   *
   * <p>Returns the range it INSERTED rather than a bare count, because the caller's requested
   * window is a wildly unsafe proxy for it. {@code ContinuousFuturesRoller.stitch} asks for the
   * front contract's whole nominal segment, which starts at its {@code STITCH_EPOCH} of 2000-01-01;
   * the contract itself only has a few months of bars, and {@code ON CONFLICT DO NOTHING} makes a
   * daily re-run insert just TODAY's. Refreshing the requested window therefore re-materialized ~26
   * years of continuous aggregates every evening to publish one day of new buckets. Once V049
   * compressed the caggs that stopped being merely wasteful and started FAILING: the roll aborted
   * for all six index roots on 2026-08-04 with {@code tuple decompression limit exceeded by
   * operation … tuples decompressed: 341820} on {@code candles_5m}'s materialization hypertable,
   * because the window reached back into chunks compressed years ago.
   *
   * <p>{@code RETURNING} inside a data-modifying CTE yields ONLY the rows that were really
   * inserted — a bucket skipped by {@code DO NOTHING} never appears — so the aggregate over it is
   * exactly the invalidated span. Pinned by {@code ContinuousFuturesStitchRangeIntegrationTest}.
   */
  public StitchedRange stitchInto(
      String contSymbol, String exchange, String fromSymbol, OffsetDateTime from, OffsetDateTime to) {
    StitchedRange stitched =
        jdbc.query(
            """
            WITH stitched AS (
              INSERT INTO candles
                (exchange, tradingsymbol, "interval", bucket, open, high, low, close, volume, oi, source, fetched_at)
              SELECT exchange, ?, "interval", bucket, open, high, low, close, volume, oi, source, now()
              FROM candles
              WHERE exchange = ? AND tradingsymbol = ? AND "interval" IN ('1m','1d')
                AND bucket >= ? AND bucket < ?
              ON CONFLICT (exchange, tradingsymbol, "interval", bucket) DO NOTHING
              RETURNING bucket
            )
            SELECT count(*) AS rows, min(bucket) AS lo, max(bucket) AS hi FROM stitched
            """,
            rs -> {
              if (!rs.next()) {
                return StitchedRange.NONE;
              }
              int rows = rs.getInt("rows");
              return rows == 0
                  ? StitchedRange.NONE
                  : new StitchedRange(
                      rows,
                      rs.getObject("lo", OffsetDateTime.class),
                      rs.getObject("hi", OffsetDateTime.class));
            },
            contSymbol, exchange, fromSymbol,
            Timestamp.from(from.toInstant()), Timestamp.from(to.toInstant()));
    return stitched == null ? StitchedRange.NONE : stitched;
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
   * exactly the unguarded destruction that V057 exists to prevent.
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
   * Batched insert into the corporate-action rebuild STAGING buffer (V057). Plain replace-on-conflict
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
   * @param cachedBarsBelowSpan live buckets OLDER than {@code stagedFrom}. The swap does not touch
   *     them, so after a corporate action they keep PRE-event prices while everything from
   *     {@code stagedFrom} forward is adjusted — a ratio-sized discontinuity with no gap marking
   *     it. Nothing else can see this: the detector's deepest anchor is 5 years, so it cannot reach
   *     the splice, and {@code cachedBarsNotStaged} is scoped inside the span by construction.
   *     Measured live 2026-08-04: 1,276 1d rows across 49 symbols sit older than the default
   *     {@code rebackfill-days-1d} window, and every one of those symbols is in the sweep's scope
   */
  public record StagedCoverage(
      long stagedBars,
      OffsetDateTime stagedFrom,
      OffsetDateTime stagedTo,
      long cachedBarsNotStaged,
      OffsetDateTime cachedTo,
      long cachedBarsBelowSpan) {}

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
                                      AND g.bucket = k.bucket)) AS not_staged,
               (SELECT count(*) FROM candles b
                 WHERE b.exchange = ? AND b.tradingsymbol = ? AND b."interval" = ?
                   AND b.bucket < s.lo) AS below_span
        FROM (SELECT count(*) AS n, min(bucket) AS lo, max(bucket) AS hi
                FROM candle_rebuild_staging
               WHERE exchange = ? AND tradingsymbol = ? AND "interval" = ?) s,
             (SELECT max(bucket) AS hi FROM candles
               WHERE exchange = ? AND tradingsymbol = ? AND "interval" = ?) c
        """,
        rs -> {
          if (!rs.next()) {
            return new StagedCoverage(0, null, null, 0, null, 0);
          }
          return new StagedCoverage(
              rs.getLong("staged_n"),
              rs.getObject("staged_lo", OffsetDateTime.class),
              rs.getObject("staged_hi", OffsetDateTime.class),
              rs.getLong("not_staged"),
              rs.getObject("cached_hi", OffsetDateTime.class),
              rs.getLong("below_span"));
        },
        exchange, tradingsymbol, interval,
        exchange, tradingsymbol, interval,
        exchange, tradingsymbol, interval,
        exchange, tradingsymbol, interval);
  }

  /**
   * The SWAP: replaces one (symbol, interval)'s live bars over EXACTLY the staged span with the
   * staged ones. Returns the rows inserted.
   *
   * <p>⚠️ The window loop runs OLDEST → NEWEST ({@link #purgeWindows} advances a cursor from
   * {@code start}), and that direction is load-bearing, not incidental. A failure part-way through
   * therefore leaves a RECENT SUFFIX unswapped, which keeps the detector's short-dated anchors
   * (7d/1m/3m) diverged — two or more, so the next sweep re-detects. Newest-first would leave the
   * OLD end unswapped, and the deepest anchors alone can be a SINGLE diverged anchor, which
   * {@code CorporateActionDetector} classifies as {@code anchorNoise} and never remediates. Nothing
   * else pins this direction; reversing it would strand symbols silently.
   *
   * <p>Scoped to the staged span rather than the whole symbol on purpose — the invariant this whole
   * change buys is "every bucket deleted has a verified replacement", and outside the staged span
   * there is none.
   *
   * <p>⚠️ That is less DESTRUCTIVE than the {@link #purgeSymbol} it replaces on this path — which
   * deleted bars older than Kite's serving depth that nothing could restore — but less destructive
   * is NOT the same as safer, and reading it that way would be the wrong inference. The old purge
   * produced a visible TRUNCATION; this produces a silent WRONG PRICE, because bars below the
   * staged span survive at pre-event levels while everything above is adjusted. On the same data
   * that is arguably the worse failure mode: a gap is obvious to every consumer, a discontinuity is
   * obvious to none. It is deliberately accepted rather than fixed here (refusing would strand the
   * 49 live symbols that have such bars), and it is made OBSERVABLE instead — see
   * {@link StagedCoverage#cachedBarsBelowSpan}, which the caller counts and alerts on.
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
    int windowsCommitted = 0;
    for (Window w : purgeWindows(bounds.from(), bounds.to(), Period.ofMonths(PURGE_WINDOW_MONTHS))) {
      Timestamp from = Timestamp.from(w.from().toInstant());
      Timestamp to = Timestamp.from(w.to().toInstant());
      try {
        inserted += replaceWindowAtomically(exchange, tradingsymbol, interval, from, to);
        windowsCommitted++;
      } catch (RuntimeException failure) {
        // ⚠️ A failure PARTWAY THROUGH an interval is still progress, and the caller cannot see it
        // any other way (cross-vendor review 2026-08-10). Each window commits independently, so
        // windows before this one are DURABLE: the live series is now internally split between
        // adjusted and unadjusted windows. Reported as a plain failure the caller would record
        // ordinary FAILED, and the cooldown would hold that split series for seven days — the exact
        // hole V056 closes for a whole-interval partial. The caller needs "any window committed",
        // not "the interval finished", so the exception carries it.
        if (windowsCommitted > 0) {
          throw new PartialSwapException(interval, windowsCommitted, failure);
        }
        throw failure;
      }
    }
    // fetched_at is stamped now() rather than copied: the Stage-D dataHash reads it to flag
    // pre-event backtest runs as not-like-for-like, which is exactly what a rebuild must trigger.
    return inserted;
  }

  /**
   * Thrown when an interval swap fails AFTER at least one six-month window has durably committed.
   *
   * <p>It exists so the caller can distinguish "nothing landed" from "the series is now half
   * replaced". Those need opposite recoveries: the first is an ordinary failure the cooldown should
   * bound, the second must be re-attempted promptly because the live series is internally
   * inconsistent until it is.
   */
  public static class PartialSwapException extends RuntimeException {
    private final transient String interval;
    private final transient int windowsCommitted;

    public PartialSwapException(String interval, int windowsCommitted, Throwable cause) {
      super(
          "swap of " + interval + " failed after " + windowsCommitted + " window(s) had committed",
          cause);
      this.interval = interval;
      this.windowsCommitted = windowsCommitted;
    }

    /** The interval left half-replaced. */
    public String interval() {
      return interval;
    }

    /** How many windows are already durable. */
    public int windowsCommitted() {
      return windowsCommitted;
    }
  }

  /** Deletes and refills one window in one commit, so readers never observe an empty window. */
  private int replaceWindowAtomically(
      String exchange, String tradingsymbol, String interval, Timestamp from, Timestamp to) {
    return jdbc.execute(
        (ConnectionCallback<Integer>)
            connection -> {
              boolean manageTransaction = connection.getAutoCommit();
              if (manageTransaction) {
                connection.setAutoCommit(false);
              }
              boolean committed = false;
              boolean rolledBack = false;
              try {
                int inserted;
                try (PreparedStatement delete =
                        connection.prepareStatement(
                            "DELETE FROM candles WHERE exchange = ? AND tradingsymbol = ?"
                                + " AND \"interval\" = ? AND bucket >= ? AND bucket < ?");
                    PreparedStatement insert =
                        connection.prepareStatement(
                            """
                            INSERT INTO candles
                              (exchange, tradingsymbol, "interval", bucket, open, high, low, close,
                               volume, oi, source, fetched_at)
                            SELECT exchange, tradingsymbol, "interval", bucket, open, high, low, close,
                                   volume, oi, source, now()
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
                            """)) {
                  delete.setString(1, exchange);
                  delete.setString(2, tradingsymbol);
                  delete.setString(3, interval);
                  delete.setTimestamp(4, from);
                  delete.setTimestamp(5, to);
                  delete.executeUpdate();

                  insert.setString(1, exchange);
                  insert.setString(2, tradingsymbol);
                  insert.setString(3, interval);
                  insert.setTimestamp(4, from);
                  insert.setTimestamp(5, to);
                  inserted = insert.executeUpdate();
                }
                if (manageTransaction) {
                  connection.commit();
                  committed = true;
                }
                return inserted;
              } catch (SQLException | RuntimeException failure) {
                if (manageTransaction) {
                  try {
                    connection.rollback();
                    rolledBack = true;
                  } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                    abortQuietly(connection);
                  }
                }
                throw failure;
              } finally {
                // ⚠️ Restoration must never REPLACE the outcome (cross-vendor review 2026-08-10).
                // Unguarded, a throw here does one of two harmful things: after a rollback it
                // supplants the original exception, so the caller is told about a JDBC housekeeping
                // failure instead of the DB error that actually aborted the swap; and after a
                // COMMIT it turns durable work into a thrown swapStaged, so the caller never
                // records the interval as swapped and the alert under-reports what landed.
                //
                // Aborting is the safe direction either way. A connection returned to the pool with
                // autoCommit=false silently enrols the NEXT unrelated caller in a transaction
                // nobody commits — a whole-service hazard that no test on this path would catch.
                if (manageTransaction && (committed || rolledBack)) {
                  try {
                    connection.setAutoCommit(true);
                  } catch (SQLException restoreFailure) {
                    abortQuietly(connection);
                  }
                }
              }
            });
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
