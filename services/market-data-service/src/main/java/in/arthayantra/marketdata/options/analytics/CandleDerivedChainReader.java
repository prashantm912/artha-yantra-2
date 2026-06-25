package in.arthayantra.marketdata.options.analytics;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketdata.options.OiInterval;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

  public CandleDerivedChainReader(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
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
              r.bucket, r.strike, r.optionType, r.ltp, r.oi, oiChange, null, null, r.volume));
      prevKey = key;
      prevOi = r.oi;
    }
    // re-sort to (bucket, strike, leg) to match series()'s contract for the bucket-grouping folders
    points.sort(
        Comparator.comparing(OptionsSnapshotReader.StrikePoint::bucket)
            .thenComparing(OptionsSnapshotReader.StrikePoint::strike)
            .thenComparing(OptionsSnapshotReader.StrikePoint::optionType));
    return points;
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

  private record Raw(
      OffsetDateTime bucket,
      BigDecimal strike,
      String optionType,
      BigDecimal ltp,
      Long oi,
      Long volume) {}
}
