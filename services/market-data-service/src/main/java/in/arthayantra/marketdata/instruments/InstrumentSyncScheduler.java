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
    if (!tradingDay()) {
      return;
    }
    log.info("08:30 instrument sync: {}", syncService.runSync().state());
  }

  /**
   * 09:05 IST catch-up: re-runs the sync ONLY when the 08:30 pass did not end OK.
   *
   * <p>Preserving the {@code kite-dump} permit through an open breaker (F-SYNC) is necessary but not
   * sufficient — nothing was ever going to spend it. {@code CallNotPermittedException} is
   * deliberately NOT retryable, and {@link #morningSync()} invokes {@code runSync()} exactly once,
   * so a breaker rejection at 08:30 recorded FAILED and that was the whole day: only a manual call
   * could use the preserved permit. This is the pass that actually closes the loop.
   *
   * <p>35 minutes is chosen against BOTH clocks: the breaker's open state lasts 30s, and
   * {@code kite-dump} refreshes one permit every 30 minutes — so even in the worst case (the 08:30
   * attempt reached Kite and legitimately spent its permit) a fresh one exists by 09:05.
   *
   * <p>A separate cron, never a sleep-and-retry inside {@link #morningSync()}: this rides
   * market-data's shared scheduler, and blocking it for 35 minutes would stall every sibling job —
   * the exact defect S1 (bar-flush behind the options pass) was just fixed for.
   */
  @Scheduled(cron = "0 5 9 * * MON-FRI", zone = "Asia/Kolkata")
  public void morningSyncCatchUp() {
    if (!tradingDay()) {
      return;
    }
    String state = syncService.status().state();
    if ("OK".equals(state) || "RUNNING".equals(state)) {
      return; // 08:30 succeeded, or is still in flight — never double-run a sync
    }
    log.warn("09:05 instrument sync catch-up (08:30 ended {}): {}", state, syncService.runSync().state());
  }

  private boolean tradingDay() {
    LocalDate today = LocalDate.now(clock.withZone(Ist.ZONE));
    try {
      return calendar.isTradingDay(today);
    } catch (IllegalArgumentException uncoveredYear) {
      return false;
    }
  }
}
