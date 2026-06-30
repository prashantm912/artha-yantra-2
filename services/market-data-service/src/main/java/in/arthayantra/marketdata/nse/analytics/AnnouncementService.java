package in.arthayantra.marketdata.nse.analytics;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketdata.nse.NseAnnouncementFetcher;
import in.arthayantra.marketdata.nse.NseAnnouncementFetcher.Announcement;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The Announcement feed (oipulse §equity/announcement) — NSE corporate filings over a date range,
 * optionally narrowed to one symbol. Thin over the {@link NseAnnouncementFetcher} port: defaults the
 * range to the last 7 days (the oipulse default) and degrades to an empty list when the source is not
 * live (mock) or the NSE call fails, so the page renders its empty state rather than 5xx-ing.
 */
@Service
public class AnnouncementService {

  /** Default look-back when no start date is given (the oipulse default range). */
  private static final int DEFAULT_LOOKBACK_DAYS = 7;

  private static final Logger log = LoggerFactory.getLogger(AnnouncementService.class);

  private final NseAnnouncementFetcher fetcher;
  private final Clock clock;

  public AnnouncementService(NseAnnouncementFetcher fetcher, Clock clock) {
    this.fetcher = fetcher;
    this.clock = clock;
  }

  /** The resolved range + the announcements (newest first); empty list when the source is unavailable. */
  public record Feed(LocalDate from, LocalDate to, String symbol, List<Announcement> items) {}

  /**
   * Announcements in {@code [from, to]} (defaults: to = today IST, from = to − 7d), optionally filtered
   * to {@code symbol}. NEVER throws — an NSE/transport failure degrades to an empty list.
   */
  public Feed list(LocalDate from, LocalDate to, String symbol) {
    LocalDate end = to != null ? to : LocalDate.now(clock.withZone(Ist.ZONE));
    LocalDate start = from != null ? from : end.minusDays(DEFAULT_LOOKBACK_DAYS);
    String sym = symbol == null || symbol.isBlank() ? null : symbol.trim().toUpperCase();
    List<Announcement> items;
    try {
      items = fetcher.fetch(start, end, sym);
    } catch (RuntimeException e) {
      log.warn("NSE announcements {}..{} symbol={} failed — empty: {}", start, end, sym, e.toString());
      items = List.of();
    }
    return new Feed(start, end, sym, items);
  }
}
