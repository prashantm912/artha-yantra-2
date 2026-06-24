package in.arthayantra.marketdata.kite.upstoxfeed;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.kite.RawTick;
import in.arthayantra.marketdata.kite.UpstoxLiveTickFeed;
import in.arthayantra.marketdata.kite.ticker.SubscriptionMode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * The Wave-U3 {@code TickerHandle} adapter is a THIN delegate: it forwards the {@code LiveTickerFeed}
 * supervisor's {@link in.arthayantra.marketdata.kite.ticker.TickerHandle} calls to the {@link
 * UpstoxLiveTickFeed} port (which owns the WS client + token↔key translation in {@code options}). This
 * pins the delegation — the Kite mode ladder collapses to one Upstox call, lifecycle maps 1:1, and the
 * tick/onConnected callbacks pass straight through.
 */
class UpstoxTickerHandleTest {

  /** A scriptable port capturing the handle's delegation. */
  static final class FakeFeed implements UpstoxLiveTickFeed {
    final List<List<Long>> subscribes = new ArrayList<>();
    final List<List<Long>> unsubscribes = new ArrayList<>();
    boolean started;
    boolean running;
    Consumer<List<RawTick>> ticksSink;
    Runnable onConnected;

    @Override
    public void start() {
      started = true;
      running = true;
    }

    @Override
    public void stop() {
      running = false;
    }

    @Override
    public boolean running() {
      return running;
    }

    @Override
    public void subscribe(List<Long> tokens) {
      subscribes.add(tokens);
    }

    @Override
    public void unsubscribe(List<Long> tokens) {
      unsubscribes.add(tokens);
    }

    @Override
    public void onTicks(Consumer<List<RawTick>> sink) {
      this.ticksSink = sink;
    }

    @Override
    public void onConnected(Runnable callback) {
      this.onConnected = callback;
    }
  }

  @Test
  void lifecycleDelegatesToThePort() {
    FakeFeed feed = new FakeFeed();
    UpstoxTickerHandle handle = new UpstoxTickerHandle(feed);

    handle.connect();
    assertThat(feed.started).isTrue();
    assertThat(handle.connected()).isTrue();

    handle.disconnect();
    assertThat(handle.connected()).isFalse();
  }

  @Test
  void subscribeForwardsTokensRegardlessOfKiteMode() {
    FakeFeed feed = new FakeFeed();
    UpstoxTickerHandle handle = new UpstoxTickerHandle(feed);

    handle.subscribe(List.of(1L, 2L), SubscriptionMode.FULL);
    handle.subscribe(List.of(3L), SubscriptionMode.LTP); // mode collapses inside the impl

    assertThat(feed.subscribes).containsExactly(List.of(1L, 2L), List.of(3L));
  }

  @Test
  void unsubscribeAndTickAndConnectedCallbacksPassThrough() {
    FakeFeed feed = new FakeFeed();
    UpstoxTickerHandle handle = new UpstoxTickerHandle(feed);
    List<RawTick> received = new ArrayList<>();
    boolean[] connectedFired = {false};

    handle.onTicks(received::addAll);
    handle.onConnected(() -> connectedFired[0] = true);
    handle.unsubscribe(List.of(9L));

    assertThat(feed.unsubscribes).containsExactly(List.of(9L));

    feed.onConnected.run();
    assertThat(connectedFired[0]).isTrue();

    feed.ticksSink.accept(
        List.of(new RawTick(1L, java.math.BigDecimal.ONE, 5L, null, java.time.Instant.EPOCH)));
    assertThat(received).hasSize(1);
  }
}
