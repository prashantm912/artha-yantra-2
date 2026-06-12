package in.arthayantra.marketdata.feed;

/**
 * In-process consumers of normalized ticks (the Phase-10 candle builder is the first). Invoked on
 * the single-writer normalizer thread — implementations must be fast and never block on IO they
 * cannot bound.
 */
public interface NormalizedTickListener {

  /** One normalized tick, post-publish. */
  void onNormalizedTick(NormalizedTick tick);
}
