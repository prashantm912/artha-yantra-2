package in.arthayantra.marketdata.options;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The 16:00 IST IV rollup job (F-42B): MarketCalendar-gated, it distils the trading day's
 * {@code options_chain_snapshots} into one {@code iv_daily_summary} row per configured underlying.
 * Idempotent (the recompute path is the same {@link IvAnalyticsService#rollup}), so a missed run or
 * a later solver fix is healed by the retroactive {@code /iv-rollup} command.
 */
@Service
public class IvRollupJob {

  private static final Logger log = LoggerFactory.getLogger(IvRollupJob.class);

  private final IvAnalyticsService analytics;
  private final MarketCalendar calendar;
  private final Clock clock;
  private final List<String> underlyings;

  /** Wires the rollup over the configured snapshot underlyings. */
  public IvRollupJob(
      IvAnalyticsService analytics,
      MarketCalendar calendar,
      Clock clock,
      @Value("${artha.options.snapshot-underlyings:NIFTY 50}") List<String> underlyings) {
    this.analytics = analytics;
    this.calendar = calendar;
    this.clock = clock;
    this.underlyings = underlyings;
  }

  /** 16:00 IST on trading days — after the 15:30 close, over the day's accrued snapshots. */
  @Scheduled(cron = "0 0 16 * * MON-FRI", zone = "Asia/Kolkata")
  public void scheduledRollup() {
    LocalDate today = LocalDate.ofInstant(clock.instant(), Ist.ZONE);
    if (!isTradingDaySafe(today)) {
      return;
    }
    for (String underlying : underlyings) {
      try {
        analytics.rollup(underlying, today);
      } catch (Exception e) {
        log.warn("iv rollup failed for {} on {}: {}", underlying, today, e.getMessage());
      }
    }
  }

  private boolean isTradingDaySafe(LocalDate date) {
    try {
      return calendar.isTradingDay(date);
    } catch (IllegalArgumentException uncoveredYear) {
      return false;
    }
  }
}
