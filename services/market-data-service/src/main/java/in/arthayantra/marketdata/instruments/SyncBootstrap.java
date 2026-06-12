package in.arthayantra.marketdata.instruments;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * First-boot bootstrap: an empty instrument master under the mock profile syncs inline from the
 * fixture dump so the feed pipeline resolves tokens from the very first `ay up`. Live never
 * bootstraps here — the day's token may not exist before the morning ritual; the 08:30 scheduler
 * (Phase 16) owns live syncs.
 */
@Component
@ConditionalOnProperty(name = "artha.instruments.bootstrap-sync", havingValue = "true", matchIfMissing = true)
public class SyncBootstrap {

  private static final Logger log = LoggerFactory.getLogger(SyncBootstrap.class);

  private final InstrumentRepository repository;
  private final InstrumentSyncService syncService;
  private final Environment environment;

  /** Wires the bootstrap collaborators. */
  public SyncBootstrap(
      InstrumentRepository repository, InstrumentSyncService syncService, Environment environment) {
    this.repository = repository;
    this.syncService = syncService;
    this.environment = environment;
  }

  /** Runs once the app is ready; inline (not async) so mock boots deterministically. */
  @EventListener(ApplicationReadyEvent.class)
  public void bootstrapIfEmpty() {
    if (environment.matchesProfiles("live")) {
      return;
    }
    if (repository.countActive() == 0) {
      log.info("instrument master empty - running bootstrap sync from the fixture dump");
      syncService.runSync();
    }
  }
}
