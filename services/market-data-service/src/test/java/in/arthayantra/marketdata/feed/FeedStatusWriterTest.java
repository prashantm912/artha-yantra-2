package in.arthayantra.marketdata.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * The serialized, non-blocking feed-status writer (task_ff56fd20): enqueues are applied to Redis in
 * FIFO order by a single background thread, a failing write is swallowed (never stops later writes),
 * and a stale lower-seq event can never regress a newer status for the same key.
 */
class FeedStatusWriterTest {

  @SuppressWarnings("unchecked")
  private static FeedStatusWriter writerWithOps(ValueOperations<String, String> ops) {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    when(redis.opsForValue()).thenReturn(ops);
    return new FeedStatusWriter(redis, new SimpleMeterRegistry());
  }

  @Test
  @SuppressWarnings("unchecked")
  void enqueuedWritesAreAppliedToRedisInFifoOrder() throws Exception {
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    FeedStatusWriter writer = writerWithOps(ops);
    try {
      writer.session("LOGGED_IN");
      writer.ticker("CONNECTING");
      writer.ticker("CONNECTED");
      assertThat(writer.awaitDrained(2_000)).isTrue();

      InOrder inOrder = inOrder(ops);
      inOrder.verify(ops).set(FeedPipeline.SESSION_STATUS_KEY, "LOGGED_IN");
      inOrder.verify(ops).set(FeedPipeline.TICKER_STATUS_KEY, "CONNECTING");
      inOrder.verify(ops).set(FeedPipeline.TICKER_STATUS_KEY, "CONNECTED");
    } finally {
      writer.drainAndShutdown(1_000);
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void failingWriteIsSwallowedAndLaterWritesStillApply() throws Exception {
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    doThrow(new IllegalStateException("redis down"))
        .when(ops)
        .set(FeedPipeline.TICKER_STATUS_KEY, "CONNECTING");
    FeedStatusWriter writer = writerWithOps(ops);
    try {
      writer.ticker("CONNECTING"); // throws inside the writer thread — must be swallowed
      writer.ticker("CONNECTED"); // the writer thread must survive and apply this
      assertThat(writer.awaitDrained(2_000)).isTrue();

      verify(ops).set(FeedPipeline.TICKER_STATUS_KEY, "CONNECTED");
    } finally {
      writer.drainAndShutdown(1_000);
    }
  }

  /**
   * The seq gate applied directly (deterministic): a lower-seq event arriving AFTER a higher-seq one
   * for the same key — the out-of-order shape two racing producers can produce between stamping and
   * enqueuing — is dropped, never regressing the newer status.
   */
  @Test
  @SuppressWarnings("unchecked")
  void lowerSeqEventNeverRegressesNewerAppliedStatus() {
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    FeedStatusWriter writer = writerWithOps(ops);
    try {
      writer.apply(FeedPipeline.TICKER_STATUS_KEY, "CONNECTED", 5L); // newest applied
      writer.apply(FeedPipeline.TICKER_STATUS_KEY, "DISCONNECTED", 3L); // stale, lower seq → dropped

      verify(ops).set(FeedPipeline.TICKER_STATUS_KEY, "CONNECTED");
      verify(ops, never()).set(FeedPipeline.TICKER_STATUS_KEY, "DISCONNECTED");
    } finally {
      writer.drainAndShutdown(1_000);
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void drainAndShutdownFlushesQueuedWritesBeforeStopping() throws Exception {
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    FeedStatusWriter writer = writerWithOps(ops);
    writer.ticker("CONNECTING");
    writer.ticker("DISCONNECTED");

    writer.drainAndShutdown(2_000);

    verify(ops).set(FeedPipeline.TICKER_STATUS_KEY, "CONNECTING");
    verify(ops).set(FeedPipeline.TICKER_STATUS_KEY, "DISCONNECTED");
  }
}
