package in.arthayantra.strategysignal.insights;

import java.time.LocalDate;
import java.util.List;

/**
 * The expiry snapshot the EXPIRY_EVENT generator consumes (INT design §2.2). Assembled at the engine
 * edge by {@link PortfolioReader} — open derivative positions whose contract expires within the T-1
 * window, joined to their opening signal's {@code scalper_detail.expiry}, plus the market-calendar
 * holiday proximity around the expiry.
 *
 * @param istToday the IST date the sweep ran
 * @param expiring the open positions expiring within the window
 * @param nextHoliday the next listed market holiday on/after tomorrow, or {@code null}
 * @param nextHolidayName its name, or {@code null}
 */
public record ExpirySnapshot(
    LocalDate istToday,
    List<ExpiringPosition> expiring,
    LocalDate nextHoliday,
    String nextHolidayName) {

  /** One open position expiring soon (INT design §2.2 EXPIRY_EVENT). */
  public record ExpiringPosition(
      long positionId,
      String book,
      String exchange,
      String tradingsymbol,
      String side,
      long qty,
      LocalDate expiry,
      long daysToExpiry) {}
}
