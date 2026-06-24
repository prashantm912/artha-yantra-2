package in.arthayantra.marketdata.kite.upstoxfeed;

import in.arthayantra.marketdata.kite.RawTick;
import in.arthayantra.marketdata.kite.UpstoxLiveTickFeed;
import in.arthayantra.marketdata.kite.ticker.SubscriptionMode;
import in.arthayantra.marketdata.kite.ticker.TickerHandle;
import java.util.List;
import java.util.function.Consumer;

/**
 * Upstox-backed {@link TickerHandle} (Wave U3) — a THIN delegate that lets the EXISTING {@code
 * LiveTickerFeed} supervisor (registry replay, the {@code kite-ticker} breaker, reconnect counting,
 * post-reconnect gap-heal, {@code FeedRearm}) drive the direct-Upstox v3 WS feed UNCHANGED. It adapts
 * the {@link TickerHandle} seam to the {@link UpstoxLiveTickFeed} port (implemented in {@code options},
 * which owns the WS client + the token↔Upstox-{@code instrument_key} translation), so this class stays
 * entirely in {@code kite} domain terms — no {@code upstox} import, no {@code kite → upstox} edge (that
 * would close a Modulith cycle).
 *
 * <p>The Kite {@code LTP/QUOTE/FULL} {@link SubscriptionMode} ladder is dropped on the way through:
 * the port subscribes Upstox's single tradeable {@code full} mode (LTP + volume + OI), which is all the
 * tick sink needs. Ticks arrive already re-keyed to Kite tokens, flowing into the SAME token-keyed sink
 * as the Kite feed (candles/charts/signals stay source-agnostic).
 */
public final class UpstoxTickerHandle implements TickerHandle {

  private final UpstoxLiveTickFeed feed;

  public UpstoxTickerHandle(UpstoxLiveTickFeed feed) {
    this.feed = feed;
  }

  @Override
  public void connect() {
    feed.start();
  }

  @Override
  public void disconnect() {
    feed.stop();
  }

  @Override
  public boolean connected() {
    return feed.running();
  }

  @Override
  public void subscribe(List<Long> tokens, SubscriptionMode mode) {
    feed.subscribe(tokens); // mode collapses to Upstox "full" inside the impl
  }

  @Override
  public void unsubscribe(List<Long> tokens) {
    feed.unsubscribe(tokens);
  }

  @Override
  public void onConnected(Runnable callback) {
    // LiveTickerFeed registers ONE onConnected callback (replay registry + count reconnects + gap
    // scan). The port fires it after EVERY socket open — first connect AND each reconnect — matching
    // the Kite SDK semantics the supervisor was built for (its own connectedBefore flag distinguishes
    // first-connect from a reconnect for the counter + gap-heal).
    feed.onConnected(callback);
  }

  @Override
  public void onDisconnected(Runnable callback) {
    // The port owns its own reconnect loop; a transient drop is not surfaced to the supervisor (it
    // would otherwise tear down state the impl is about to restore). No-op by design.
  }

  @Override
  public void onTicks(Consumer<List<RawTick>> callback) {
    feed.onTicks(callback);
  }

  @Override
  public void onError(Consumer<String> callback) {
    // Errors are logged inside the Upstox client; nothing actionable to surface to the supervisor.
  }
}
