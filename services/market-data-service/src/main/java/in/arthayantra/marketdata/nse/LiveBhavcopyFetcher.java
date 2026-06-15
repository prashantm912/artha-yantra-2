package in.arthayantra.marketdata.nse;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

  private static final int MAX_LOOKBACK_DAYS = 5;
  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private static final DateTimeFormatter URL_DATE = DateTimeFormatter.ofPattern("ddMMyyyy");
  private static final DateTimeFormatter ROW_DATE =
      DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);

  private final NseHttpClient client;
  private final String archivesUrl;
  private final Clock clock;

  public LiveBhavcopyFetcher(
      NseHttpClient client,
      @Value("${artha.nse.archives-url:https://nsearchives.nseindia.com}") String archivesUrl,
      Clock clock) {
    this.client = client;
    this.archivesUrl = archivesUrl;
    this.clock = clock;
  }

  @Override
  public List<BhavcopyRow> fetchLatest() {
    LocalDate today = OffsetDateTime.now(clock).atZoneSameInstant(IST).toLocalDate();
    RuntimeException last = null;
    for (int back = 0; back <= MAX_LOOKBACK_DAYS; back++) {
      LocalDate d = today.minusDays(back);
      String url =
          archivesUrl + "/products/content/sec_bhavdata_full_" + URL_DATE.format(d) + ".csv";
      try {
        String csv = client.getAbsolute(url);
        if (csv != null && csv.contains("DELIV_PER")) {
          return parse(csv);
        }
      } catch (RuntimeException miss) {
        last = miss; // 404 on a non-trading day is expected — keep walking back
      }
    }
    throw new IllegalStateException(
        "No NSE bhavcopy file in the last " + MAX_LOOKBACK_DAYS + " days", last);
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
