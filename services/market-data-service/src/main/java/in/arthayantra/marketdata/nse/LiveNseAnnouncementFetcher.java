package in.arthayantra.marketdata.nse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
 * Fetches the NSE corporate-announcements JSON
 * ({@code /api/corporate-announcements?index=equities&from_date&to_date[&symbol]}). NSE gates it behind
 * anti-bot cookies, so it goes through {@link NseHttpClient#getWithCookieSeed} with the corporate-
 * filings-announcements referer. Each row maps to an {@link Announcement}: {@code symbol} / {@code desc}
 * (subject) / {@code attchmntText} (detail) / {@code attchmntFile} (the PDF) / {@code an_dt} (the
 * announcement datetime, split into date + time) / {@code sm_name} (company). {@code live}-only; the
 * non-live context gets the empty {@link MockNseAnnouncementFetcher}.
 */
@Component
@Profile("live")
public class LiveNseAnnouncementFetcher implements NseAnnouncementFetcher {

  private static final Logger log = LoggerFactory.getLogger(LiveNseAnnouncementFetcher.class);
  private static final DateTimeFormatter PARAM = DateTimeFormatter.ofPattern("dd-MM-yyyy");
  /** NSE {@code an_dt} = e.g. {@code "16-Jun-2026 14:48:50"}. */
  private static final DateTimeFormatter AN_DT =
      DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss", Locale.ENGLISH);
  private static final String REFERER_PATH = "/companies-listing/corporate-filings-announcements";

  private final NseHttpClient client;
  private final String baseUrl;
  private final ObjectMapper mapper;

  public LiveNseAnnouncementFetcher(
      NseHttpClient client,
      @Value("${artha.nse.base-url:https://www.nseindia.com}") String baseUrl,
      ObjectMapper mapper) {
    this.client = client;
    this.baseUrl = baseUrl;
    this.mapper = mapper;
  }

  @Override
  public List<Announcement> fetch(LocalDate from, LocalDate to, String symbol) {
    StringBuilder path =
        new StringBuilder("/api/corporate-announcements?index=equities&from_date=")
            .append(PARAM.format(from))
            .append("&to_date=")
            .append(PARAM.format(to));
    if (symbol != null && !symbol.isBlank()) {
      path.append("&symbol=").append(URLEncoder.encode(symbol.trim().toUpperCase(), StandardCharsets.UTF_8));
    }
    String json = client.getWithCookieSeed(path.toString(), baseUrl + REFERER_PATH);
    JsonNode root;
    try {
      root = mapper.readTree(json);
    } catch (Exception parseFailed) {
      throw new IllegalStateException("NSE corporate-announcements parse failed", parseFailed);
    }
    JsonNode arr = root.isArray() ? root : root.path("data");
    List<Announcement> rows = new ArrayList<>();
    for (JsonNode n : arr) {
      Announcement a = mapRow(n);
      if (a != null) {
        rows.add(a);
      }
    }
    log.debug("NSE corporate-announcements {}..{} symbol={}: {} rows", from, to, symbol, rows.size());
    return rows;
  }

  /** Maps one NSE announcement node to {@link Announcement}; null when it carries no symbol. */
  static Announcement mapRow(JsonNode n) {
    String symbol = text(n, "symbol");
    if (symbol == null) {
      return null;
    }
    String when = text(n, "an_dt");
    if (when == null) {
      when = text(n, "sort_date"); // some rows carry the timestamp here instead
    }
    String[] dt = splitDateTime(when);
    return new Announcement(
        dt[0],
        dt[1],
        symbol,
        text(n, "sm_name"),
        text(n, "desc"),
        text(n, "attchmntText"),
        text(n, "attchmntFile"));
  }

  /**
   * Splits NSE's {@code an_dt} ("16-Jun-2026 14:48:50") into {@code [isoDate, time]}. Falls back to a
   * plain space-split (date part, time part) when it does not parse, and to {@code [raw, ""]} / {@code
   * ["", ""]} when there is no time / no value.
   */
  static String[] splitDateTime(String anDt) {
    if (anDt == null || anDt.isBlank()) {
      return new String[] {"", ""};
    }
    try {
      LocalDateTime ldt = LocalDateTime.parse(anDt.trim(), AN_DT);
      return new String[] {ldt.toLocalDate().toString(), ldt.toLocalTime().toString()};
    } catch (RuntimeException notParsed) {
      String[] parts = anDt.trim().split("\\s+", 2);
      return new String[] {parts[0], parts.length > 1 ? parts[1] : ""};
    }
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
