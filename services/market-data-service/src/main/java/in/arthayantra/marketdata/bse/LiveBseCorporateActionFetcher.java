package in.arthayantra.marketdata.bse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Fetches BSE corporate actions from {@code api.bseindia.com/BseIndiaAPI/api/DefaultData/w}. The
 * {@code strSearch=S} mode honours the date range + ex-date filter and returns a JSON array (no
 * cookie seeding — UA + referer from {@link BseHttpClient} suffice; an empty range yields {@code []},
 * not the SPA homepage). The feed carries NO ISIN, so rows are keyed by scrip code + short name
 * downstream. Each row's {@code Purpose} (e.g. "Bonus issue 1:1", "Stock  Split From Rs.10/- to
 * Rs.1/-") is parsed into a ratio by the shared {@code CorporateActionSubjectParser}.
 */
@Component
@Profile("live")
public class LiveBseCorporateActionFetcher implements BseCorporateActionFetcher {

  private static final Logger log = LoggerFactory.getLogger(LiveBseCorporateActionFetcher.class);
  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

  private final BseHttpClient client;
  private final String apiBaseUrl;
  private final ObjectMapper mapper;

  public LiveBseCorporateActionFetcher(
      BseHttpClient client,
      @Value("${artha.bse.api-url:https://api.bseindia.com}") String apiBaseUrl,
      ObjectMapper mapper) {
    this.client = client;
    this.apiBaseUrl = apiBaseUrl;
    this.mapper = mapper;
  }

  @Override
  public List<BseCaRecord> fetchRecent(LocalDate from, LocalDate to) {
    String url =
        apiBaseUrl
            + "/BseIndiaAPI/api/DefaultData/w?Fdate="
            + DATE.format(from)
            + "&TDate="
            + DATE.format(to)
            + "&strSearch=S&ddlindustrys=&ddlcategorys=E&segment=0&scripcode=&Purposecode=";
    String json = client.getAbsolute(url);
    JsonNode root;
    try {
      root = mapper.readTree(json);
    } catch (Exception parseFailed) {
      throw new IllegalStateException("BSE corporate-actions parse failed", parseFailed);
    }
    JsonNode arr = root.isArray() ? root : root.path("Table");
    List<BseCaRecord> rows = new ArrayList<>();
    int skipped = 0;
    for (JsonNode n : arr) {
      String scrip = text(n, "scrip_code");
      String shortName = text(n, "short_name");
      String exRaw = text(n, "exdate");
      String purpose = text(n, "Purpose");
      if (exRaw == null || (scrip == null && shortName == null)) {
        continue;
      }
      try {
        rows.add(new BseCaRecord(scrip, shortName, LocalDate.parse(exRaw, DATE), purpose));
      } catch (RuntimeException badDate) {
        skipped++;
      }
    }
    if (skipped > 0) {
      log.debug("BSE corporate-actions {}..{}: skipped {} rows without a usable ex-date", from, to, skipped);
    }
    return rows;
  }

  private static String text(JsonNode n, String field) {
    JsonNode v = n.get(field);
    if (v == null || v.isNull()) {
      return null;
    }
    String s = v.asText().trim();
    return s.isEmpty() ? null : s;
  }
}
