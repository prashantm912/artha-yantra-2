package in.arthayantra.marketdata.kite;

import java.time.Instant;
import java.util.Optional;

/**
 * When an instrument last ticked (B-6 gap detection). Implemented by the feed module over its
 * last-tick map — declared here so the kite module never depends on feed internals.
 */
public interface LastSeenProvider {

  /** Timestamp of the newest tick for {@code key}, when one has arrived. */
  Optional<Instant> lastSeen(InstrumentKey key);
}
