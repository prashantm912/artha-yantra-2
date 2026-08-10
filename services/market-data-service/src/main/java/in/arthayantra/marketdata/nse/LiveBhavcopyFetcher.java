package in.arthayantra.marketdata.nse;

import in.arthayantra.marketcalendar.MarketCalendar;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Parses the NSE {@code sec_bhavdata_full_DDMMYYYY.csv} archive (B-1b) — security-wise EOD OHLCV +
 * delivery. The file is URL-date-stamped, so {@code fetchLatest} walks back from today (IST) to the
 * most recent published file (weekends/holidays 404 and are skipped). Cells carry a leading space;
 * non-deliverable rows carry {@code -} for the delivery columns.
 */
@Component
@Profile("live")
public class LiveBhavcopyFetcher implements BhavcopyFetcher {

  private static final Logger log = LoggerFactory.getLogger(LiveBhavcopyFetcher.class);
  private static final int MAX_LOOKBACK_DAYS = 5;
  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private static final DateTimeFormatter URL_DATE = DateTimeFormatter.ofPattern("ddMMyyyy");
  private static final DateTimeFormatter ROW_DATE =
      DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);

  private final NseHttpClient client;
  private final String archivesUrl;
  private final Clock clock;

  /**
   * Counts payloads refused by the mis-dated guard below, tagged by exchange. A WARN alone was not
   * enough: the refusal path is SILENT to every downstream health surface, because {@code
   * BhavcopyBackfillService} still records a SUCCESS ingest run (with the real, zero row count) and
   * {@code IngestCoverageCanary} judges bhavcopy on "≥1 SUCCESS run". So a SYSTEMATIC false positive
   * — NSE changing {@code DATE1} semantics, say — would discard every payload forever, stall {@code
   * nse_eod_bhavcopy}, and still report green. The counter is the diagnosis (which exchange, how
   * many); the per-exchange floor in that canary is the alarm.
   *
   * <p>⚠️ <b>Counted only for dates the exchange actually TRADED</b>, and that gate is the whole
   * reason this counter is usable. Serving the previous day's file under a holiday URL is NORMAL
   * NSE behaviour — it is the very case the guard was written for. The catch-up loop skips only
   * Saturdays, Sundays and dates already stored ({@code BhavcopyBackfillService.runNse}), so every
   * exchange holiday inside the catch-up window is re-probed on EVERY run, forever, and refused
   * every time. Without the calendar gate the counter would climb monotonically on a perfectly
   * healthy feed — roughly one increment per holiday per run — and no threshold could separate that
   * baseline from the systematic break it exists to reveal. With it, zero on a healthy feed is true
   * by construction rather than by wishful javadoc.
   */
  private final Counter misdatedCounter;

  /** NSE trading calendar; only its {@code isTradingDay} is used, to gate the counter above. */
  private final MarketCalendar calendar;

  public LiveBhavcopyFetcher(
      NseHttpClient client,
      @Value("${artha.nse.archives-url:https://nsearchives.nseindia.com}") String archivesUrl,
      Clock clock,
      MeterRegistry meterRegistry,
      MarketCalendar calendar) {
    this.client = client;
    this.archivesUrl = archivesUrl;
    this.clock = clock;
    this.calendar = calendar;
    this.misdatedCounter =
        meterRegistry.counter("ay_bhavcopy_misdated_payload_total", "exchange", "NSE");
  }

  /**
   * True when {@code date} is a real trading session. Fail-CLOSED for the counter: past the bundled
   * calendar's covered years {@code isTradingDay} throws (the CD-2 cliff, deliberately loud), and a
   * metric must never break a fetch — so a cliff means "do not count", never "do not fetch".
   */
  private boolean countableTradingDay(LocalDate date) {
    try {
      return calendar.isTradingDay(date);
    } catch (RuntimeException calendarCliff) {
      log.debug("NSE bhavcopy {}: calendar does not cover this year — not counting", date);
      return false;
    }
  }

  @Override
  public List<BhavcopyRow> fetchLatest() {
    LocalDate today = OffsetDateTime.now(clock).atZoneSameInstant(IST).toLocalDate();
    for (int back = 0; back <= MAX_LOOKBACK_DAYS; back++) {
      List<BhavcopyRow> rows = fetchForDate(today.minusDays(back));
      if (!rows.isEmpty()) {
        return rows;
      }
    }
    throw new IllegalStateException(
        "No NSE bhavcopy file in the last " + MAX_LOOKBACK_DAYS + " days");
  }

  @Override
  public List<BhavcopyRow> fetchForDate(LocalDate date) {
    String url =
        archivesUrl + "/products/content/sec_bhavdata_full_" + URL_DATE.format(date) + ".csv";
    try {
      String csv = client.getAbsolute(url);
      // A real file carries the DELIV_PER header; anything else (an HTML error page, an empty body)
      // is treated as "not published" so the catch-up just skips that day.
      if (csv == null || !csv.contains("DELIV_PER")) {
        return List.of();
      }
      List<BhavcopyRow> rows = parse(csv);
      // The archive answers 200 with the PREVIOUS trading day's file under many holiday URLs, and
      // the trade date comes from the CSV's own DATE1 column — so an unchecked payload stores H-1's
      // rows, leaves the requested day H permanently missing, and makes the anti-join re-probe (and
      // re-stamp fetched_at on) H-1 every run forever. A mis-dated payload is "not published".
      // allMatch, not a first-row check, and deliberately REJECT rather than filter: filtering to
      // the correctly-dated rows would store a PARTIAL day, and the anti-join treats "any row for
      // this date" as present — so a partial write is never re-probed and the hole becomes silent
      // and permanent. Refusing is loud (WARN + re-probe next run). Less destructive is not safer.
      if (!rows.isEmpty() && !rows.stream().allMatch(r -> date.equals(r.date()))) {
        if (countableTradingDay(date)) {
          misdatedCounter.increment();
        }
        log.warn(
            "NSE bhavcopy {}: archive served {} row(s) dated {} — discarding as not published",
            date,
            rows.size(),
            rows.stream().map(BhavcopyRow::date).distinct().sorted().toList());
        return List.of();
      }
      return rows;
    } catch (RuntimeException miss) {
      // 404 on a weekend/holiday (or a not-yet-posted file) is the expected non-trading-day signal.
      return List.of();
    }
  }

  private static List<BhavcopyRow> parse(String csv) {
    String[] lines = csv.split("\\r?\\n");
    List<BhavcopyRow> rows = new ArrayList<>();
    // line 0 = header; line 1.. = one row per symbol+series
    for (int i = 1; i < lines.length; i++) {
      String line = lines[i].trim();
      if (line.isEmpty()) {
        continue;
      }
      String[] c = line.split(",");
      if (c.length < 15) {
        continue;
      }
      rows.add(
          new BhavcopyRow(
              LocalDate.parse(c[2].trim(), ROW_DATE),
              c[0].trim(),
              c[1].trim(),
              num(c[3]),
              num(c[4]),
              num(c[5]),
              num(c[6]),
              num(c[7]),
              num(c[8]),
              num(c[9]),
              lng(c[10]),
              num(c[11]),
              lng(c[12]),
              lng(c[13]),
              num(c[14])));
    }
    return rows;
  }

  /** Numeric cell → null when blank or the {@code -} non-deliverable marker. */
  private static BigDecimal num(String s) {
    String t = s.trim();
    return t.isEmpty() || t.equals("-") ? null : new BigDecimal(t);
  }

  private static Long lng(String s) {
    String t = s.trim();
    return t.isEmpty() || t.equals("-") ? null : Long.valueOf(t);
  }
}
