package in.arthayantra.marketdata.kite.ticker;

import in.arthayantra.marketdata.kite.InstrumentMasterUpdated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Restores persisted (non-pinned) ticker subscriptions into the in-memory {@link
 * SubscriptionRegistry} after a restart (B-6 durability). Replays on startup and after every
 * instrument sync — idempotent, mirroring {@link PinnedIndicesSubscriber} — so holds whose tokens
 * were unresolvable at boot land once the master is synced. The live ticker re-subscribes the
 * restored set on its next connect (it replays the whole registry on every {@code onConnected}).
 */
@Component
public class SubscriptionReplayer {

  private static final Logger log = LoggerFactory.getLogger(SubscriptionReplayer.class);

  private final SubscriptionStore store;
  private final SubscriptionRegistry registry;

  /** Wires the durable store + the in-memory registry. */
  public SubscriptionReplayer(SubscriptionStore store, SubscriptionRegistry registry) {
    this.store = store;
    this.registry = registry;
  }

  /** Replay once the context is up — ordered last so the master/token resolver is ready. */
  // Strictly BEFORE any listener that hydrates from the registry (OptionAtmPinner does). At
  // LOWEST_PRECEDENCE both tied, and a self-reconciling pinner that hydrated first would never see
  // the holds replay was about to restore — so yesterday's expired strikes could never be rolled off.
  @EventListener(ApplicationReadyEvent.class)
  @Order(Ordered.LOWEST_PRECEDENCE - 100)
  public void onStartup() {
    replay();
  }

  /** Re-attempt after each instrument sync — boot-unresolvable instruments resolve here. */
  @EventListener(InstrumentMasterUpdated.class)
  @Order(Ordered.LOWEST_PRECEDENCE - 100)
  public void onMasterUpdated() {
    replay();
  }

  /** Idempotent pass: re-subscribing an existing hold just overwrites it. */
  public void replay() {
    int restored = 0;
    for (SubscriptionStore.PersistedHold hold : store.all()) {
      try {
        registry.subscribe(hold.subscriber(), hold.key(), hold.mode(), hold.priority());
        restored++;
      } catch (RuntimeException notResolvableYet) {
        log.warn(
            "subscription replay skipped {} ({}): {}",
            hold.key().canonical(),
            hold.subscriber(),
            notResolvableYet.getMessage());
      }
    }
    if (restored > 0) {
      log.info("replayed {} persisted ticker subscriptions", restored);
    }
  }
}
