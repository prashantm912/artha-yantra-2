package in.arthayantra.marketdata.context;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.options.OiInterval;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Cross-expiry compare (intelligence-layer design 2026-07-10 §6.4 / P1-§6.9) — two options
 * {@code /trending} folds for the same underlying, one per expiry, server-zipped by bucket. Folds the
 * EXISTING {@code options_chain_snapshots} directly (the same {@code public.last(col, ts)} over IST
 * {@code time_bucket} end-of-window convention as {@code OptionsSnapshotReader}/{@code
 * OiTrendingService}) rather than injecting those internal {@code options.analytics} types — a
 * {@code context}-module leaf reads base-package types only.
 *
 * <p><b>Trust.</b> Each side carries its own {@code dataTrust}; the compare degrades when either side
 * is stale-anchored on a prior session (#745's live stale-anchor rule) or read historically, and
 * blocks only when BOTH sides have no snapshot. It never 5xxes on missing data.
 */
@Service
public class ExpiryCompareService {

  /** One trending bucket for one expiry. */
  public record TrendPoint(
      OffsetDateTime bucket,
      long totalOi,
      long ceOi,
      long peOi,
      @Schema(types = {"number", "null"}) BigDecimal pcr,
      @Schema(types = {"number", "null"}) BigDecimal spot,
      @Schema(types = {"number", "null"}) BigDecimal ceLtp,
      @Schema(types = {"number", "null"}) BigDecimal peLtp) {}

  /** One expiry's side of the compare (its trending series + its own trust). */
  public record ExpirySide(
      LocalDate expiry,
      List<TrendPoint> series,
      OffsetDateTime asOf,
      String dataTrust,
      List<String> trustReasons) {}

  /** One aligned bucket present in either side (nulls where a side has no bucket at that time). */
  public record AlignedPoint(
      OffsetDateTime bucket,
      Long aTotalOi,
      Long bTotalOi,
      BigDecimal aPcr,
      BigDecimal bPcr,
      BigDecimal aSpot,
      BigDecimal bSpot) {}

  /** The cross-expiry compare. {@code dataTrust} = OK/DEGRADED/BLOCKED. */
  public record ExpiryCompare(
      String underlying,
      ExpirySide a,
      ExpirySide b,
      List<AlignedPoint> aligned,
      OffsetDateTime asOf,
      String dataTrust,
      List<String> trustReasons) {

    static ExpiryCompare blocked(String underlying, ExpirySide a, ExpirySide b, String reason) {
      return new ExpiryCompare(underlying, a, b, List.of(), null, "BLOCKED", List.of(reason));
    }
  }

  private final JdbcTemplate jdbc;
  private final Clock clock;

  public ExpiryCompareService(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /**
   * Compare two expiries of {@code name}. {@code date} null = live (each side anchors on its newest
   * bucket overall); a past IST date reads that session. {@code interval} defaults to 3m.
   */
  public ExpiryCompare compare(String name, LocalDate date, OiInterval interval, LocalDate expiryA, LocalDate expiryB) {
    ExpirySide a = side(name, expiryA, interval, date);
    ExpirySide b = side(name, expiryB, interval, date);
    if (a.series().isEmpty() && b.series().isEmpty()) {
      return ExpiryCompare.blocked(name, a, b, "no snapshot for either expiry");
    }

    List<AlignedPoint> aligned = align(a, b);
    List<String> reasons = new ArrayList<>();
    if (!"OK".equals(a.dataTrust())) {
      reasons.add(expiryA + ": " + String.join(", ", a.trustReasons()));
    }
    if (!"OK".equals(b.dataTrust())) {
      reasons.add(expiryB + ": " + String.join(", ", b.trustReasons()));
    }
    String trust = reasons.isEmpty() ? "OK" : "DEGRADED";
    OffsetDateTime asOf = maxAsOf(a.asOf(), b.asOf());
    return new ExpiryCompare(name, a, b, aligned, asOf, trust, List.copyOf(reasons));
  }

  private ExpirySide side(String name, LocalDate expiry, OiInterval interval, LocalDate date) {
    OffsetDateTime newest = newestBucket(name, expiry, interval, date);
    if (newest == null) {
      return new ExpirySide(expiry, List.of(), null, "BLOCKED", List.of("no snapshot for " + name + " " + expiry));
    }
    LocalDate sessionDay = newest.atZoneSameInstant(Ist.ZONE).toLocalDate();
    OffsetDateTime from = sessionDay.atTime(MarketCalendar.SESSION_OPEN).atOffset(Ist.OFFSET);
    List<TrendPoint> series = trending(name, expiry, interval, from, newest.plus(interval.bucket()));

    List<String> reasons = new ArrayList<>();
    if (date == null) {
      LocalDate todayIst = LocalDate.ofInstant(clock.instant(), Ist.ZONE);
      if (!sessionDay.equals(todayIst)) {
        reasons.add("stale snapshot - newest bucket is " + sessionDay + ", not today");
      }
    } else {
      reasons.add("historical read");
    }
    String trust = reasons.isEmpty() ? "OK" : "DEGRADED";
    return new ExpirySide(expiry, series, newest, trust, List.copyOf(reasons));
  }

  /** The newest IST bucket for (name, expiry); {@code date}-scoped in history mode. */
  private OffsetDateTime newestBucket(String name, LocalDate expiry, OiInterval interval, LocalDate date) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT public.time_bucket(INTERVAL '"
                + interval.pgInterval()
                + "', max(ts) - INTERVAL '1 second', 'Asia/Kolkata') AS b "
                + "FROM options_chain_snapshots WHERE underlying = ? AND expiry = ?");
    List<Object> args = new ArrayList<>();
    args.add(name);
    args.add(java.sql.Date.valueOf(expiry));
    if (date != null) {
      sql.append(" AND (ts AT TIME ZONE 'Asia/Kolkata')::date = ?");
      args.add(java.sql.Date.valueOf(date));
    }
    List<OffsetDateTime> b =
        jdbc.query(sql.toString(), (rs, n) -> rs.getObject("b", OffsetDateTime.class), args.toArray());
    return b.isEmpty() ? null : b.get(0);
  }

  /** Per-bucket CE/PE OI + summed premium + spot: last() per strike, then sum across the chain. */
  private List<TrendPoint> trending(
      String name, LocalDate expiry, OiInterval interval, OffsetDateTime from, OffsetDateTime to) {
    String sql =
        "SELECT bucket,"
            + " coalesce(sum(oi) FILTER (WHERE option_type = 'CE'), 0) AS ce_oi,"
            + " coalesce(sum(oi) FILTER (WHERE option_type = 'PE'), 0) AS pe_oi,"
            + " sum(ltp) FILTER (WHERE option_type = 'CE') AS ce_ltp,"
            + " sum(ltp) FILTER (WHERE option_type = 'PE') AS pe_ltp,"
            + " max(spot) AS spot"
            + " FROM ("
            + "   SELECT public.time_bucket(INTERVAL '"
            + interval.pgInterval()
            + "', ts - INTERVAL '1 second', 'Asia/Kolkata') AS bucket, strike, option_type,"
            + "          public.last(oi, ts) AS oi, public.last(ltp, ts) AS ltp,"
            + "          public.last(spot_price, ts) AS spot"
            + "   FROM options_chain_snapshots"
            + "   WHERE underlying = ? AND expiry = ? AND ts >= ? AND ts < ?"
            + "   GROUP BY bucket, strike, option_type) s"
            + " GROUP BY bucket ORDER BY bucket";
    return jdbc.query(
        sql,
        (rs, n) -> {
          long ceOi = rs.getLong("ce_oi");
          long peOi = rs.getLong("pe_oi");
          BigDecimal pcr =
              ceOi == 0 ? null : BigDecimal.valueOf(peOi).divide(BigDecimal.valueOf(ceOi), 4, RoundingMode.HALF_UP);
          return new TrendPoint(
              rs.getObject("bucket", OffsetDateTime.class),
              ceOi + peOi,
              ceOi,
              peOi,
              pcr,
              rs.getBigDecimal("spot"),
              rs.getBigDecimal("ce_ltp"),
              rs.getBigDecimal("pe_ltp"));
        },
        name,
        java.sql.Date.valueOf(expiry),
        Timestamp.from(from.plusSeconds(1).toInstant()),
        Timestamp.from(to.plusSeconds(1).toInstant()));
  }

  /** Zip the two series by bucket instant (#214: key by instant, not the offset-bearing timestamp). */
  private static List<AlignedPoint> align(ExpirySide a, ExpirySide b) {
    Map<Instant, TrendPoint> byA = new LinkedHashMap<>();
    for (TrendPoint p : a.series()) {
      byA.put(p.bucket().toInstant(), p);
    }
    Map<Instant, TrendPoint> byB = new LinkedHashMap<>();
    for (TrendPoint p : b.series()) {
      byB.put(p.bucket().toInstant(), p);
    }
    java.util.TreeSet<Instant> keys = new java.util.TreeSet<>();
    keys.addAll(byA.keySet());
    keys.addAll(byB.keySet());
    List<AlignedPoint> out = new ArrayList<>();
    for (Instant k : keys) {
      TrendPoint pa = byA.get(k);
      TrendPoint pb = byB.get(k);
      OffsetDateTime bucket = pa != null ? pa.bucket() : pb.bucket();
      out.add(
          new AlignedPoint(
              bucket,
              pa == null ? null : pa.totalOi(),
              pb == null ? null : pb.totalOi(),
              pa == null ? null : pa.pcr(),
              pb == null ? null : pb.pcr(),
              pa == null ? null : pa.spot(),
              pb == null ? null : pb.spot()));
    }
    return out;
  }

  private static OffsetDateTime maxAsOf(OffsetDateTime a, OffsetDateTime b) {
    if (a == null) {
      return b;
    }
    if (b == null) {
      return a;
    }
    return a.toInstant().isAfter(b.toInstant()) ? a : b;
  }

  /** Parse an ISO expiry param (400 on a bad/absent value). Used by the controller. */
  public static LocalDate requireExpiry(String raw, String field) {
    if (raw == null || raw.isBlank()) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, field + " is required");
    }
    try {
      return LocalDate.parse(raw);
    } catch (java.time.format.DateTimeParseException ex) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, field + " must be ISO yyyy-MM-dd");
    }
  }
}
