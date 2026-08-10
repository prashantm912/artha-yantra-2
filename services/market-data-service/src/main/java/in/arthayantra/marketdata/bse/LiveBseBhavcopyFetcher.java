package in.arthayantra.marketdata.bse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Downloads + parses the BSE UDiFF daily equity bhavcopy
 * ({@code /download/BhavCopy/Equity/BhavCopy_BSE_CM_0_0_0_YYYYMMDD_F_0000.CSV}). The combined CM file
 * carries equities and BSE index-derivatives; only cash equities ({@code FinInstrmTp=STK},
 * {@code Sgmt=CM}) are kept. Columns are read by HEADER NAME (the UDiFF layout is wide and may grow),
 * and the row splitter is quote-aware so a comma inside an instrument name can never shift the OHLC.
 *
 * <p>BSE returns HTTP 200 + the SPA homepage HTML on a non-trading day (not a 404), so a missing
 * file is detected by content-sniffing the body (HTML / wrong header line) → empty list.
 */
@Component
@Profile("live")
public class LiveBseBhavcopyFetcher implements BseBhavcopyFetcher {

  private static final Logger log = LoggerFactory.getLogger(LiveBseBhavcopyFetcher.class);
  private static final DateTimeFormatter URL_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

  private final BseHttpClient client;
  private final String baseUrl;

  public LiveBseBhavcopyFetcher(
      BseHttpClient client, @Value("${artha.bse.base-url:https://www.bseindia.com}") String baseUrl) {
    this.client = client;
    this.baseUrl = baseUrl;
  }

  @Override
  public List<BseBhavRow> fetchForDate(LocalDate date) {
    String url =
        baseUrl
            + "/download/BhavCopy/Equity/BhavCopy_BSE_CM_0_0_0_"
            + URL_DATE.format(date)
            + "_F_0000.CSV";
    String body;
    try {
      body = client.getAbsolute(url);
    } catch (RuntimeException miss) {
      return List.of(); // network error / unexpected status — treat as not published, skip
    }
    // Content-sniff: a real file starts with the UDiFF header; the non-trading-day response is HTML.
    if (body == null || body.isBlank() || body.stripLeading().startsWith("<")) {
      return List.of();
    }
    return parse(body, date);
  }

  private static List<BseBhavRow> parse(String csv, LocalDate expected) {
    String[] lines = csv.split("\\r?\\n");
    if (lines.length < 2 || !lines[0].startsWith("TradDt,")) {
      return List.of(); // not the expected UDiFF header
    }
    List<String> header = splitCsv(lines[0]);
    Map<String, Integer> col = new HashMap<>();
    for (int i = 0; i < header.size(); i++) {
      col.put(header.get(i).trim(), i);
    }
    List<BseBhavRow> rows = new ArrayList<>();
    int skipped = 0;
    for (int i = 1; i < lines.length; i++) {
      if (lines[i].isBlank()) {
        continue;
      }
      List<String> c = splitCsv(lines[i]);
      try {
        if (!"STK".equals(get(c, col, "FinInstrmTp")) || !"CM".equals(get(c, col, "Sgmt"))) {
          continue; // index-derivative / non-cash row in the combined file
        }
        rows.add(
            new BseBhavRow(
                LocalDate.parse(get(c, col, "TradDt")),
                get(c, col, "FinInstrmId"),
                get(c, col, "TckrSymb"),
                get(c, col, "ISIN"),
                get(c, col, "SctySrs"),
                num(get(c, col, "PrvsClsgPric")),
                num(get(c, col, "OpnPric")),
                num(get(c, col, "HghPric")),
                num(get(c, col, "LwPric")),
                num(get(c, col, "LastPric")),
                num(get(c, col, "ClsPric")),
                lng(get(c, col, "TtlTradgVol")),
                num(get(c, col, "TtlTrfVal")),
                lng(get(c, col, "TtlNbOfTxsExctd"))));
      } catch (RuntimeException malformed) {
        skipped++; // a misaligned / unparseable row — drop it, never corrupt the rest
      }
    }
    if (skipped > 0) {
      log.warn("BSE bhavcopy {}: skipped {} malformed rows", expected, skipped);
    }
    // Same seam as NSE: the trade date comes from the file's own TradDt, so a server that answers
    // 200 with a DIFFERENT day's file would store that day's rows and leave the requested one
    // permanently missing. Refuse the payload rather than mis-file it.
    if (!rows.isEmpty() && !rows.stream().allMatch(r -> expected.equals(r.date()))) {
      log.warn(
          "BSE bhavcopy {}: file served {} row(s) dated {} — discarding as not published",
          expected,
          rows.size(),
          rows.stream().map(BseBhavRow::date).distinct().sorted().toList());
      return List.of();
    }
    return rows;
  }

  private static String get(List<String> cells, Map<String, Integer> col, String name) {
    Integer idx = col.get(name);
    if (idx == null || idx >= cells.size()) {
      throw new IllegalStateException("missing column " + name);
    }
    return cells.get(idx).trim();
  }

  private static BigDecimal num(String s) {
    return s == null || s.isEmpty() ? null : new BigDecimal(s);
  }

  private static Long lng(String s) {
    return s == null || s.isEmpty() ? null : Long.valueOf(s);
  }

  /** RFC-4180-ish split: honours double-quoted fields so an embedded comma never shifts columns. */
  static List<String> splitCsv(String line) {
    List<String> out = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    boolean inQuotes = false;
    for (int i = 0; i < line.length(); i++) {
      char ch = line.charAt(i);
      if (inQuotes) {
        if (ch == '"') {
          if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
            cur.append('"');
            i++;
          } else {
            inQuotes = false;
          }
        } else {
          cur.append(ch);
        }
      } else if (ch == '"') {
        inQuotes = true;
      } else if (ch == ',') {
        out.add(cur.toString());
        cur.setLength(0);
      } else {
        cur.append(ch);
      }
    }
    out.add(cur.toString());
    return out;
  }
}
