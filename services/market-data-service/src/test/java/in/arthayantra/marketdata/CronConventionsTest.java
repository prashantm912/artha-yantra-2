package in.arthayantra.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The B-9 cron-pitfall guard, machine-checked: every cron-based {@code @Scheduled} in the service
 * must be SIX-field Spring cron with the explicit Asia/Kolkata zone — {@code "0 5 * * * *"} fires
 * hourly, not daily, and a missing zone drifts with the host clock. (Phase 16 FAIL criterion.)
 */
class CronConventionsTest {

  private static final List<Class<?>> SCHEDULED_CLASSES =
      List.of(
          in.arthayantra.marketdata.kite.ticker.EodBackfillJob.class,
          in.arthayantra.marketdata.futures.ContinuousFuturesRoller.class,
          in.arthayantra.marketdata.instruments.InstrumentSyncScheduler.class,
          in.arthayantra.marketdata.feed.TickerSchedule.class,
          in.arthayantra.marketdata.options.OptionsSnapshotService.class,
          in.arthayantra.marketdata.futures.FuturesOiSnapshotService.class,
          in.arthayantra.marketdata.kite.session.SessionHealthProbe.class,
          in.arthayantra.marketdata.nse.preopen.PreOpenEquityScanService.class,
          in.arthayantra.marketdata.candles.CandlesConfig.CandleHousekeeping.class);

  @Test
  void everyCronScheduleIsSixFieldWithIstZone() {
    int cronsChecked = 0;
    for (Class<?> clazz : SCHEDULED_CLASSES) {
      for (Method method : clazz.getDeclaredMethods()) {
        Scheduled scheduled = method.getAnnotation(Scheduled.class);
        if (scheduled == null || scheduled.cron().isEmpty()) {
          continue;
        }
        cronsChecked++;
        assertThat(scheduled.cron().trim().split("\\s+"))
            .as("%s.%s must use SIX-field Spring cron", clazz.getSimpleName(), method.getName())
            .hasSize(6);
        assertThat(scheduled.zone())
            .as("%s.%s must pin zone=Asia/Kolkata", clazz.getSimpleName(), method.getName())
            .isEqualTo("Asia/Kolkata");
      }
    }
    assertThat(cronsChecked).as("the cron schedules exist").isGreaterThanOrEqualTo(4);
  }
}
