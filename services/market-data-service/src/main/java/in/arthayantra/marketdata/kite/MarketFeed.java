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

  /**
   * Registers a socket-state sink invoked with {@code true} on connect and {@code false} on
   * disconnect — the truth source behind {@code kite:ticker:status}. Live wires the WS callbacks;
   * mock reports connected-while-running. Default no-op so test fakes stay one-method.
   */
  default void onConnectionState(java.util.function.Consumer<Boolean> sink) {}
}
