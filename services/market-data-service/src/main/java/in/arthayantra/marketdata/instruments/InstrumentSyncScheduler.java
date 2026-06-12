package in.arthayantra.marketdata.instruments;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import java.time.Clock;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** The 08:30 IST daily instrument sync (B-12) — six-field cron, IST zone, calendar-gated. */
@Component
public class InstrumentSyncScheduler {

  private static final Logger log = LoggerFactory.getLogger(InstrumentSyncScheduler.class);

  private final InstrumentSyncService syncService;
  private final MarketCalendar calendar;
  private final Clock clock;

  /** Wires the sync. */
  public InstrumentSyncScheduler(
      InstrumentSyncService syncService, MarketCalendar calendar, Clock clock) {
    this.syncService = syncService;
    this.calendar = calendar;
    this.clock = clock;
  }

  /** 08:30 IST daily full NSE/NFO/BFO sync. */
  @Scheduled(cron = "0 30 8 * * MON-FRI", zone = "Asia/Kolkata")
  public void morningSync() {
    LocalDate today = LocalDate.now(clock.withZone(Ist.ZONE));
    try {
      if (!calendar.isTradingDay(today)) {
        return;
      }
    } catch (IllegalArgumentException uncoveredYear) {
      return;
    }
    log.info("08:30 instrument sync: {}", syncService.runSync().state());
  }
}
