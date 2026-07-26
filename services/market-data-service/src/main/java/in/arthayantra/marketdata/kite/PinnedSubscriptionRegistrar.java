package in.arthayantra.marketdata.kite;

/**
 * Exposed port for system components that reconcile fixed quote-mode subscriptions.
 *
 * <p><b>These holds are RESEARCH capture and rank BELOW live trading.</b> The registry evicts
 * lower-priority holds to make room at its cap, so a hold taken here must never be able to displace a
 * {@code STRATEGY} subscription the live engine depends on. Implementations therefore subscribe at
 * {@code SPECULATIVE} — the first tier to yield. Do not "upgrade" this to {@code PINNED_INDEX} to make
 * option capture more reliable: that inverts the priority order and trades a live signal for a
 * research bar.
 */
public interface PinnedSubscriptionRegistrar {

  /** Registers one pinned quote-mode hold. */
  void subscribe(String subscriber, InstrumentKey key);

  /** Releases one pinned hold. */
  void unsubscribe(String subscriber, InstrumentKey key);

  /**
   * Keys this subscriber currently holds, as the REGISTRY sees them.
   *
   * <p>A self-reconciling pinner must hydrate from this on startup, not from an empty in-memory set.
   * These holds are persisted and replayed across a restart (the registry persists every
   * non-{@code PINNED_INDEX} hold), so a fresh process that trusted its own empty set would never
   * learn about the strikes it restored — and could therefore never roll an expired one off.
   */
  java.util.Set<InstrumentKey> heldBy(String subscriber);
}
