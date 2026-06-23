package in.arthayantra.marketdata.nse.analytics;

import org.springframework.context.annotation.Profile;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Refreshes the live {@link SectorIndexWatch} on a fixed cadence (live profile only). Warms once at
 * startup so the Sector Stats cards appear promptly, then polls the niftyindices feed.
 */
@Component
@Profile("live")
public class SectorIndexWatchScheduler {

  private final SectorIndexWatch watch;

  public SectorIndexWatchScheduler(SectorIndexWatch watch) {
    this.watch = watch;
  }

  @EventListener(ContextRefreshedEvent.class)
  public void warmOnStartup() {
    watch.refresh();
  }

  @Scheduled(fixedDelayString = "${artha.nse.index-watch-interval-ms:60000}", initialDelay = 60_000)
  public void poll() {
    watch.refresh();
  }
}
