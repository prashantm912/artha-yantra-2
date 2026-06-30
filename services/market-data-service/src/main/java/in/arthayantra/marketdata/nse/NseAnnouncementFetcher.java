package in.arthayantra.marketdata.nse;

import java.time.LocalDate;
import java.util.List;

/**
 * Port for the NSE corporate-announcements / exchange-filings feed (the Announcement page,
 * oipulse §equity/announcement). One cookie-seeded API call over a date range, optionally narrowed to a
 * single symbol. Distinct from {@link NseCorporateActionFetcher} (splits/bonuses/dividends, the ratio
 * source) and from the Upstox per-stock {@code /equity/news} headlines — this is the NSE filings feed
 * (press releases, board meetings, KMP changes, …) with the announcement PDF attachment.
 */
public interface NseAnnouncementFetcher {

  /**
   * One corporate announcement. {@code date}/{@code time} are the IST announcement date (ISO) + clock
   * (HH:mm:ss) split from NSE's {@code an_dt}; {@code subject} is the category, {@code detail} the full
   * text, {@code fileLink} the attachment PDF URL (may be null when none).
   */
  record Announcement(
      String date,
      String time,
      String symbol,
      String company,
      String subject,
      String detail,
      String fileLink) {}

  /**
   * Announcements with an announcement date in {@code [from, to]} (newest first), optionally filtered to
   * {@code symbol} (null/blank = all equities). One cookie-seeded NSE API call; an empty list when the
   * source is not live / the call fails.
   */
  List<Announcement> fetch(LocalDate from, LocalDate to, String symbol);
}
