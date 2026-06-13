package in.arthayantra.backtest.replay.options;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.strategyengine.series.EngineSeries;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads an option-premium series from {@code marketdata.options_chain_snapshots} at the archive's
 * NATIVE 5-minute granularity (§D.15) — read-only (D10), never over REST, never interpolated below
 * 5 minutes. The series is the snapshot {@code ltp} in {@code ts} order over {@code [from, to)}.
 *
 * <p>An archive-coverage pre-flight compares the expected 5-min snapshot slots per {@link
 * MarketCalendar} trading sessions against the present count and fails fast with 422 {@code
 * DATA_GAP} when present &lt; expected — the archive only accrues from Stage B onward, so windows
 * predating it legitimately fail the snapshot-grade check (the synthetic mode is the explicit,
 * labelled alternative). A strategy whose primary timeframe is finer than 5m cannot be served
 * snapshot-grade premiums, and {@link #refuseSubFiveMinute(String)} says so up front instead of
 * fabricating sub-snapshot prices.
 */
@Component
public class SnapshotPremiumReader {

  /** The archive's native snapshot cadence — premiums are NEVER interpolated below this. */
  public static final Duration NATIVE_GRANULARITY = Duration.ofMinutes(5);

  private static final long SLOT_SECONDS = NATIVE_GRANULARITY.getSeconds();

  private final JdbcTemplate jdbc;
  private final MarketCalendar calendar = MarketCalendar.nse();

  /** Wires JDBC (connected as the owner; the marketdata read is via the CD-1 grant model). */
  public SnapshotPremiumReader(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** One snapshot premium observation at the archive's native 5-min granularity. */
  public record PremiumPoint(OffsetDateTime ts, BigDecimal premium) {}

  /**
   * Refuses snapshot-grade replay for a primary timeframe finer than 5 minutes — the §D.15 contract
   * never fabricates sub-snapshot prices. {@code "1m"} (and any timeframe below the native 5-min
   * cadence) throws 400 {@code VALIDATION_INTERVAL_UNSUPPORTED}; coarser timeframes are accepted.
   *
   * @param primaryTimeframe the strategy's declared primary timeframe (e.g. {@code "1m"},
   *     {@code "5m"}, {@code "15m"})
   */
  public void refuseSubFiveMinute(String primaryTimeframe) {
    long minutes = timeframeMinutes(primaryTimeframe);
    if (minutes > 0 && minutes < NATIVE_GRANULARITY.toMinutes()) {
      throw new ApiException(
          400,
          ErrorCodes.VALIDATION_INTERVAL_UNSUPPORTED,
          "options snapshot replay is served only at the archive's native 5-minute granularity; "
              + "a strategy on a "
              + primaryTimeframe
              + " primary timeframe cannot be served snapshot-grade premiums "
              + "(use synthetic premium mode for finer reconstruction)",
          Map.of(
              "primaryTimeframe", primaryTimeframe,
              "snapshotGranularity", "5m"));
    }
  }

  /**
   * Reads the premium series for one contract bucket over {@code [from, to)} in {@code ts} order,
   * after the archive-coverage pre-flight. Throws 422 {@code DATA_GAP} listing the gap when fewer
   * snapshot slots are present than the calendar expects.
   *
   * @param underlying the underlying symbol (e.g. {@code "NIFTY"})
   * @param expiry the contract expiry date
   * @param strike the strike price
   * @param optionType {@code "CE"} or {@code "PE"}
   * @param from window start (inclusive)
   * @param to window end (exclusive)
   * @return the ordered premium series at native 5-min granularity
   */
  public List<PremiumPoint> read(
      String underlying,
      LocalDate expiry,
      BigDecimal strike,
      String optionType,
      OffsetDateTime from,
      OffsetDateTime to) {
    checkArchiveCoverage(underlying, expiry, strike, optionType, from, to);
    return jdbc.query(
        "SELECT ts, ltp FROM marketdata.options_chain_snapshots "
            + "WHERE underlying=? AND expiry=? AND strike=? AND option_type=? "
            + "AND ts >= ? AND ts < ? ORDER BY ts",
        (rs, n) ->
            new PremiumPoint(
                rs.getTimestamp("ts").toInstant().atOffset(EngineSeries.IST),
                rs.getBigDecimal("ltp")),
        underlying,
        expiry,
        strike,
        optionType,
        from,
        to);
  }

  /**
   * The archive-coverage pre-flight: throws 422 {@code DATA_GAP} when present snapshot slots are
   * fewer than the {@link MarketCalendar} expects over {@code [from, to)}, listing the gap.
   */
  public void checkArchiveCoverage(
      String underlying,
      LocalDate expiry,
      BigDecimal strike,
      String optionType,
      OffsetDateTime from,
      OffsetDateTime to) {
    long expected = expectedSnapshotSlots(from.toInstant(), to.toInstant());
    long present =
        present(underlying, expiry, strike, optionType, from, to);
    if (present < expected) {
      throw new ApiException(
          422,
          ErrorCodes.DATA_GAP,
          "insufficient 5-min options-snapshot coverage for "
              + underlying
              + " "
              + expiry
              + " "
              + strike.toPlainString()
              + optionType,
          Map.of(
              "underlying", underlying,
              "expiry", expiry.toString(),
              "strike", strike.toPlainString(),
              "optionType", optionType,
              "from", from.toString(),
              "to", to.toString(),
              "expectedSlots", expected,
              "presentSlots", present,
              "missingSlots", expected - present));
    }
  }

  private long present(
      String underlying,
      LocalDate expiry,
      BigDecimal strike,
      String optionType,
      OffsetDateTime from,
      OffsetDateTime to) {
    // Count only SLOT-ALIGNED snapshot rows so an off-cadence row (e.g. a 09:17 write) cannot
    // inflate coverage and mask a genuinely missing 5-min slot. The archive's native cadence is
    // anchored to the IST session open (09:15), and every aligned 5-min IST instant has an epoch
    // divisible by 300; DISTINCT collapses any duplicate writes within one slot.
    Long count =
        jdbc.queryForObject(
            "SELECT count(DISTINCT ts) FROM marketdata.options_chain_snapshots "
                + "WHERE underlying=? AND expiry=? AND strike=? AND option_type=? "
                + "AND ts >= ? AND ts < ? "
                + "AND (extract(epoch FROM ts)::bigint % ?) = 0",
            Long.class,
            underlying,
            expiry,
            strike,
            optionType,
            from,
            to,
            SLOT_SECONDS);
    return count == null ? 0L : count;
  }

  /**
   * Expected 5-minute snapshot slots whose start lies in {@code [from, to)} and inside a regular
   * NSE session — the archive-coverage denominator (a full session has 75 five-minute slot-starts in
   * {@code [09:15, 15:30)}: 09:15, 09:20, ... 15:25, with 15:30 the close and excluded by the strict
   * hi-bound). Mirrors {@link MarketCalendar#expectedMinuteBuckets} at 5-min granularity.
   */
  long expectedSnapshotSlots(Instant from, Instant to) {
    if (!to.isAfter(from)) {
      return 0;
    }
    long total = 0;
    LocalDate day = from.atZone(MarketCalendar.IST).toLocalDate();
    LocalDate lastDay = to.atZone(MarketCalendar.IST).toLocalDate();
    while (!day.isAfter(lastDay)) {
      Optional<MarketCalendar.SessionBounds> bounds = calendar.sessionBounds(day);
      if (bounds.isPresent()) {
        Instant open = bounds.get().open();
        Instant close = bounds.get().close();
        Instant lo = open.isAfter(from) ? open : from;
        Instant hi = close.isBefore(to) ? close : to;
        if (hi.isAfter(lo)) {
          total += slotsInWindow(open, lo, hi);
        }
      }
      day = day.plusDays(1);
    }
    return total;
  }

  /** Counts 5-min slot-starts (aligned to the session open) in {@code [lo, hi)}. */
  private long slotsInWindow(Instant sessionOpen, Instant lo, Instant hi) {
    long fromOpen = Duration.between(sessionOpen, lo).getSeconds();
    long offset = ((fromOpen % SLOT_SECONDS) + SLOT_SECONDS) % SLOT_SECONDS;
    Instant firstSlot = offset == 0 ? lo : lo.plusSeconds(SLOT_SECONDS - offset);
    if (!hi.isAfter(firstSlot)) {
      return 0;
    }
    long span = Duration.between(firstSlot, hi).getSeconds();
    return (span + SLOT_SECONDS - 1) / SLOT_SECONDS;
  }

  private static long timeframeMinutes(String timeframe) {
    if (timeframe == null || timeframe.isBlank()) {
      return 0;
    }
    String tf = timeframe.trim().toLowerCase();
    try {
      if (tf.endsWith("m")) {
        return Long.parseLong(tf.substring(0, tf.length() - 1));
      }
      if (tf.endsWith("h")) {
        return Long.parseLong(tf.substring(0, tf.length() - 1)) * 60;
      }
      if (tf.endsWith("d")) {
        return Long.parseLong(tf.substring(0, tf.length() - 1)) * 60 * 24;
      }
      if (tf.endsWith("w")) {
        return Long.parseLong(tf.substring(0, tf.length() - 1)) * 60 * 24 * 7;
      }
    } catch (NumberFormatException ignored) {
      return 0;
    }
    return 0;
  }
}
