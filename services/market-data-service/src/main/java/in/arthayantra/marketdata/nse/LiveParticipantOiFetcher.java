package in.arthayantra.marketdata.nse;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Parses the NSE {@code fao_participant_oi_DDMMYYYY.csv} archive (B-1b). The file is date-stamped in
 * the URL, so {@code fetchLatest} walks back from today (IST) to the most recent published file —
 * weekends/holidays 404 and are skipped. The authoritative trade date is read from the title line.
 */
@Component
@Profile("live")
public class LiveParticipantOiFetcher implements ParticipantOiFetcher {

  private static final int MAX_LOOKBACK_DAYS = 5;
  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private static final DateTimeFormatter URL_DATE = DateTimeFormatter.ofPattern("ddMMyyyy");
  private static final DateTimeFormatter TITLE_DATE =
      DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);
  private static final Pattern AS_ON = Pattern.compile("as on ([A-Za-z]{3} \\d{1,2}, \\d{4})");

  private final NseHttpClient client;
  private final String archivesUrl;
  private final Clock clock;

  public LiveParticipantOiFetcher(
      NseHttpClient client,
      @Value("${artha.nse.archives-url:https://nsearchives.nseindia.com}") String archivesUrl,
      Clock clock) {
    this.client = client;
    this.archivesUrl = archivesUrl;
    this.clock = clock;
  }

  @Override
  public List<ParticipantOiRow> fetchLatest() {
    LocalDate today = OffsetDateTime.now(clock).atZoneSameInstant(IST).toLocalDate();
    RuntimeException last = null;
    for (int back = 0; back <= MAX_LOOKBACK_DAYS; back++) {
      LocalDate d = today.minusDays(back);
      String url = archivesUrl + "/content/nsccl/fao_participant_oi_" + URL_DATE.format(d) + ".csv";
      try {
        String csv = client.getAbsolute(url);
        if (csv != null && csv.contains("Client Type")) {
          return parse(csv);
        }
      } catch (RuntimeException miss) {
        last = miss; // 404 on a non-trading day is expected — keep walking back
      }
    }
    throw new IllegalStateException(
        "No NSE participant-OI file in the last " + MAX_LOOKBACK_DAYS + " days", last);
  }

  private static List<ParticipantOiRow> parse(String csv) {
    String[] lines = csv.split("\\r?\\n");
    LocalDate date = extractDate(lines[0]);
    List<ParticipantOiRow> rows = new ArrayList<>();
    // line 0 = title, line 1 = header, line 2.. = one row per client type
    for (int i = 2; i < lines.length; i++) {
      String line = lines[i].trim();
      if (line.isEmpty()) {
        continue;
      }
      String[] c = line.split(",");
      if (c.length < 15) {
        continue;
      }
      rows.add(
          new ParticipantOiRow(
              date,
              c[0].trim(),
              lng(c[1]),
              lng(c[2]),
              lng(c[3]),
              lng(c[4]),
              lng(c[5]),
              lng(c[6]),
              lng(c[7]),
              lng(c[8]),
              lng(c[9]),
              lng(c[10]),
              lng(c[11]),
              lng(c[12]),
              lng(c[13]),
              lng(c[14])));
    }
    return rows;
  }

  private static LocalDate extractDate(String titleLine) {
    Matcher m = AS_ON.matcher(titleLine);
    if (!m.find()) {
      throw new IllegalStateException("no date in participant-OI title line: " + titleLine);
    }
    return LocalDate.parse(m.group(1), TITLE_DATE);
  }

  private static long lng(String s) {
    return Long.parseLong(s.trim());
  }
}
