package in.arthayantra.marketdata.kite.ticker;

import in.arthayantra.marketdata.kite.InstrumentKey;
import java.util.List;

/**
 * Durable record of non-pinned ticker subscription holds (B-6 restart durability). The {@link
 * SubscriptionRegistry} is in-memory, so without this a market-data restart silently drops every
 * strategy/UI subscription — only the config-driven pinned indices+futures re-register on boot.
 * Holds are keyed by {@code (subscriber, instrument)}; {@code PINNED_INDEX} holds are never
 * persisted (they self-restore from config). {@link SubscriptionReplayer} replays {@link #all()}.
 */
public interface SubscriptionStore {

  /** Persists (or overwrites) one subscriber's hold on an instrument. */
  void put(
      String subscriber, InstrumentKey key, SubscriptionMode mode, SubscriptionPriority priority);

  /** Removes one subscriber's hold; a no-op if it was never persisted. */
  void remove(String subscriber, InstrumentKey key);

  /** Every persisted hold, for replay on startup. */
  List<PersistedHold> all();

  /** One persisted hold. */
  record PersistedHold(
      String subscriber, InstrumentKey key, SubscriptionMode mode, SubscriptionPriority priority) {}

  /** A store that persists nothing — the default for the legacy registry ctor + unit tests. */
  SubscriptionStore NOOP =
      new SubscriptionStore() {
        @Override
        public void put(String s, InstrumentKey k, SubscriptionMode m, SubscriptionPriority p) {
          // no-op
        }

        @Override
        public void remove(String s, InstrumentKey k) {
          // no-op
        }

        @Override
        public List<PersistedHold> all() {
          return List.of();
        }
      };
}
