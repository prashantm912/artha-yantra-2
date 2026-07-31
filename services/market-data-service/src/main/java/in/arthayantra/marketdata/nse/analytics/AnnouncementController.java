package in.arthayantra.marketdata.nse.analytics;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.marketdata.nse.NseAnnouncementFetcher;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
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
 * today IST, from = to − 7d); {@code symbol} optional (blank = all equities). Typed as a record (D3)
 * so the shape DOES enumerate into the springdoc schema — the previous Map envelope made every key
 * here invisible to the contract gate, which the old comment described as if it were a feature.
 * Delegates to {@link AnnouncementService}, which NEVER throws — when
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
  public AnnouncementsResponse announcements(
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String symbol) {
    AnnouncementService.Feed feed = service.list(parseDate(from), parseDate(to), symbol);
    return new AnnouncementsResponse(
        feed.items(),
        feed.from().toString(),
        feed.to().toString(),
        feed.symbol() == null ? "" : feed.symbol());
  }

  /**
   * The announcements envelope. NOT {@code AnnouncementService.Feed} itself: the wire form differs
   * on two components and both are preserved verbatim — {@code from}/{@code to} are emitted as the
   * {@code LocalDate.toString()} STRING (typing them as {@code LocalDate} would look identical only
   * while Jackson's ISO date config holds, so the string is pinned instead), and a null
   * {@code symbol} is emitted as {@code ""}, never null. Multi-key {@code Map.of} before D3 — order
   * NORMALISED, not preserved.
   */
  public record AnnouncementsResponse(
      List<NseAnnouncementFetcher.Announcement> items, String from, String to, String symbol) {}

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
