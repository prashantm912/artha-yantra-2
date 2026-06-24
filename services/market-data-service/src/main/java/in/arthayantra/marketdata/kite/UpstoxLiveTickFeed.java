package in.arthayantra.marketdata.kite;

import java.util.List;
import java.util.function.Consumer;

/**
 * Port for the Wave-U3 direct-Upstox v3 WS tick transport — declared in {@code kite} (the module that
 * owns the {@code TickerHandle}/{@code SubscriptionRegistry} live-feed machinery) and implemented in
 * {@code options} (which wraps the {@code upstox} WS client + the token→key map). The direction stays
 * {@code options → kite}, mirroring {@link InstrumentTokenResolver}; this is the seam that keeps the
 * Upstox WS feed wiring intra-{@code kite} WITHOUT a {@code kite → upstox} edge — which would close a
 * Modulith cycle, because {@code upstox} already reaches {@code kite} transitively
 * ({@code upstox → candles → feed → instruments → kite}).
 *
 * <p>Speaks entirely in {@code kite} domain terms (Kite tokens + {@link RawTick}): the impl does ALL
 * the token↔Upstox-{@code instrument_key} translation internally, so the {@code kite.upstoxfeed}
 * {@code TickerHandle} adapter that drives the existing {@code LiveTickerFeed} supervisor stays a thin,
 * domain-only delegate. A token with no derivable Upstox key is silently skipped by the impl (it does
 * not stream over Upstox).
 */
public interface UpstoxLiveTickFeed {

  /** Authorizes + opens the WS and starts reconnect supervision. Idempotent. */
  void start();

  /** Closes the WS and stops supervision. Idempotent. */
  void stop();

  /** Whether the WS is currently open. */
  boolean running();

  /** Subscribes the given Kite tokens (translated to Upstox keys internally). */
  void subscribe(List<Long> tokens);

  /** Unsubscribes the given Kite tokens. */
  void unsubscribe(List<Long> tokens);

  /** Registers the tick sink — ticks are re-keyed to Kite tokens (the shared sink shape). */
  void onTicks(Consumer<List<RawTick>> sink);

  /** Registers the callback fired after EVERY socket open (first connect + each reconnect). */
  void onConnected(Runnable callback);
}
