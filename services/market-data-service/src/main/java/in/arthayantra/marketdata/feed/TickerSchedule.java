package in.arthayantra.marketdata.feed;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import java.time.Clock;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Ticker auto start/stop (B-12, live profile only — the mock feed free-runs): 09:10 IST start so
 * the socket is up before the 09:15 open, 15:35 IST stop after close. Six-field crons, IST zone,
 * calendar-gated.
 */
@Component
@Profile("live")
public class TickerSchedule {

  private static final Logger log = LoggerFactory.getLogger(TickerSchedule.class);

  private final FeedPipeline pipeline;
  private final MarketCalendar calendar;
  private final Clock clock;

  /** Wires the pipeline. */
  public TickerSchedule(FeedPipeline pipeline, MarketCalendar calendar, Clock clock) {
    this.pipeline = pipeline;
    this.calendar = calendar;
    this.clock = clock;
  }

  /** 09:10 IST — connect before the open. */
  @Scheduled(cron = "0 10 9 * * MON-FRI", zone = "Asia/Kolkata")
  public void morningStart() {
    if (isTradingDay()) {
      log.info("09:10 ticker auto-start");
      pipeline.startFeed();
    }
  }

  /** 15:35 IST — disconnect after the close. */
  @Scheduled(cron = "0 35 15 * * MON-FRI", zone = "Asia/Kolkata")
  public void eveningStop() {
    if (isTradingDay()) {
      log.info("15:35 ticker auto-stop");
      pipeline.stopFeed();
    }
  }

  private boolean isTradingDay() {
    try {
      return calendar.isTradingDay(LocalDate.now(clock.withZone(Ist.ZONE)));
    } catch (IllegalArgumentException uncoveredYear) {
      return false;
    }
  }
}
