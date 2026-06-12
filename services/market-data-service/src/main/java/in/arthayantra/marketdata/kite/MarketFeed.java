package in.arthayantra.marketdata.kite;

/**
 * Port 2/5 (A.7a): the streaming tick source. Live = Kite WebSocket (Stage B, Phase 13); mock =
 * seeded deterministic random walk (Phase 7). Exactly one impl is bound — mock xor live.
 */
public interface MarketFeed {

  /** Starts delivering ticks to the listener. Idempotent. */
  void start(TickListener listener);

  /** Stops the feed. Idempotent. */
  void stop();

  /** Whether the feed is currently delivering. */
  boolean running();
}
