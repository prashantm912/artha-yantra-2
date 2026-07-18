package in.arthayantra.marketdata.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * The serialized, non-blocking feed-status writer (task_ff56fd20): a single background thread owns
 * the Redis {@code set}; each key coalesces to its LATEST published value so the newest ALWAYS wins
 * and is never dropped under saturation; a failing write is swallowed; and a stale lower-seq event
 * can never regress a newer status for the same key.
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
  void appliesEachStatusInOrderWhenTheWriterKeepsUp() throws Exception {
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    FeedStatusWriter writer = writerWithOps(ops);
    try {
      // Drain between each so the writer applies every value (no coalescing when it keeps up).
      writer.session("LOGGED_IN");
      assertThat(writer.awaitDrained(2_000)).isTrue();
      writer.ticker("CONNECTING");
      assertThat(writer.awaitDrained(2_000)).isTrue();
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
      writer.ticker("CONNECTING"); // applied on the writer thread — throws, must be swallowed
      assertThat(writer.awaitDrained(2_000)).isTrue();
      writer.ticker("CONNECTED"); // the writer thread must have survived and apply this
      assertThat(writer.awaitDrained(2_000)).isTrue();

      verify(ops).set(FeedPipeline.TICKER_STATUS_KEY, "CONNECTED");
    } finally {
      writer.drainAndShutdown(1_000);
    }
  }

  /**
   * The seq gate applied directly (deterministic): a lower-seq event arriving AFTER a higher-seq one
   * for the same key — the out-of-order shape two racing producers can produce between stamping and
   * publishing — is dropped, never regressing the newer status.
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

  /**
   * The Major fixed this round (task_ff56fd20): under a hung Redis, a burst of intermediate statuses
   * followed by a NEWER terminal status must NOT lose the terminal one. The bounded-queue predecessor
   * dropped the newest task on a full queue, so a terminal CONNECTED could be dropped while an older
   * CONNECTING later applied — leaving Redis stale. The per-key latest-value model coalesces the
   * burst away and the terminal CONNECTED is what reaches Redis.
   */
  @Test
  @SuppressWarnings("unchecked")
  void underHungRedisTheNewestTerminalStatusWinsNeverStaleIntermediate() throws Exception {
    CountDownLatch firstApplyEntered = new CountDownLatch(1);
    CountDownLatch releaseRedis = new CountDownLatch(1);
    Map<String, String> lastWritten = new ConcurrentHashMap<>();
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    doAnswer(
            inv -> {
              lastWritten.put(inv.getArgument(0), inv.getArgument(1));
              firstApplyEntered.countDown();
              releaseRedis.await(); // hang the writer thread inside the first apply
              return null;
            })
        .when(ops)
        .set(any(), any());
    FeedStatusWriter writer = writerWithOps(ops);
    try {
      writer.ticker("CONNECTING"); // the writer takes this and parks in the hung Redis set
      assertThat(firstApplyEntered.await(2, TimeUnit.SECONDS)).isTrue();

      // While the writer is hung, saturate with intermediates then publish the terminal CONNECTED.
      for (int i = 0; i < 500; i++) {
        writer.ticker("FLAP-" + i); // the bounded-queue predecessor would drop the newest of these
      }
      writer.ticker("CONNECTED"); // the terminal status — must be the one that survives

      releaseRedis.countDown(); // un-hang Redis; the writer drains the coalesced latest
      assertThat(writer.awaitDrained(2_000)).isTrue();

      assertThat(lastWritten.get(FeedPipeline.TICKER_STATUS_KEY))
          .as("the newest terminal status wins; a hung Redis never leaves a stale intermediate")
          .isEqualTo("CONNECTED");
    } finally {
      releaseRedis.countDown();
      writer.drainAndShutdown(1_000);
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void drainAndShutdownFlushesTheLatestPendingWrite() throws Exception {
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    FeedStatusWriter writer = writerWithOps(ops);
    writer.ticker("CONNECTING");
    writer.ticker("DISCONNECTED"); // coalesces over CONNECTING — the terminal value

    writer.drainAndShutdown(2_000);

    verify(ops).set(FeedPipeline.TICKER_STATUS_KEY, "DISCONNECTED");
  }
}
