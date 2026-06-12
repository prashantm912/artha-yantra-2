package in.arthayantra.marketdata.kite.ticker;

import in.arthayantra.marketdata.kite.InstrumentKey;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Pins the always-on index subscriptions (B-6 eviction top priority): NIFTY 50, NIFTY BANK and —
 * per FP-14 — INDIA VIX, an ordinary NSE index instrument with no special casing. Runs after the
 * bootstrap sync (ordered last) so the master can resolve tokens; unknown instruments are skipped
 * with a warning, never a startup failure.
 */
@Component
public class PinnedIndicesSubscriber {

  private static final Logger log = LoggerFactory.getLogger(PinnedIndicesSubscriber.class);

  private final SubscriptionRegistry registry;
  private final List<String> pinned;

  /** Wires the configured pin list ({@code EXCHANGE:TRADINGSYMBOL} comma-separated). */
  public PinnedIndicesSubscriber(
      SubscriptionRegistry registry,
      @Value("${artha.subscriptions.pinned-indices:NSE:NIFTY 50,NSE:NIFTY BANK,NSE:INDIA VIX}")
          List<String> pinned) {
    this.registry = registry;
    this.pinned = pinned;
  }

  /** Subscribes the pinned set; ordered after the bootstrap sync's ready-listener. */
  @EventListener(ApplicationReadyEvent.class)
  @Order(org.springframework.core.Ordered.LOWEST_PRECEDENCE)
  public void pinIndices() {
    ensurePinned();
  }

  /** Re-resolves pins after every instrument sync — startup-unknown indices land here. */
  @EventListener(in.arthayantra.marketdata.kite.InstrumentMasterUpdated.class)
  public void onMasterUpdated() {
    ensurePinned();
  }

  /** Idempotent pin pass — also callable after instrument syncs. */
  public void ensurePinned() {
    for (String entry : pinned) {
      int colon = entry.indexOf(':');
      if (colon <= 0) {
        log.warn("malformed pinned index entry '{}'", entry);
        continue;
      }
      InstrumentKey key =
          new InstrumentKey(entry.substring(0, colon).trim(), entry.substring(colon + 1).trim());
      try {
        registry.subscribe(
            "system-pinned", key, SubscriptionMode.QUOTE, SubscriptionPriority.PINNED_INDEX);
      } catch (Exception unknownYet) {
        log.warn("pinned index {} not resolvable yet: {}", key.canonical(), unknownYet.getMessage());
      }
    }
  }
}
