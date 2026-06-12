package in.arthayantra.marketdata.feed;

import in.arthayantra.marketdata.kite.InstrumentKey;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Latest tick per instrument (A.7.1): in-memory for quote serving, mirrored to the Redis
 * {@code ticks:last} hash by the publisher.
 */
@Component
public class LastTickStore {

  private final Map<InstrumentKey, NormalizedTick> lastTicks = new ConcurrentHashMap<>();

  /** Records the newest tick for its instrument. */
  public void update(NormalizedTick tick) {
    lastTicks.put(new InstrumentKey(tick.exchange(), tick.tradingsymbol()), tick);
  }

  /** The latest tick for {@code key}, if any has arrived. */
  public Optional<NormalizedTick> latest(InstrumentKey key) {
    return Optional.ofNullable(lastTicks.get(key));
  }
}
