package in.arthayantra.marketdata.nse;

import in.arthayantra.marketdata.constituents.IndexConstituentsFetcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Fetches the current NSE index membership CSV and turns it into canonical constituents. */
@Component
@Profile("live")
public class LiveIndexConstituentsFetcher implements IndexConstituentsFetcher {

  private static final Logger log = LoggerFactory.getLogger(LiveIndexConstituentsFetcher.class);
  private static final int MIN_EQ_PERCENT = 90;

  private final NseHttpClient client;
  private final String archivesUrl;

  /** Wires the shared NSE HTTP client and archive host. */
  public LiveIndexConstituentsFetcher(
      NseHttpClient client,
      @Value("${artha.nse.archives-url:https://nsearchives.nseindia.com}") String archivesUrl) {
    this.client = client;
    this.archivesUrl = archivesUrl;
  }

  @Override
  public Optional<List<Constituent>> fetch(String indexName) {
    String filename = filenameFor(indexName);
    if (filename == null) {
      log.warn("cannot map NSE constituents index name '{}' to a published file", indexName);
      return Optional.empty();
    }

    String url = archivesUrl + "/content/indices/" + filename;
    try {
      String csv = client.getAbsolute(url);
      if (!hasExpectedHeader(csv)) {
        log.warn(
            "NSE constituents response for '{}' is not a published CSV (missing Company Name or"
                + " ISIN Code header)",
            indexName);
        return Optional.empty();
      }
      return Optional.of(parse(csv, indexName));
    } catch (RuntimeException failure) {
      log.warn(
          "NSE constituents fetch failed for '{}' from {}: {}",
          indexName,
          url,
          failure.getMessage());
      return Optional.empty();
    }
  }

  private static String filenameFor(String indexName) {
    if (indexName == null) {
      return null;
    }
    String normalized = indexName.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    if (!normalized.matches("nifty[a-z0-9]+")) {
      return null;
    }
    return "ind_" + normalized + "list.csv";
  }

  private static boolean hasExpectedHeader(String csv) {
    if (csv == null) {
      return false;
    }
    int firstLineEnd = csv.indexOf('\n');
    String header = firstLineEnd >= 0 ? csv.substring(0, firstLineEnd) : csv;
    return header.contains("Company Name") && header.contains("ISIN Code");
  }

  private static List<Constituent> parse(String csv, String indexName) {
    String[] lines = csv.split("\\r?\\n", -1);
    List<Constituent> rows = new ArrayList<>();
    int dataLineCount = 0;
    for (int i = 1; i < lines.length; i++) {
      String line = lines[i].trim();
      if (line.isEmpty()) {
        continue;
      }
      dataLineCount++;
      if (line.contains("\"")) {
        throw new IllegalArgumentException("quoted or malformed constituent row: " + line);
      }
      String[] cells = line.split(",", -1);
      if (cells.length != 5) {
        throw new IllegalArgumentException(
            "constituent row has " + cells.length + " fields, expected 5: " + line);
      }
      String symbol = cells[2].trim();
      String series = cells[3].trim();
      if (symbol.isEmpty() || series.isEmpty()) {
        throw new IllegalArgumentException("constituent row has a blank symbol or series: " + line);
      }
      if ("EQ".equalsIgnoreCase(series)) {
        rows.add(new Constituent("NSE", symbol.toUpperCase(Locale.ROOT)));
      } else {
        // NOT silent. A blank symbol/series fails the whole fetch one branch up, so a quietly
        // dropped row would be that same defect wearing a different hat. Dropping non-EQ IS
        // intentional (the bundled fixture's NIFTYBEES,BE ETF is expected out; all 5 real NSE index
        // CSVs measured 100% EQ) — but if NSE moved a genuine member to BE/T2T surveillance it would
        // vanish from that day's snapshot, the <90% guard would not fire until ~11 members moved, and
        // `accrue` is append-only so the short snapshot would be permanent and indistinguishable from
        // a real one. Note this service already treats BE as a real equity series elsewhere
        // (application.yml:225 — `nse.bhavcopy.candle-series: EQ,BE`), so if this WARN ever fires for
        // a genuine constituent, WIDENING the filter is the likely right answer, not tightening it.
        log.warn(
            "dropping non-EQ constituent {} (series {}) from index '{}'", symbol, series, indexName);
      }
    }

    if (dataLineCount == 0) {
      throw new IllegalArgumentException("NSE constituents CSV contains no data rows");
    }
    if (rows.size() * 100L < dataLineCount * (long) MIN_EQ_PERCENT) {
      throw new IllegalArgumentException(
          "NSE constituents CSV has an implausibly short EQ list: "
              + rows.size()
              + " EQ rows out of "
              + dataLineCount
              + " data rows (minimum "
              + MIN_EQ_PERCENT
              + "%)");
    }
    return List.copyOf(rows);
  }
}
