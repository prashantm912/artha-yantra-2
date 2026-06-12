package in.arthayantra.marketdata.kite.ticker;

/**
 * Eviction priority under the 3,000-token cap (B-6): pinned indices outrank strategy universes
 * outrank UI watchlists outrank speculative subscriptions. Lower ordinal = survives longer.
 */
public enum SubscriptionPriority {
  PINNED_INDEX,
  STRATEGY,
  UI,
  SPECULATIVE
}
