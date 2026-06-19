package in.arthayantra.marketdata.nse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
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
 * Fetches the NSE corporate-actions JSON
 * ({@code /api/corporates-corporateActions?index=equities&from_date&to_date}). NSE gates it behind
 * anti-bot cookies, so it goes through {@link NseHttpClient#getWithCookieSeed} with the corporate-
 * filings referer. Each row's {@code subject} is the human-readable action text (e.g. "Bonus 1:3",
 * "Face Value Split ... From Rs 10/- To Re 1/-") parsed downstream into a ratio.
 */
@Component
@Profile("live")
public class LiveNseCorporateActionFetcher implements NseCorporateActionFetcher {

  private static final Logger log = LoggerFactory.getLogger(LiveNseCorporateActionFetcher.class);
  private static final DateTimeFormatter PARAM = DateTimeFormatter.ofPattern("dd-MM-yyyy");
  private static final DateTimeFormatter EX_DATE =
      DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);
  private static final String REFERER_PATH = "/companies-listing/corporate-filings-actions";

  private final NseHttpClient client;
  private final String baseUrl;
  private final ObjectMapper mapper;

  public LiveNseCorporateActionFetcher(
      NseHttpClient client,
      @Value("${artha.nse.base-url:https://www.nseindia.com}") String baseUrl,
      ObjectMapper mapper) {
    this.client = client;
    this.baseUrl = baseUrl;
    this.mapper = mapper;
  }

  @Override
  public List<CaRecord> fetchRecent(LocalDate from, LocalDate to) {
    String path =
        "/api/corporates-corporateActions?index=equities&from_date="
            + PARAM.format(from)
            + "&to_date="
            + PARAM.format(to);
    String json = client.getWithCookieSeed(path, baseUrl + REFERER_PATH);
    JsonNode root;
    try {
      root = mapper.readTree(json);
    } catch (Exception parseFailed) {
      throw new IllegalStateException("NSE corporate-actions parse failed", parseFailed);
    }
    JsonNode arr = root.isArray() ? root : root.path("data");
    List<CaRecord> rows = new ArrayList<>();
    int skipped = 0;
    for (JsonNode n : arr) {
      String symbol = text(n, "symbol");
      String subject = text(n, "subject");
      String exRaw = text(n, "exDate");
      if (symbol == null || exRaw == null) {
        continue;
      }
      try {
        rows.add(new CaRecord(symbol, text(n, "isin"), LocalDate.parse(exRaw, EX_DATE), subject));
      } catch (RuntimeException badDate) {
        skipped++; // missing/placeholder ex-date — not actionable
      }
    }
    if (skipped > 0) {
      log.debug("NSE corporate-actions {}..{}: skipped {} rows without a usable ex-date", from, to, skipped);
    }
    return rows;
  }

  private static String text(JsonNode n, String field) {
    JsonNode v = n.get(field);
    if (v == null || v.isNull()) {
      return null;
    }
    String s = v.asText().trim();
    return s.isEmpty() || "-".equals(s) ? null : s;
  }
}
