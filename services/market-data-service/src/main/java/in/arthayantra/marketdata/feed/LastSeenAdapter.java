package in.arthayantra.marketdata.feed;

import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.LastSeenProvider;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Last-tick-map-backed implementation of the kite-side gap-detection seam (Phase 13). */
@Component
public class LastSeenAdapter implements LastSeenProvider {

  private final LastTickStore lastTickStore;

  /** Wires the in-memory last-tick map. */
  public LastSeenAdapter(LastTickStore lastTickStore) {
    this.lastTickStore = lastTickStore;
  }

  @Override
  public Optional<Instant> lastSeen(InstrumentKey key) {
    return lastTickStore.latest(key).map(tick -> tick.timestamp().toInstant());
  }
}
