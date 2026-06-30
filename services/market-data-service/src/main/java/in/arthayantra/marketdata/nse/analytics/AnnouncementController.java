package in.arthayantra.marketdata.nse.analytics;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Announcement read surface (oipulse §equity/announcement) — NSE corporate filings over a date range,
 * optionally narrowed to one symbol.
 *
 * <p>{@code GET /api/v1/market/equity/announcements?from&to&symbol} → {@code {items:[Announcement...],
 * from, to, symbol}} (newest first). {@code from}/{@code to} are ISO {@code yyyy-MM-dd} (defaults: to =
 * today IST, from = to − 7d); {@code symbol} optional (blank = all equities). Map-envelope so it does
 * not enumerate a springdoc schema. Delegates to {@link AnnouncementService}, which NEVER throws — when
 * the NSE source is not live (mock) or the call fails the items list is empty and the page renders its
 * empty state rather than 5xx-ing.
 */
@RestController
@RequestMapping("/api/v1/market/equity")
public class AnnouncementController {

  private final AnnouncementService service;

  public AnnouncementController(AnnouncementService service) {
    this.service = service;
  }

  /** The corporate-announcements feed for the range; empty items when the NSE source isn't live. */
  @GetMapping("/announcements")
  public Map<String, Object> announcements(
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String symbol) {
    AnnouncementService.Feed feed = service.list(parseDate(from), parseDate(to), symbol);
    return Map.of(
        "items", feed.items(),
        "from", feed.from().toString(),
        "to", feed.to().toString(),
        "symbol", feed.symbol() == null ? "" : feed.symbol());
  }

  private static LocalDate parseDate(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(raw);
    } catch (DateTimeParseException e) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "date must be ISO yyyy-MM-dd");
    }
  }
}
