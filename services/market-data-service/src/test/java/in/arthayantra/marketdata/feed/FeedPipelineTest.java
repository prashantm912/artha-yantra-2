package in.arthayantra.marketdata.feed;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.arthayantra.marketdata.kite.MarketFeed;
import in.arthayantra.marketdata.kite.SessionGateway;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
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
}
