package in.arthayantra.marketdata.kite.ticker;

import in.arthayantra.marketdata.kite.InstrumentKey;
import java.util.ArrayList;
import java.util.List;

/** Test double: an in-memory {@link SubscriptionStore} capturing persisted holds by (subscriber, key). */
class InMemorySubscriptionStore implements SubscriptionStore {

  private final List<PersistedHold> holds = new ArrayList<>();

  @Override
  public void put(
      String subscriber, InstrumentKey key, SubscriptionMode mode, SubscriptionPriority priority) {
    holds.removeIf(h -> h.subscriber().equals(subscriber) && h.key().equals(key));
    holds.add(new PersistedHold(subscriber, key, mode, priority));
  }

  @Override
  public void remove(String subscriber, InstrumentKey key) {
    holds.removeIf(h -> h.subscriber().equals(subscriber) && h.key().equals(key));
  }

  @Override
  public List<PersistedHold> all() {
    return List.copyOf(holds);
  }
}
