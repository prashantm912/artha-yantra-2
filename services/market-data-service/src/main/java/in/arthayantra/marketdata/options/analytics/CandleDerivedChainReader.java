package in.arthayantra.marketdata.options.analytics;

import in.arthayantra.black76.Black76;
import in.arthayantra.black76.IvSolver;
import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.options.ExpiryClock;
import in.arthayantra.marketdata.options.OiInterval;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.OptionalDouble;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Derives a per-strike option-chain OI series ({@link OptionsSnapshotReader.StrikePoint}) from the
 * per-CONTRACT {@code candles} bars (the Upstox expired-instruments 1-yr backfill) for sessions that
 * have NO captured {@code options_chain_snapshots} — so OI-using backtests + the OI pages stop
 * returning {@code NO_DATA} on history. See
 * {@code docs/superpowers/plans/2026-06-25-historical-oi-virtual-readtime.md}.
 *
 * <p>This is a READ-TIME derivation: it writes nothing, so it sidesteps the compressed-chunk
 * write-amplification that twice OOM-crashed the live DB. The expensive asset (per-contract {@code oi}
 * in 72.7M candle rows) is already on disk; here we bucket+pivot it into the same shape the snapshot
 * consumers read.
 *
 * <p>Fidelity vs live snapshots:
 * <ul>
 *   <li><b>oi / ltp / volume</b> — faithful: {@code last()} over the interval bucket, identical
 *       {@code time_bucket(...,'Asia/Kolkata')} semantics as {@link OptionsSnapshotReader#series}.
 *   <li><b>oi_change</b> — a documented APPROXIMATION: live captures the venue's per-snapshot OI
 *       delta; here it is the bucket-over-bucket diff within each (strike, leg), NULL on the first
 *       bucket of the window (matching live's first-pass-of-day null). Computed over the BUCKETED
 *       series (not raw 1-min candles) so the sentiment-% magnitude is not 5× compressed.
 *   <li><b>iv / greeks / bid / ask / spot</b> — NULL: candles carry no solver inputs. The ATM-IV
 *       confluence factor therefore degrades to NEUTRAL on derived history (1 of 11 factors), which
 *       {@code ConnectingDotsService.ivFactor} handles gracefully. The OI sentiment factor — the one
 *       this exists for — needs only oi + oi_change and is faithful.
 * </ul>
 *
 * <p>Coverage gate: only contracts marked {@code complete} in {@code expired_contracts} contribute,
 * so a half-backfilled strike never silently thins the chain.
 */
@Repository
public class CandleDerivedChainReader {

  private final JdbcTemplate jdbc;
  private final MarketCalendar calendar;
  private final double riskFreeRate;
  // Read-time IV-solve band: the number of strikes EITHER SIDE of the forward to back-solve from the
  // candle premium. Default 3 (the ATM band — cheap, byte-identical to the prior behaviour). Set <= 0
  // for the FULL CHAIN — every strike with a valid premium gets a per-strike IV (the historical OI
  // pages then show IV across the whole ladder, not just near the money). Full-chain is a heavier
  // read (one Newton-Raphson solve per strike per bucket), so it stays opt-in via config.
  private final int ivBand;

  public CandleDerivedChainReader(
      JdbcTemplate jdbc,
      MarketCalendar calendar,
      @org.springframework.beans.factory.annotation.Value("${artha.options.risk-free-rate:0.065}")
          BigDecimal riskFreeRate,
      @org.springframework.beans.factory.annotation.Value("${artha.options.derived-iv-band:3}")
          int ivBand) {
    this.jdbc = jdbc;
    this.calendar = calendar;
    this.riskFreeRate = riskFreeRate.doubleValue();
    this.ivBand = ivBand;
  }

  /**
   * Maps a ConnectingDots index display name (e.g. {@code NIFTY 50}) to the {@code
   * expired_contracts.underlying_symbol} the backfill keys by (e.g. {@code NIFTY}); null when no
   * expired-contract roster exists for the index (today only NIFTY + SENSEX are backfilled).
   */
  public static String expiredUnderlying(String indexDisplayName) {
    if (indexDisplayName == null) {
      return null;
    }
    return switch (indexDisplayName.toUpperCase(Locale.ROOT)) {
      case "NIFTY 50", "NIFTY" -> "NIFTY";
      case "SENSEX" -> "SENSEX";
      case "NIFTY BANK", "BANKNIFTY" -> "BANKNIFTY";
      case "NIFTY FIN SERVICE", "FINNIFTY" -> "FINNIFTY";
      case "NIFTY MID SELECT", "MIDCPNIFTY" -> "MIDCPNIFTY";
      case "BANKEX" -> "BANKEX";
      default -> null;
    };
  }

  /**
   * The front (nearest on-or-after {@code session}) expiry that has COMPLETE expired-contract
   * coverage for {@code expiredUnderlyingSymbol}; null when none — i.e. the weekly that was being
   * traded that session.
   */
  public LocalDate frontExpiry(String expiredUnderlyingSymbol, LocalDate session) {
    // MIN() always returns one row (a null value when nothing matches) — queryForObject hands that
    // null straight back, where stream().findFirst() on a [null] list would NPE.
    return jdbc.queryForObject(
        "SELECT min(expiry) AS e FROM expired_contracts "
            + "WHERE underlying_symbol = ? AND expiry >= ? AND complete = true "
            + "AND instrument_type IN ('CE','PE')",
        (rs, n) -> rs.getObject("e", LocalDate.class),
        expiredUnderlyingSymbol,
        java.sql.Date.valueOf(session));
  }

  /** The exchange + tradingsymbol of an expired front future, for reading its stored candles. */
  public record FrontFuture(String exchange, String tradingsymbol) {}

  /**
   * The front (nearest on-or-after {@code session}) EXPIRED future for {@code expiredUnderlyingSymbol}
   * with complete coverage; null when none — the historical futures spine for a past ConnectingDots
   * session (the live instruments table has no expired futures).
   */
  public FrontFuture frontFuture(String expiredUnderlyingSymbol, LocalDate session) {
    return jdbc
        .query(
            "SELECT exchange, tradingsymbol FROM expired_contracts "
                + "WHERE underlying_symbol = ? AND instrument_type = 'FUT' AND expiry >= ? "
                + "AND complete = true ORDER BY expiry LIMIT 1",
            (rs, n) -> new FrontFuture(rs.getString("exchange"), rs.getString("tradingsymbol")),
            expiredUnderlyingSymbol,
            java.sql.Date.valueOf(session))
        .stream()
        .findFirst()
        .orElse(null);
  }

  /**
   * The candle-derived chain series for ({@code expiredUnderlyingSymbol}, {@code expiry}) over
   * [{@code from}, {@code to}), bucketed to {@code interval}. Oldest-bucket-first, matching {@link
   * OptionsSnapshotReader#series}'s ordering so the bucket-grouping consumers fold it identically.
   */
  public List<OptionsSnapshotReader.StrikePoint> series(
      String expiredUnderlyingSymbol,
      LocalDate expiry,
      OiInterval interval,
      OffsetDateTime from,
      OffsetDateTime to) {
    String sql =
        "SELECT public.time_bucket(INTERVAL '"
            + interval.pgInterval()
            + "', c.bucket, 'Asia/Kolkata') AS b, "
            + "  ec.strike AS strike, ec.instrument_type AS option_type, "
            + "  public.last(c.close, c.bucket) AS ltp, public.last(c.oi, c.bucket) AS oi, "
            + "  public.last(c.volume, c.bucket) AS volume "
            + "FROM candles c "
            + "JOIN expired_contracts ec "
            + "  ON ec.exchange = c.exchange AND ec.tradingsymbol = c.tradingsymbol "
            + "WHERE ec.underlying_symbol = ? AND ec.expiry = ? AND ec.complete = true "
            + "  AND ec.instrument_type IN ('CE','PE') "
            + "  AND c.interval = '1m' AND c.bucket >= ? AND c.bucket < ? "
            + "GROUP BY b, ec.strike, ec.instrument_type "
            // grouped by (strike, leg) then bucket so the oi_change lag is contiguous per contract
            + "ORDER BY ec.strike, ec.instrument_type, b";
    List<Raw> raw =
        jdbc.query(
            sql,
            (rs, n) ->
                new Raw(
                    rs.getObject("b", OffsetDateTime.class),
                    rs.getBigDecimal("strike"),
                    rs.getString("option_type"),
                    rs.getBigDecimal("ltp"),
                    rs.getObject("oi", Long.class),
                    rs.getObject("volume", Long.class)),
            expiredUnderlyingSymbol,
            java.sql.Date.valueOf(expiry),
            Timestamp.from(from.toInstant()),
            Timestamp.from(to.toInstant()));

    // spot proxy per bucket = the front EXPIRED future's bucketed close (≈ forward) — lets the
    // ATM-band pages (heatmap/expiry/open-high) centre on the right strikes instead of the lowest.
    // Empty map when no future candle exists → spot stays null (the prior behaviour).
    Map<OffsetDateTime, BigDecimal> spotByBucket = futureSpotByBucket(expiredUnderlyingSymbol, interval, from, to);

    // oi_change = bucket-over-bucket diff WITHIN each (strike, leg), null on that contract's first
    // bucket in the window (the rows are already ordered strike→leg→bucket).
    List<OptionsSnapshotReader.StrikePoint> points = new ArrayList<>(raw.size());
    String prevKey = null;
    Long prevOi = null;
    for (Raw r : raw) {
      String key = r.strike + "|" + r.optionType;
      Long oiChange = null;
      if (key.equals(prevKey) && prevOi != null && r.oi != null) {
        oiChange = r.oi - prevOi;
      }
      points.add(
          new OptionsSnapshotReader.StrikePoint(
              r.bucket, r.strike, r.optionType, r.ltp, r.oi, oiChange, null,
              spotByBucket.get(r.bucket), r.volume));
      prevKey = key;
      prevOi = r.oi;
    }
    // re-sort to (bucket, strike, leg) to match series()'s contract for the bucket-grouping folders
    points.sort(
        Comparator.comparing(OptionsSnapshotReader.StrikePoint::bucket)
            .thenComparing(OptionsSnapshotReader.StrikePoint::strike)
            .thenComparing(OptionsSnapshotReader.StrikePoint::optionType));
    return enrichIv(points, expiry);
  }

  /**
   * Solves Black-76 IV per bucket from the candle premium — the future-close {@code spot} proxy IS the
   * forward, so the only missing solver input is now available. By default only the ATM band ({@link
   * #ivBand} strikes either side, the cheap near-the-money set) is solved; with {@code ivBand <= 0} the
   * FULL chain is solved so every strike with a valid premium carries a per-strike IV. Any below-
   * intrinsic / zero / non-converging premium (and, in band mode, any out-of-band strike) stays null.
   */
  private List<OptionsSnapshotReader.StrikePoint> enrichIv(
      List<OptionsSnapshotReader.StrikePoint> points, LocalDate expiry) {
    Map<OffsetDateTime, List<OptionsSnapshotReader.StrikePoint>> byBucket = new LinkedHashMap<>();
    for (OptionsSnapshotReader.StrikePoint p : points) {
      byBucket.computeIfAbsent(p.bucket(), k -> new ArrayList<>()).add(p);
    }
    Map<String, BigDecimal> ivOverride = new HashMap<>();
    byBucket.forEach(
        (bucket, pts) -> {
          BigDecimal forward =
              pts.stream()
                  .map(OptionsSnapshotReader.StrikePoint::spot)
                  .filter(Objects::nonNull)
                  .findFirst()
                  .orElse(null);
          if (forward == null) {
            return;
          }
          OptionalDouble years = ExpiryClock.yearsToExpiry(bucket.toInstant(), expiry);
          if (years.isEmpty()) {
            return;
          }
          double t = years.getAsDouble();
          // ivBand <= 0 ⇒ FULL chain (band == null, no strike filter); else the nearest 2*ivBand+1.
          Set<BigDecimal> band =
              ivBand <= 0
                  ? null
                  : new HashSet<>(
                      pts.stream()
                          .map(OptionsSnapshotReader.StrikePoint::strike)
                          .distinct()
                          .sorted(Comparator.comparing(s -> s.subtract(forward).abs()))
                          .limit(ivBand * 2L + 1)
                          .toList());
          for (OptionsSnapshotReader.StrikePoint p : pts) {
            if ((band != null && !band.contains(p.strike())) || p.ltp() == null || p.ltp().signum() <= 0) {
              continue;
            }
            Black76.OptionType type =
                "CE".equals(p.optionType()) ? Black76.OptionType.CE : Black76.OptionType.PE;
            IvSolver.IvResult r =
                IvSolver.solve(
                    type, forward.doubleValue(), p.strike().doubleValue(), t, riskFreeRate,
                    p.ltp().doubleValue());
            if (r.iv() != null) {
              ivOverride.put(ivKey(p), r.iv());
            }
          }
        });
    if (ivOverride.isEmpty()) {
      return points;
    }
    List<OptionsSnapshotReader.StrikePoint> out = new ArrayList<>(points.size());
    for (OptionsSnapshotReader.StrikePoint p : points) {
      BigDecimal iv = ivOverride.get(ivKey(p));
      out.add(
          iv == null
              ? p
              : new OptionsSnapshotReader.StrikePoint(
                  p.bucket(), p.strike(), p.optionType(), p.ltp(), p.oi(), p.oiChange(), iv,
                  p.spot(), p.volume()));
    }
    return out;
  }

  private static String ivKey(OptionsSnapshotReader.StrikePoint p) {
    return p.bucket() + "|" + p.strike().stripTrailingZeros().toPlainString() + "|" + p.optionType();
  }

  /** The IST-day window's bucketed chain — the basis the {@code latest}/{@code latestPair} twins slice. */
  private List<OptionsSnapshotReader.StrikePoint> daySeries(
      String eu, LocalDate expiry, OiInterval interval, LocalDate date) {
    OffsetDateTime start = date.atStartOfDay().atOffset(Ist.OFFSET);
    return series(eu, expiry, interval, start, start.plusDays(1));
  }

  /** The newest bucket of {@code date}'s chain (the candle-derived twin of {@code latest(date)}). */
  public List<OptionsSnapshotReader.StrikePoint> latest(
      String eu, LocalDate expiry, OiInterval interval, LocalDate date) {
    List<OptionsSnapshotReader.StrikePoint> day = daySeries(eu, expiry, interval, date);
    if (day.isEmpty()) {
      return List.of();
    }
    OffsetDateTime newest =
        day.stream()
            .map(OptionsSnapshotReader.StrikePoint::bucket)
            .max(Comparator.naturalOrder())
            .orElseThrow();
    return day.stream().filter(p -> p.bucket().equals(newest)).toList();
  }

  /** The two most-recent buckets of {@code date}'s chain (twin of {@code latestPair(date)}, for deltas). */
  public List<OptionsSnapshotReader.StrikePoint> latestPair(
      String eu, LocalDate expiry, OiInterval interval, LocalDate date) {
    List<OptionsSnapshotReader.StrikePoint> day = daySeries(eu, expiry, interval, date);
    if (day.isEmpty()) {
      return List.of();
    }
    List<OffsetDateTime> twoNewest =
        day.stream()
            .map(OptionsSnapshotReader.StrikePoint::bucket)
            .distinct()
            .sorted(Comparator.reverseOrder())
            .limit(2)
            .toList();
    return day.stream().filter(p -> twoNewest.contains(p.bucket())).toList();
  }

  /** One strike's intraday series (twin of {@code strikeSeries}) — filter the chain series in memory. */
  public List<OptionsSnapshotReader.StrikePoint> strikeSeries(
      String eu,
      LocalDate expiry,
      BigDecimal strike,
      OiInterval interval,
      OffsetDateTime from,
      OffsetDateTime to) {
    return series(eu, expiry, interval, from, to).stream()
        .filter(p -> p.strike().compareTo(strike) == 0)
        .toList();
  }

  /** Per-(strike, leg) per-IST-day premium OHLC + oi/volume close (twin of {@code eodSeries}). */
  public List<OptionsSnapshotReader.OptionEodRow> eodSeries(
      String eu, LocalDate expiry, OffsetDateTime from, OffsetDateTime to) {
    String sql =
        "SELECT (c.bucket AT TIME ZONE 'Asia/Kolkata')::date AS d, ec.strike AS strike, "
            + "  ec.instrument_type AS option_type, public.first(c.close, c.bucket) AS o, "
            + "  max(c.close) AS h, min(c.close) AS l, public.last(c.close, c.bucket) AS c, "
            + "  public.last(c.oi, c.bucket) AS oi_close, public.last(c.volume, c.bucket) AS vol "
            + "FROM candles c "
            + "JOIN expired_contracts ec "
            + "  ON ec.exchange = c.exchange AND ec.tradingsymbol = c.tradingsymbol "
            + "WHERE ec.underlying_symbol = ? AND ec.expiry = ? AND ec.complete = true "
            + "  AND ec.instrument_type IN ('CE','PE') "
            + "  AND c.interval = '1m' AND c.bucket >= ? AND c.bucket < ? "
            + "GROUP BY d, ec.strike, ec.instrument_type "
            + "ORDER BY ec.strike, ec.instrument_type, d";
    return jdbc.query(
        sql,
        (rs, n) ->
            new OptionsSnapshotReader.OptionEodRow(
                rs.getBigDecimal("strike"),
                rs.getString("option_type"),
                rs.getObject("d", LocalDate.class),
                rs.getBigDecimal("o"),
                rs.getBigDecimal("h"),
                rs.getBigDecimal("l"),
                rs.getBigDecimal("c"),
                rs.getObject("oi_close", Long.class),
                rs.getObject("vol", Long.class)),
        eu,
        java.sql.Date.valueOf(expiry),
        Timestamp.from(from.toInstant()),
        Timestamp.from(to.toInstant()));
  }

  /** The front EXPIRED future's bucketed close per interval bucket — the derived spot proxy. */
  private Map<OffsetDateTime, BigDecimal> futureSpotByBucket(
      String eu, OiInterval interval, OffsetDateTime from, OffsetDateTime to) {
    FrontFuture ff = frontFuture(eu, LocalDate.ofInstant(from.toInstant(), Ist.ZONE));
    if (ff == null) {
      return Map.of();
    }
    String sql =
        "SELECT public.time_bucket(INTERVAL '"
            + interval.pgInterval()
            + "', c.bucket, 'Asia/Kolkata') AS b, public.last(c.close, c.bucket) AS spot "
            + "FROM candles c WHERE c.exchange = ? AND c.tradingsymbol = ? "
            + "AND c.interval = '1m' AND c.bucket >= ? AND c.bucket < ? GROUP BY b";
    Map<OffsetDateTime, BigDecimal> m = new HashMap<>();
    jdbc.query(
        sql,
        (org.springframework.jdbc.core.RowCallbackHandler)
            rs -> m.put(rs.getObject("b", OffsetDateTime.class), rs.getBigDecimal("spot")),
        ff.exchange(),
        ff.tradingsymbol(),
        Timestamp.from(from.toInstant()),
        Timestamp.from(to.toInstant()));
    return m;
  }

  /**
   * Per-(strike, leg) session OHLC of the option premium (twin of {@code sessionStats}) — derived from
   * the session's bucketed premium ({@code ltp}); {@code prevClose} = the prior trading day's newest
   * bucket ltp (calendar-resolved; an uncovered-year throw degrades it to null, like the snapshot path).
   */
  public List<OptionsSnapshotReader.PerStrikeSessionStat> sessionStats(
      String eu, LocalDate expiry, LocalDate session, int intervalMinutes) {
    OiInterval interval = OiInterval.parse(intervalMinutes + "m");
    List<OptionsSnapshotReader.StrikePoint> cur = daySeries(eu, expiry, interval, session);
    if (cur.isEmpty()) {
      return List.of();
    }
    Map<String, BigDecimal> prevClose = prevCloseByStrike(eu, expiry, session, interval);
    Map<String, List<OptionsSnapshotReader.StrikePoint>> byStrike = new LinkedHashMap<>();
    for (OptionsSnapshotReader.StrikePoint p : cur) {
      byStrike.computeIfAbsent(sKey(p.strike(), p.optionType()), k -> new ArrayList<>()).add(p);
    }
    List<OptionsSnapshotReader.PerStrikeSessionStat> out = new ArrayList<>();
    for (List<OptionsSnapshotReader.StrikePoint> pts : byStrike.values()) {
      OptionsSnapshotReader.StrikePoint first = pts.get(0);
      OptionsSnapshotReader.StrikePoint lastPt = pts.get(pts.size() - 1);
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
          new OptionsSnapshotReader.PerStrikeSessionStat(
              first.strike(), first.optionType(), first.ltp(), high, low, lastPt.ltp(), dayVolume,
              declineVolume, prevClose.get(sKey(first.strike(), first.optionType())),
              // Derived-history path: OI is virtual/muted (CLAUDE.md), so the per-strike ΔOI veto is a
              // FORWARD-paper feature — leave oiChangePct null here (the veto degrades to safe).
              null));
    }
    return out;
  }

  /** Newest-bucket ltp per (strike, leg) of the prior trading session — empty when none/uncovered. */
  private Map<String, BigDecimal> prevCloseByStrike(
      String eu, LocalDate expiry, LocalDate session, OiInterval interval) {
    LocalDate prior;
    try {
      prior = calendar.previousTradingDay(session);
    } catch (IllegalArgumentException uncoveredYear) {
      return Map.of();
    }
    Map<String, BigDecimal> close = new LinkedHashMap<>();
    for (OptionsSnapshotReader.StrikePoint p : daySeries(eu, expiry, interval, prior)) {
      close.put(sKey(p.strike(), p.optionType()), p.ltp()); // oldest-first → last write = newest bucket
    }
    return close;
  }

  private static String sKey(BigDecimal strike, String optionType) {
    return strike.stripTrailingZeros().toPlainString() + "|" + optionType;
  }

  private static Long intervalVolume(Long a, Long b) {
    return (a == null || b == null) ? null : b - a;
  }

  private static Long add(Long acc, Long v) {
    if (v == null) {
      return acc;
    }
    return acc == null ? v : acc + v;
  }

  private record Raw(
      OffsetDateTime bucket,
      BigDecimal strike,
      String optionType,
      BigDecimal ltp,
      Long oi,
      Long volume) {}
}
