package in.arthayantra.marketdata.kite.ticker;

import in.arthayantra.marketdata.kite.RawTick;
import java.util.List;
import java.util.function.Consumer;

/**
 * The thin seam over the actual Kite WS connection — the SDK wrapper implements it for live, a
 * fake implements it in tests. Everything above (registry replay, breaker, gap scan) is plain
 * testable code.
 */
public interface TickerHandle {

  /** Opens the socket; callbacks fire on SDK threads. Throws on immediate failure. */
  void connect();

  /** Closes the socket. Idempotent. */
  void disconnect();

  /** Whether the socket is currently open. */
  boolean connected();

  /** Subscribes tokens and sets their tick mode. */
  void subscribe(List<Long> tokens, SubscriptionMode mode);

  /** Drops tokens. */
  void unsubscribe(List<Long> tokens);

  /** Registers the connect callback (fires on first connect AND every reconnect). */
  void onConnected(Runnable callback);

  /** Registers the disconnect callback. */
  void onDisconnected(Runnable callback);

  /** Registers the tick sink. */
  void onTicks(Consumer<List<RawTick>> callback);

  /** Registers the error sink (message only — tokens never appear in errors). */
  void onError(Consumer<String> callback);
}
