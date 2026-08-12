package in.arthayantra.marketdata.options.analytics;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.options.OiInterval;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Query-time downsample of option_chain_snapshots (NO cagg — decision 2026-06-15).
 *
 * <p><b>End-of-window bucketing (audit 2026-07-02 §9.1, T2):</b> every bucket expression shifts
 * {@code ts} back one second, so a capture stamped exactly ON a bucket boundary labels into the
 * window it TERMINATES — bucket {@code [B, B+iv)} carries the chain state observed AT {@code B+iv}.
 * That matches both the candle-derived history convention ({@code last(oi)} of the window) and the
 * oipulse barometer's boundary-sample rows, and it kills the post-close artifact bucket (the
 * 15:30:00 EOD capture labels into the final session window). Legacy mid-bucket captures are
 * unaffected ({@code ts − 1s} stays in the same bucket). The raw-ts window predicates shift the
 * same second (callers keep passing bucket-domain {@code [from, to)}).
 */
@Repository
public class OptionsSnapshotReader {

  private final JdbcTemplate jdbc;
  private final MarketCalendar calendar;

  public OptionsSnapshotReader(JdbcTemplate jdbc, MarketCalendar calendar) {
    this.jdbc = jdbc;
    this.calendar = calendar;
  }

  /**
   * One downsampled point per (bucket, strike, optionType): last() of each point-in-time stat.
   *
   * <p>Nullability is load-bearing now that this record is ENUMERATED into the OpenAPI spec (D3:
   * {@code /oi-analysis} + {@code /oi-analysis/strike-series} stopped returning an opaque {@code
   * Map}). Only the three PK-derived columns are NOT NULL in V006 ({@code ts} / {@code strike} /
   * {@code option_type}); {@code ltp} / {@code oi} / {@code volume} / {@code iv} are nullable
   * columns, {@code oi_change} is nullable by V007_1 and is additionally null for the FIRST bucket
   * of each leg on the candle-derived path, and {@code spot} is null whenever the bucket carried no
   * underlying sample. The candle-derived reader leaves {@code iv} null except on the ATM band it
   * back-solves ({@code CandleDerivedChainReader.enrichIv}) — so a null {@code iv} is a SYMPTOM of
   * derivation, never its identity, and nothing may key provenance on it. {@code
   * OptionsAnalyticsController.oiFreshness} used to, and mislabelled both directions; it now asks
   * {@link #allGroupsCaptured} for the winning row's {@code source} instead.
   *
   * <p>Spelled as the 3.1 type union, NOT {@code @Schema(nullable = true)} — the latter is a silent
   * no-op at OpenAPI 3.1 and would publish these as non-nullable, a lie in the generated TS.
   *
   * <p>⚠️ Every {@link BigDecimal} here is declared {@code string}, not {@code number}, because
   * {@code ArthaJacksonAutoConfiguration} registers {@code ToStringSerializer} for {@code
   * BigDecimal} platform-wide (money is never a float on our wire). Bare springdoc infers {@code
   * number} from the Java type and would publish a type the service never emits — which is worse
   * than the opaque Map this record replaced, since a generated client would now confidently parse
   * the wrong thing. The {@code Long} columns are genuinely JSON numbers and stay {@code integer}.
   *
   * <p>⚠️ <b>SPELLING TRAP, measured here 2026-08-01.</b> {@code types} does NOT replace the
   * inferred type, it UNIONS with it: {@code @Schema(types = {"string", "null"})} on a {@code
   * BigDecimal} captures as {@code ["number","string","null"]}, a three-type union that still
   * advertises the impossible {@code number}. To RETYPE a nullable field you must set the base type
   * as well — {@code @Schema(type = "string", types = {"string", "null"})} — which captures cleanly
   * as {@code ["string","null"]}. The bare {@code types}-only form documented in CLAUDE.md is only
   * correct when the declared base MATCHES what springdoc already inferred (e.g. {@code
   * {"number","null"}} on a {@code BigDecimal}, or {@code {"integer","null"}} on a {@code Long} —
   * which is why it has always looked right). Verify any retype by reading the captured spec, not
   * by trusting the annotation.
   */
  public record StrikePoint(
      OffsetDateTime bucket,
      @Schema(type = "string") BigDecimal strike,
      String optionType,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal ltp,
      @Schema(types = {"integer", "null"}) Long oi,
      @Schema(types = {"integer", "null"}) Long oiChange,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal iv,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal spot,
      @Schema(types = {"integer", "null"}) Long volume) {}

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
      BigDecimal prevClose,
      // W3 PR-6: the per-strike session change-in-OI % (last-bucket OI vs first-bucket OI), for the
      // Day-14 p20 AVOID veto (ΔOI > 50% = a bigger player took the opposite side). Folded from the
      // StrikePoint OI already read for this session — no second DB read. Null when OI is unavailable.
      BigDecimal oiChangePct) {}

  /**
   * As {@link #series} but scoped to a SINGLE {@code strike} (both CE + PE) — the per-strike
   * intraday time-series the oipulse "Options OI Analysis" page (buckets-on-rows) needs. Filtering
   * in SQL keeps the read bounded (~buckets × 2 rows) rather than pulling the full chain's session.
   *
   * <p>{@code oi_change} here is the BUCKET-LAG delta ({@code last(oi)} vs the prior bucket's), not
   * the carried 3-min captured {@code oi_change} — on a resampled interval (5m/15m/…) the carried
   * value is only the final capture slice's delta, which diverged from oipulse's
   * endpoint-to-endpoint Δ (value-verify F5). This also matches {@code CandleDerivedChainReader}'s
   * derived-history semantics (bucket-lag, first bucket null). The full-chain {@link #series} read
   * deliberately keeps the captured value — the live gate's sentiment/trending dots are calibrated
   * on it and must stay byte-identical.
   */
  public List<StrikePoint> strikeSeries(
      String underlying,
      LocalDate expiry,
      BigDecimal strike,
      OiInterval interval,
      OffsetDateTime from,
      OffsetDateTime to) {
    String sql =
        "SELECT b, strike, option_type, ltp, oi, "
            + "  oi - lag(oi) OVER (PARTITION BY option_type ORDER BY b) AS oi_change, "
            + "  iv, spot, volume FROM ("
            + "SELECT public.time_bucket(INTERVAL '"
            + interval.pgInterval()
            + "', ts - INTERVAL '1 second', 'Asia/Kolkata') AS b, "
            + "  strike, option_type, "
            + "  public.last(ltp, ts) AS ltp, public.last(oi, ts) AS oi, "
            + "  public.last(iv, ts) AS iv, "
            + "  public.last(spot_price, ts) AS spot, public.last(volume, ts) AS volume "
            + "FROM options_chain_snapshots "
            // audit V6: OI folds skip quarantined outlier rows (NULL on old rows = not quarantined)
            + "WHERE underlying = ? AND expiry = ? AND strike = ? AND ts >= ? AND ts < ? "
            + "  AND (quarantined IS NOT TRUE) "
            + "GROUP BY b, strike, option_type) t "
            + "ORDER BY b, option_type";
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
        strike,
        Timestamp.from(from.plusSeconds(1).toInstant()),
        Timestamp.from(to.plusSeconds(1).toInstant()));
  }

  /**
   * Per-(strike, optionType) per-IST-day EOD rollup of the option premium, for the oipulse "Options EOD
   * OI Analysis" page (plan §options/oi-expiry-strategy). One row per (strike, leg, trade day) over
   * [{@code from}, {@code to}): {@code open} = first ltp of the day, {@code high}/{@code low} = max/min
   * ltp, {@code close} = last ltp, {@code oiClose} = last oi, {@code volume} = last cumulative day
   * volume (Kite quote.volume). Aggregated straight from the day's raw snapshots (no bucketing — the
   * daily OHLC of premium needs every captured point). Ordered (strike, leg, day) so the caller folds
   * each leg's day series in chronological order to derive the day-over-day % changes + 4-state
   * interpretation + all-day-high/low flags.
   */
  public record OptionEodRow(
      BigDecimal strike,
      String optionType,
      LocalDate tradeDate,
      BigDecimal open,
      BigDecimal high,
      BigDecimal low,
      BigDecimal close,
      Long oiClose,
      Long volume) {}

  public List<OptionEodRow> eodSeries(
      String underlying, LocalDate expiry, OffsetDateTime from, OffsetDateTime to) {
    String sql =
        "SELECT (ts AT TIME ZONE 'Asia/Kolkata')::date AS d, strike, option_type, "
            + "  public.first(ltp, ts) AS o, max(ltp) AS h, min(ltp) AS l, "
            + "  public.last(ltp, ts) AS c, public.last(oi, ts) AS oi_close, "
            + "  public.last(volume, ts) AS vol "
            + "FROM options_chain_snapshots "
            // audit V6: OI folds skip quarantined outlier rows (NULL on old rows = not quarantined)
            + "WHERE underlying = ? AND expiry = ? AND ts >= ? AND ts < ? AND (quarantined IS NOT TRUE) "
            + "GROUP BY d, strike, option_type "
            + "ORDER BY strike, option_type, d";
    return jdbc.query(
        sql,
        (rs, n) ->
            new OptionEodRow(
                rs.getBigDecimal("strike"),
                rs.getString("option_type"),
                rs.getObject("d", LocalDate.class),
                rs.getBigDecimal("o"),
                rs.getBigDecimal("h"),
                rs.getBigDecimal("l"),
                rs.getBigDecimal("c"),
                rs.getObject("oi_close", Long.class),
                rs.getObject("vol", Long.class)),
        underlying,
        java.sql.Date.valueOf(expiry),
        Timestamp.from(from.toInstant()),
        Timestamp.from(to.toInstant()));
  }

  public List<StrikePoint> series(
      String underlying,
      LocalDate expiry,
      OiInterval interval,
      OffsetDateTime from,
      OffsetDateTime to) {
    String sql =
        "SELECT public.time_bucket(INTERVAL '"
            + interval.pgInterval()
            + "', ts - INTERVAL '1 second', 'Asia/Kolkata') AS b, "
            + "  strike, option_type, "
            + "  public.last(ltp, ts) AS ltp, public.last(oi, ts) AS oi, "
            + "  public.last(oi_change, ts) AS oi_change, public.last(iv, ts) AS iv, "
            + "  public.last(spot_price, ts) AS spot, public.last(volume, ts) AS volume "
            + "FROM options_chain_snapshots "
            // audit V6: OI folds skip quarantined outlier rows (NULL on old rows = not quarantined)
            + "WHERE underlying = ? AND expiry = ? AND ts >= ? AND ts < ? AND (quarantined IS NOT TRUE) "
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
        Timestamp.from(from.plusSeconds(1).toInstant()),
        Timestamp.from(to.plusSeconds(1).toInstant()));
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
              prevClose.get(key(first.strike(), first.optionType())),
              sessionOiChangePct(first.oi(), lastPt.oi())));
    }
    return out;
  }

  /**
   * W3 PR-6: the per-strike session change-in-OI % — (last-bucket OI - first-bucket OI) / first-bucket
   * OI * 100, scale 4 HALF_UP — for the Day-14 p20 AVOID veto. Null when either OI is absent or the
   * first-bucket OI is zero (no base to divide by).
   */
  private static BigDecimal sessionOiChangePct(Long firstOi, Long lastOi) {
    if (firstOi == null || lastOi == null || firstOi == 0L) {
      return null;
    }
    return BigDecimal.valueOf(lastOi - firstOi)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(firstOi), 4, RoundingMode.HALF_UP);
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
                + "', max(ts) - INTERVAL '1 second', 'Asia/Kolkata') AS b "
                + "FROM options_chain_snapshots "
                + "WHERE underlying = ? AND expiry = ? AND (quarantined IS NOT TRUE)");
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

  /**
   * As {@link #latestPair(String, LocalDate, OiInterval)} but {@code date}-scoped (history mode).
   *
   * <p><b>Why two {@code max(ts)} aggregates and not {@code SELECT DISTINCT time_bucket(..) ORDER BY
   * b DESC LIMIT 2}.</b> TimescaleDB 2.18.2 aborts planning with {@code "non-Var pathkey not expected
   * for compressed batch sorted merge"} whenever a top-level {@code DISTINCT}/{@code ORDER BY}/{@code
   * GROUP BY} key is {@code time_bucket(iv, <expression>, tz)} — i.e. our end-of-window {@code ts -
   * INTERVAL '1 second'} shift — AND the query carries a {@code LIMIT}, over a hypertable with
   * compressed chunks. {@code time_bucket(iv, ts, tz)} on the bare column is fine; the shift is what
   * makes the sort key a non-Var. That defect took all three OI confluence dots offline for the whole
   * 2026-07-20 session (see docs/signal-analysis/2026-07-20-session-findings.md §6.2). These two
   * aggregates carry NO pathkey at all, so the sorted-merge path is never considered and the
   * optimisation stays enabled for every other read.
   *
   * <p>Correctness rests on the end-of-window convention this class documents: a row belongs to
   * bucket {@code B} iff {@code B < ts <= B + interval}. So every row NOT in the newest bucket
   * satisfies {@code ts <= newestBucketStart}, and the bucket of the newest such row IS the prior
   * non-empty bucket — gap robust, exactly what the {@code DISTINCT}-and-take-two form returned.
   */
  public List<StrikePoint> latestPair(
      String underlying, LocalDate expiry, OiInterval interval, LocalDate date) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT public.time_bucket(INTERVAL '"
                + interval.pgInterval()
                + "', max(ts) - INTERVAL '1 second', 'Asia/Kolkata') AS b "
                + "FROM options_chain_snapshots "
                + "WHERE underlying = ? AND expiry = ? AND (quarantined IS NOT TRUE)");
    List<Object> args = new ArrayList<>();
    args.add(underlying);
    args.add(java.sql.Date.valueOf(expiry));
    appendDayFilter(sql, args, date);
    OffsetDateTime newestBucket = queryBucket(sql.toString(), args);
    if (newestBucket == null) {
      return List.of();
    }
    List<Object> priorArgs = new ArrayList<>(args);
    priorArgs.add(Timestamp.from(newestBucket.toInstant()));
    OffsetDateTime priorBucket = queryBucket(sql + " AND ts <= ?", priorArgs);
    OffsetDateTime earliest = priorBucket == null ? newestBucket : priorBucket;
    return series(underlying, expiry, interval, earliest, newestBucket.plus(interval.bucket()));
  }

  /**
   * True iff EVERY (bucket, strike, optionType) group in [{@code from}, {@code to}) resolves to a
   * LIVE-CAPTURED row — the provenance discriminator behind the OI reads' freshness envelope ({@code
   * OptionsAnalyticsController.oiFreshness}). False when the window holds no group at all, which is
   * the candle-derived reader's case (it writes nothing).
   *
   * <p><b>Grouped by the SAME key and resolved by the SAME winner as {@link #series}, deliberately.</b>
   * {@code series} emits one row per (bucket, strike, optionType) whose every value is {@code
   * last(…, ts)} — the newest row in the group WINS. So the provenance a response actually carries is
   * the provenance of that winning row, and nothing else. Asking the weaker question "does the window
   * hold any captured row?" promotes a mixed bucket to {@code live} while serving derived values: a
   * 09:24 {@code LIVE} row and a 09:25 {@code UPSTOX_1M} row land in the same 5-minute bucket, {@code
   * last()} takes the 09:25 derived values, and the read is labelled a capture. The same hole opens
   * across strikes — one derived leg beside captured legs is a partly-derived chain. Cross-vendor
   * review caught both on #1240; the homogeneous fixtures could not reach either.
   *
   * <p>"Live-captured" is the SAME predicate {@code OptionsSnapshotRepository}'s {@code CAPTURED_ONLY}
   * already uses to decide whether a stored chain may be served back as the live one: {@code source}
   * is NULL on pre-V023 rows ("read as live by convention", V023's own wording) and {@code 'LIVE'} on
   * every capture since, while every other label is a DERIVATION rather than a capture — {@code
   * 'BACKFILL'} (the OI importer's 1m history) and {@code 'UPSTOX_1M'} (the on-demand stock-chain
   * warm, {@code StockChainWarmService}). The {@code COALESCE(source, 'LIVE')} INSIDE the aggregate
   * folds the NULL convention in per row, so the classification never depends on whether {@code
   * last()} propagates or skips a NULL value. Quarantined rows are excluded because every OI fold
   * excludes them, so they can never be rows a response was built from.
   *
   * <p>The {@code +1s} shift on both bounds and the {@code ts - INTERVAL '1 second'} bucket argument
   * both mirror {@link #series}, so the groups counted here are exactly the groups it returns.
   *
   * <p>Shape safety: {@code GROUP BY <expression>, <col>, <col>} is the multi-key form that is SAFE
   * under TimescaleDB 2.18.2 — identical to {@link #series}, which runs it live — and there is no
   * {@code ORDER BY}/{@code DISTINCT}/{@code LIMIT} anywhere, so the compressed-batch sorted-merge
   * planner assertion ({@link #latestPair}) has no pathkey to trip on.
   */
  public boolean allGroupsCaptured(
      String underlying,
      LocalDate expiry,
      OiInterval interval,
      OffsetDateTime from,
      OffsetDateTime to) {
    String sql =
        "SELECT count(*) AS groups, count(*) FILTER (WHERE won <> 'LIVE') AS derived_groups FROM ("
            + "SELECT public.last(COALESCE(source, 'LIVE'), ts) AS won "
            + "FROM options_chain_snapshots "
            + "WHERE underlying = ? AND expiry = ? AND ts >= ? AND ts < ? "
            + "  AND (quarantined IS NOT TRUE) "
            + "GROUP BY public.time_bucket(INTERVAL '"
            + interval.pgInterval()
            + "', ts - INTERVAL '1 second', 'Asia/Kolkata'), strike, option_type) g";
    List<Boolean> out =
        jdbc.query(
            sql,
            (rs, n) -> rs.getLong("groups") > 0 && rs.getLong("derived_groups") == 0,
            underlying,
            java.sql.Date.valueOf(expiry),
            Timestamp.from(from.plusSeconds(1).toInstant()),
            Timestamp.from(to.plusSeconds(1).toInstant()));
    return !out.isEmpty() && Boolean.TRUE.equals(out.get(0));
  }

  /** Runs a single-row bucket aggregate; null when the filtered set is empty. */
  private OffsetDateTime queryBucket(String sql, List<Object> args) {
    List<OffsetDateTime> rows =
        jdbc.query(sql, (rs, n) -> rs.getObject("b", OffsetDateTime.class), args.toArray());
    return rows.isEmpty() ? null : rows.get(0);
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
