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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * Feed-lifecycle + status-surface behaviour. The B-13 ticker status is callback-driven (CONNECTING
 * seed, never an eager CONNECTED); the per-tick fan-out isolates publish/listener failures so candle
 * building never rides Redis publish success; and (task_ff56fd20) every canonical status write goes
 * through the serialized, non-blocking {@link FeedStatusWriter} so no Redis I/O ever runs under the
 * lifecycle lock — including the connection-state callback, which fires SYNCHRONOUSLY inside
 * {@code marketFeed.start()}/{@code stop()} (as {@code MockMarketFeed}/{@code UpstoxMarketFeedClient}
 * do) and would otherwise be a Redis call on a lock-held path.
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

  /** A real (serialized, async) status writer backed by the given mock Redis ops. */
  private static FeedStatusWriter statusWriter(ValueOperations<String, String> ops) {
    return new FeedStatusWriter(redisWithOps(ops), new SimpleMeterRegistry());
  }

  @Test
  @SuppressWarnings("unchecked")
  void tickerStatusIsSeededConnectingAndDrivenByTheSocketCallbacks() throws Exception {
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    FeedStatusWriter writer = statusWriter(ops);
    MarketFeed feed = mock(MarketFeed.class); // start() is a no-op mock: no synchronous callback
    SessionGateway session = mock(SessionGateway.class);
    when(session.statusLabel()).thenReturn("CONNECTED");
    FeedPipeline pipeline =
        new FeedPipeline(
            feed, session, mock(IngressQueue.class), mock(TickNormalizer.class),
            mock(RedisTickPublisher.class), mock(LastTickStore.class), writer, List.of(), false);
    try {
      pipeline.startFeed();
      assertThat(writer.awaitDrained(2_000)).isTrue();

      verify(ops).set(FeedPipeline.TICKER_STATUS_KEY, "CONNECTING");
      verify(ops, never()).set(FeedPipeline.TICKER_STATUS_KEY, "CONNECTED");

      ArgumentCaptor<Consumer<Boolean>> sink = ArgumentCaptor.forClass(Consumer.class);
      verify(feed).onConnectionState(sink.capture());
      sink.getValue().accept(true);
      assertThat(writer.awaitDrained(2_000)).isTrue();
      verify(ops).set(FeedPipeline.TICKER_STATUS_KEY, "CONNECTED");
      sink.getValue().accept(false);
      assertThat(writer.awaitDrained(2_000)).isTrue();
      verify(ops).set(FeedPipeline.TICKER_STATUS_KEY, "DISCONNECTED");
    } finally {
      pipeline.stop(); // stops the normalizer spin on the mock ingress queue
      writer.drainAndShutdown(1_000);
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
            statusWriter(mock(ValueOperations.class)), List.of(candleBuilder), false);

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
            statusWriter(mock(ValueOperations.class)), List.of(failing, healthy), false);

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
            statusWriter(mock(ValueOperations.class)), List.of(), false);
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

  /**
   * Ordering guarantee (task_ff56fd20): the connection-state callback fires SYNCHRONOUSLY inside
   * {@code marketFeed.start()} (MockMarketFeed / UpstoxMarketFeedClient), so a CONNECTED is decided
   * DURING the start — before the start method returns. Because {@code startUnderLock} seeds
   * CONNECTING BEFORE calling {@code marketFeed.start()} and every write rides the single-FIFO
   * writer, the terminal ticker status is CONNECTED, not a stale CONNECTING. (The earlier
   * "seed-after-unlock" attempt failed exactly here: it wrote CONNECTING AFTER start(), clobbering
   * the synchronous CONNECTED.)
   */
  @Test
  @SuppressWarnings("unchecked")
  void aSynchronousConnectedCallbackDuringStartWinsOverTheConnectingSeed() throws Exception {
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    Map<String, String> lastWritten = new ConcurrentHashMap<>();
    doAnswer(
            inv -> {
              lastWritten.put(inv.getArgument(0), inv.getArgument(1));
              return null;
            })
        .when(ops)
        .set(any(), any());
    FeedStatusWriter writer = statusWriter(ops);

    AtomicReference<Consumer<Boolean>> sink = new AtomicReference<>();
    MarketFeed feed = mock(MarketFeed.class);
    doAnswer(
            inv -> {
              sink.set(inv.getArgument(0));
              return null;
            })
        .when(feed)
        .onConnectionState(any());
    doAnswer(
            inv -> {
              sink.get().accept(true); // fire CONNECTED SYNCHRONOUSLY inside start()
              return null;
            })
        .when(feed)
        .start(any());
    SessionGateway session = mock(SessionGateway.class);
    when(session.statusLabel()).thenReturn("CONNECTED");

    FeedPipeline pipeline =
        new FeedPipeline(
            feed, session, mock(IngressQueue.class), mock(TickNormalizer.class),
            mock(RedisTickPublisher.class), mock(LastTickStore.class), writer, List.of(), false);
    try {
      pipeline.startFeed();
      assertThat(writer.awaitDrained(2_000)).isTrue();

      assertThat(lastWritten.get(FeedPipeline.TICKER_STATUS_KEY))
          .as("a synchronous CONNECTED (fired during start) must win over the CONNECTING seed")
          .isEqualTo("CONNECTED");
    } finally {
      pipeline.stop();
      writer.drainAndShutdown(1_000);
    }
  }

  /**
   * Ordering guarantee (task_ff56fd20, the hang-start→stop terminal state): with the feed firing its
   * connection-state callback synchronously on both start() (CONNECTED) and stop() (DISCONNECTED),
   * the single-FIFO writer applies CONNECTING → CONNECTED → DISCONNECTED in order, so a start
   * followed by a stop leaves the ticker status DISCONNECTED — never a stale CONNECTING left behind
   * by an out-of-order deferred write.
   */
  @Test
  @SuppressWarnings("unchecked")
  void aStartThenStopLeavesTheTickerStatusDisconnectedNotStaleConnecting() throws Exception {
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    Map<String, String> lastWritten = new ConcurrentHashMap<>();
    doAnswer(
            inv -> {
              lastWritten.put(inv.getArgument(0), inv.getArgument(1));
              return null;
            })
        .when(ops)
        .set(any(), any());
    FeedStatusWriter writer = statusWriter(ops);

    AtomicReference<Consumer<Boolean>> sink = new AtomicReference<>();
    MarketFeed feed = mock(MarketFeed.class);
    doAnswer(
            inv -> {
              sink.set(inv.getArgument(0));
              return null;
            })
        .when(feed)
        .onConnectionState(any());
    doAnswer(
            inv -> {
              sink.get().accept(true); // CONNECTED synchronously during start()
              return null;
            })
        .when(feed)
        .start(any());
    doAnswer(
            inv -> {
              sink.get().accept(false); // DISCONNECTED synchronously during stop()
              return null;
            })
        .when(feed)
        .stop();
    SessionGateway session = mock(SessionGateway.class);
    when(session.statusLabel()).thenReturn("CONNECTED");

    FeedPipeline pipeline =
        new FeedPipeline(
            feed, session, mock(IngressQueue.class), mock(TickNormalizer.class),
            mock(RedisTickPublisher.class), mock(LastTickStore.class), writer, List.of(), false);
    try {
      pipeline.startFeed(); // enqueues CONNECTING, then the synchronous callback CONNECTED
      pipeline.stop(); // synchronous callback DISCONNECTED, then the explicit DISCONNECTED
      assertThat(writer.awaitDrained(2_000)).isTrue();

      assertThat(lastWritten.get(FeedPipeline.TICKER_STATUS_KEY))
          .as("after start-then-stop the terminal ticker status is DISCONNECTED, never a stale"
              + " CONNECTING")
          .isEqualTo("DISCONNECTED");
    } finally {
      writer.drainAndShutdown(1_000);
    }
  }

  /**
   * Non-blocking guarantee (task_ff56fd20): a hung loopback Redis (bounded only by the ~60s Lettuce
   * command timeout) parks the WRITER thread, not any lifecycle path — every status write is a
   * non-blocking enqueue. So while the writer is stuck in a Redis {@code set}, a concurrent
   * {@code stop()} (the shape of an F10 self-heal transition) still completes promptly. Against the
   * pre-fix code (Redis {@code set} on the lock-held path, incl. the synchronous callback) the
   * lifecycle transition would block for the whole hang window.
   */
  @Test
  @SuppressWarnings("unchecked")
  void aHungRedisStatusWriteNeverBlocksLifecycleTransition() throws Exception {
    CountDownLatch firstWriteEntered = new CountDownLatch(1);
    CountDownLatch releaseRedis = new CountDownLatch(1);

    ValueOperations<String, String> ops = mock(ValueOperations.class);
    doAnswer(
            inv -> {
              firstWriteEntered.countDown();
              releaseRedis.await(); // the writer thread parks here, as if Redis hung
              return null;
            })
        .when(ops)
        .set(any(), any());
    FeedStatusWriter writer = statusWriter(ops);

    MarketFeed feed = mock(MarketFeed.class);
    SessionGateway session = mock(SessionGateway.class);
    when(session.statusLabel()).thenReturn("CONNECTED");
    FeedPipeline pipeline =
        new FeedPipeline(
            feed, session, mock(IngressQueue.class), mock(TickNormalizer.class),
            mock(RedisTickPublisher.class), mock(LastTickStore.class), writer, List.of(), false);
    try {
      pipeline.startFeed(); // enqueues; the writer thread parks in the first (hung) Redis set
      assertThat(firstWriteEntered.await(2, TimeUnit.SECONDS))
          .as("the writer thread reached the (now hung) Redis set")
          .isTrue();

      Thread lifecycle = new Thread(pipeline::stop, "lifecycle");
      lifecycle.start();
      lifecycle.join(1_000);

      assertThat(lifecycle.isAlive())
          .as("stop() completed while the status writer is hung on Redis")
          .isFalse();
      assertThat(pipeline.isRunning()).isFalse();
    } finally {
      releaseRedis.countDown(); // unpark the writer thread
      writer.drainAndShutdown(1_000);
    }
  }
}
