package in.arthayantra.marketdata.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.arthayantra.marketdata.kite.MarketFeed;
import in.arthayantra.marketdata.kite.SessionGateway;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * Audit fixes ticker-status-connected-lie + tick-pipeline-redis-coupled-bar-loss: the B-13 ticker
 * status is callback-driven (CONNECTING seed, never an eager CONNECTED), and the per-tick fan-out
 * isolates publish/listener failures so candle building never rides Redis publish success.
 */
class FeedPipelineTest {

  private static final NormalizedTick TICK =
      new NormalizedTick(
          "NSE", "NIFTY 50", new BigDecimal("25000.00"), 100L, null,
          OffsetDateTime.parse("2026-07-03T11:00:00+05:30"), 1L);

  @SuppressWarnings("unchecked")
  private static StringRedisTemplate redisWithOps(ValueOperations<String, String> ops) {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    when(redis.opsForValue()).thenReturn(ops);
    return redis;
  }

  @Test
  @SuppressWarnings("unchecked")
  void tickerStatusIsSeededConnectingAndDrivenByTheSocketCallbacks() {
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    MarketFeed feed = mock(MarketFeed.class);
    SessionGateway session = mock(SessionGateway.class);
    when(session.statusLabel()).thenReturn("CONNECTED");
    FeedPipeline pipeline =
        new FeedPipeline(
            feed, session, mock(IngressQueue.class), mock(TickNormalizer.class),
            mock(RedisTickPublisher.class), mock(LastTickStore.class), redisWithOps(ops),
            List.of(), false);
    try {
      pipeline.startFeed();

      verify(ops).set(FeedPipeline.TICKER_STATUS_KEY, "CONNECTING");
      verify(ops, never()).set(FeedPipeline.TICKER_STATUS_KEY, "CONNECTED");

      ArgumentCaptor<Consumer<Boolean>> sink = ArgumentCaptor.forClass(Consumer.class);
      verify(feed).onConnectionState(sink.capture());
      sink.getValue().accept(true);
      verify(ops).set(FeedPipeline.TICKER_STATUS_KEY, "CONNECTED");
      sink.getValue().accept(false);
      verify(ops).set(FeedPipeline.TICKER_STATUS_KEY, "DISCONNECTED");
    } finally {
      pipeline.stop();
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void aRedisPublishFailureNeverStarvesTheCandleBuildingListeners() {
    RedisTickPublisher publisher = mock(RedisTickPublisher.class);
    doThrow(new IllegalStateException("redis down")).when(publisher).publish(any());
    NormalizedTickListener candleBuilder = mock(NormalizedTickListener.class);
    LastTickStore lastTickStore = mock(LastTickStore.class);
    FeedPipeline pipeline =
        new FeedPipeline(
            mock(MarketFeed.class), mock(SessionGateway.class), mock(IngressQueue.class),
            mock(TickNormalizer.class), publisher, lastTickStore,
            redisWithOps(mock(ValueOperations.class)), List.of(candleBuilder), false);

    pipeline.fanOut(TICK);

    verify(lastTickStore).update(TICK);
    verify(candleBuilder).onNormalizedTick(TICK);
  }

  @Test
  @SuppressWarnings("unchecked")
  void oneFailingListenerNeverStarvesTheNext() {
    NormalizedTickListener failing = mock(NormalizedTickListener.class);
    doThrow(new IllegalStateException("boom")).when(failing).onNormalizedTick(any());
    NormalizedTickListener healthy = mock(NormalizedTickListener.class);
    FeedPipeline pipeline =
        new FeedPipeline(
            mock(MarketFeed.class), mock(SessionGateway.class), mock(IngressQueue.class),
            mock(TickNormalizer.class), mock(RedisTickPublisher.class), mock(LastTickStore.class),
            redisWithOps(mock(ValueOperations.class)), List.of(failing, healthy), false);

    pipeline.fanOut(TICK);

    verify(healthy).onNormalizedTick(TICK);
  }

  /**
   * BEJ-01 regression: since the scheduler split, {@code FeedWatchdog.restartFeed()} runs on the
   * monitor thread while the morning start runs on the default thread, so the two lifecycle paths can
   * now interleave — pre-split they shared one scheduler thread and never could. The lifecycle lock
   * must make lifecycle transitions mutually exclusive: a {@code startFeed} racing a {@code restart}
   * must NOT start the feed while the restart's {@code stop()} is mid-flight (that overlap can leak a
   * second, un-interruptible {@code tick-normalizer}, breaking the single-writer guarantee).
   *
   * <p>Deterministic (not a probabilistic hammer): the restart is parked precisely inside
   * {@code marketFeed.stop()} — after {@code running} has been flipped false, which is exactly the
   * window a lock-free {@code startFeed} would slip through — and only then does the racing
   * {@code startFeed} run. With the lock it blocks on the lock and cannot reach
   * {@code marketFeed.start()}; without it, it starts the feed concurrently and the probe trips.
   */
  @Test
  @SuppressWarnings("unchecked")
  void concurrentStartAndRestartNeverOverlapLifecycleTransitions() throws Exception {
    AtomicBoolean insideRestartStop = new AtomicBoolean(false);
    AtomicBoolean startedDuringStop = new AtomicBoolean(false);
    AtomicBoolean parkNextStop = new AtomicBoolean(false);
    CountDownLatch enteredStop = new CountDownLatch(1);
    CountDownLatch resumeStop = new CountDownLatch(1);

    MarketFeed feed = mock(MarketFeed.class);
    doAnswer(
            inv -> {
              if (insideRestartStop.get()) {
                startedDuringStop.set(true); // a start ran while a stop() was mid-flight
              }
              return null;
            })
        .when(feed)
        .start(any());
    doAnswer(
            inv -> {
              if (parkNextStop.compareAndSet(true, false)) { // only the restart's stop() parks
                insideRestartStop.set(true);
                enteredStop.countDown();
                resumeStop.await();
                insideRestartStop.set(false);
              }
              return null;
            })
        .when(feed)
        .stop();

    SessionGateway session = mock(SessionGateway.class);
    when(session.statusLabel()).thenReturn("CONNECTED");
    FeedPipeline pipeline =
        new FeedPipeline(
            feed, session, new IngressQueue(new SimpleMeterRegistry()), mock(TickNormalizer.class),
            mock(RedisTickPublisher.class), mock(LastTickStore.class),
            redisWithOps(mock(ValueOperations.class)), List.of(), false);
    try {
      pipeline.startFeed(); // baseline: running, one normalizer (marketFeed.start #1, not in a stop)
      parkNextStop.set(true); // arm the restart's stop() to park mid-flight

      Thread restarter = new Thread(pipeline::restartFeed, "restarter");
      restarter.start();
      assertThat(enteredStop.await(2, TimeUnit.SECONDS))
          .as("the restart reached marketFeed.stop() and parked (running is now false)")
          .isTrue();

      Thread starter = new Thread(pipeline::startFeed, "starter");
      starter.start();
      starter.join(500); // bounded: it finishes (no lock) or is blocked on the lifecycle lock

      assertThat(startedDuringStop.get())
          .as("startFeed must not start the feed while a restart's stop() is mid-flight")
          .isFalse();

      resumeStop.countDown();
      restarter.join(2000);
      starter.join(2000);
      assertThat(pipeline.isRunning()).as("the feed is running after the restart settles").isTrue();
    } finally {
      resumeStop.countDown(); // never leave a thread parked, even on a failure path
      pipeline.stop();
    }
  }
}
