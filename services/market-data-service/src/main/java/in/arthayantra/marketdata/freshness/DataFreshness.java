package in.arthayantra.marketdata.freshness;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * The standard data-freshness / source-quality envelope (app-platform audit 2026-07-10 §11.17,
 * Phase-3 slice A) nested on the high-value analytics reads so every page header renders one uniform
 * freshness badge instead of a per-page ad-hoc "as of" string.
 *
 * <p>Every field is honestly derived from data the fold ALREADY read (#745 stale-anchor rule) — none
 * is fabricated:
 *
 * <ul>
 *   <li>{@code asOf} — the newest datapoint's timestamp (null when the read is empty);
 *   <li>{@code source} — the concrete producer label ({@code capture} / {@code candle-derived} /
 *       {@code bhavcopy} / {@code nse-eod});
 *   <li>{@code historyStart} — the earliest available session for this series; deliberately null in
 *       this slice (a MIN(ts)/MIN(trade_date) probe is NOT run on the hot read path — a later slice
 *       fills it rather than fabricating a value here);
 *   <li>{@code staleSeconds} — {@code max(0, now − asOf)} seconds (null when {@code asOf} null);
 *   <li>{@code complete} — the response carries a fully-captured latest datapoint;
 *   <li>{@code provenance} — the data KIND: {@code live} | {@code derived} | {@code eod} |
 *       {@code static}, INDEPENDENT of freshness (a past captured session is still {@code live}
 *       provenance, just with a large {@code staleSeconds}).
 * </ul>
 *
 * <p>The whole object is {@code @JsonInclude(NON_NULL)} and nested behind a {@code NON_NULL} field on
 * each response record, so a record built WITHOUT freshness stays byte-identical (parity/golden safe).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DataFreshness(
    OffsetDateTime asOf,
    String source,
    LocalDate historyStart,
    Long staleSeconds,
    boolean complete,
    String provenance) {

  public static final String LIVE = "live";
  public static final String DERIVED = "derived";
  public static final String EOD = "eod";
  public static final String STATIC = "static";

  /** Builds the envelope, computing {@code staleSeconds} from {@code asOf} against the clock. */
  public static DataFreshness of(
      OffsetDateTime asOf,
      String provenance,
      String source,
      LocalDate historyStart,
      boolean complete,
      Clock clock) {
    Long stale =
        asOf == null
            ? null
            : Math.max(0L, Duration.between(asOf.toInstant(), clock.instant()).getSeconds());
    return new DataFreshness(asOf, source, historyStart, stale, complete, provenance);
  }

  /** Live-captured OI/quote data (options/futures snapshots). {@code complete} = data present. */
  public static DataFreshness live(OffsetDateTime asOf, Clock clock) {
    return of(asOf, LIVE, "capture", null, asOf != null, clock);
  }

  /** Read-time candle-derived options history (pre-capture, iv/greeks null). */
  public static DataFreshness derived(OffsetDateTime asOf, Clock clock) {
    return of(asOf, DERIVED, "candle-derived", null, asOf != null, clock);
  }

  /**
   * End-of-day bhavcopy / NSE-EOD folds (breadth / sector / returns / FII). {@code source} names the
   * concrete producer ({@code bhavcopy} or {@code nse-eod}); {@code asOf} is the session-close instant.
   */
  public static DataFreshness eod(OffsetDateTime asOf, String source, Clock clock) {
    return of(asOf, EOD, source, null, asOf != null, clock);
  }
}
