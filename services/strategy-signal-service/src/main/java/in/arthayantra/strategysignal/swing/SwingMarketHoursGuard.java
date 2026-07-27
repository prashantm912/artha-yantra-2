package in.arthayantra.strategysignal.swing;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.marketcalendar.MarketCalendar;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Server-side safety gate for manual swing batch runs. */
@Component
public class SwingMarketHoursGuard {

  private static final Logger log = LoggerFactory.getLogger(SwingMarketHoursGuard.class);
  private static final String MARKET_HOURS_MESSAGE =
      "manual swing batch runs are refused during NSE market hours (09:15-15:30 IST, Monday-Friday);"
          + " pass force=true for emergency override";

  private final Clock clock;
  private final MarketCalendar calendar;

  /** Wires the shared application clock and bundled NSE holiday calendar. */
  public SwingMarketHoursGuard(Clock clock) {
    this.clock = clock;
    this.calendar = MarketCalendar.nse();
  }

  /** Refuses an unforced run during market hours, failing closed if time resolution is unavailable. */
  public void check(boolean force) {
    if (force) {
      return;
    }

    boolean marketHours;
    try {
      marketHours = calendar.isOpen(clock.instant());
    } catch (RuntimeException exception) {
      log.error("unable to resolve IST market hours; refusing manual swing batch", exception);
      marketHours = true;
    }
    if (marketHours) {
      throw new ApiException(422, ErrorCodes.VALIDATION_FAILED, MARKET_HOURS_MESSAGE);
    }
  }
}
