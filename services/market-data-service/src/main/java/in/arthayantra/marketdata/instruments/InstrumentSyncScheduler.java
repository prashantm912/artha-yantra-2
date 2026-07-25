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
   * Bounded catch-up sweep — every 15 min across 09:05–10:50 IST, re-running ONLY while today has
   * no successful sync.
   *
   * <p>Preserving the {@code kite-dump} permit through an open breaker (F-SYNC) is necessary but not
   * sufficient — nothing was ever going to spend it. {@code CallNotPermittedException} is
   * deliberately NOT retryable, and {@link #morningSync()} invokes {@code runSync()} exactly once,
   * so a breaker rejection at 08:30 recorded FAILED and that was the whole day: only a manual call
   * could use the preserved permit. This is the pass that actually closes the loop.
   *
   * <p><b>Why a repeating sweep and not a single 09:05 shot.</b> 09:05 is 35 minutes after the
   * NOMINAL cron, which is not the same as 35 minutes after the permit was actually spent: these
   * jobs share market-data's single-thread scheduler and can start late, so an 08:30 pass that
   * really ran at 08:40 would leave the 09:05 attempt facing a still-empty {@code kite-dump} bucket
   * (one permit / 30 min) — it would record a second FAILED run and there would be no third chance.
   * Eight attempts across a bounded 09:05–10:50 window make recovery robust to that skew without
   * becoming an unbounded retry; each firing is a cheap state read once the day has succeeded.
   *
   * <p>A separate cron, never a sleep-and-retry inside {@link #morningSync()}: this rides
   * market-data's shared scheduler, and blocking it for 35 minutes would stall every sibling job —
   * the exact defect S1 (bar-flush behind the options pass) was just fixed for.
   */
  @Scheduled(cron = "0 5,20,35,50 9,10 * * MON-FRI", zone = "Asia/Kolkata")
  public void morningSyncCatchUp() {
    if (!tradingDay()) {
      return;
    }
    InstrumentSyncService.SyncStatus status = syncService.status();
    if ("RUNNING".equals(status.state())) {
      return; // in flight — never double-run; one dump burns one scarce permit
    }
    if (succeededToday(status)) {
      return;
    }
    log.warn(
        "instrument sync catch-up (last run {} at {}): {}",
        status.state(),
        status.lastRun(),
        syncService.runSync().state());
  }

  /**
   * True only when a sync ended OK <b>today, IST</b>.
   *
   * <p>⚠️ Checking {@code "OK".equals(state)} alone is NOT enough, and the obvious version of this
   * method was wrong: {@link InstrumentSyncService#status()} SYNTHESIZES an {@code OK} whenever the
   * DB holds any prior {@code last_seen_at} — i.e. yesterday's sync, after any restart. A container
   * that booted after 08:30, or was restarted following a failed pass, would therefore report OK and
   * the catch-up would skip while TODAY had no successful sync at all. The date check is the whole
   * guard; without it this pass silently does nothing in exactly the scenarios it exists for.
   */
  private boolean succeededToday(InstrumentSyncService.SyncStatus status) {
    if (!"OK".equals(status.state()) || status.lastRun() == null) {
      return false;
    }
    LocalDate ranOn = status.lastRun().atZone(Ist.ZONE).toLocalDate();
    return ranOn.equals(LocalDate.now(clock.withZone(Ist.ZONE)));
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
