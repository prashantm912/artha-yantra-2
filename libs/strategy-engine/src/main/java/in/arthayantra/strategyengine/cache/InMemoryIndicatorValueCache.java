package in.arthayantra.strategyengine.cache;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Bounded-LRU default for {@link IndicatorValueCache} (thread-safe, synchronized). */
public final class InMemoryIndicatorValueCache implements IndicatorValueCache {

  private final Map<Key, BigDecimal> values;

  /** With a maximum entry count. */
  public InMemoryIndicatorValueCache(int maxEntries) {
    this.values =
        new LinkedHashMap<>(128, 0.75f, true) {
          @Override
          protected boolean removeEldestEntry(Map.Entry<Key, BigDecimal> eldest) {
            return size() > maxEntries;
          }
        };
  }

  @Override
  public synchronized Optional<BigDecimal> get(Key key) {
    return Optional.ofNullable(values.get(key));
  }

  @Override
  public synchronized void put(Key key, BigDecimal value) {
    if (value != null) {
      values.put(key, value);
    }
  }
}
