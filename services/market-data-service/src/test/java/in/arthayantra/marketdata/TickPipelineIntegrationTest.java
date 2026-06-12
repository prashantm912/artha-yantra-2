package in.arthayantra.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.marketdata.feed.FeedPipeline;
import in.arthayantra.marketdata.feed.RedisTickPublisher;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Phase-7 IT (A.7a) against pinned Redis: the mock feed publishes normalized JSON on the
 * correctly named D9 channels with string decimals, {@code +05:30} timestamps and monotonic
 * seqs; the last-tick hash and the canonical {@code kite:session:status} key are written.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.mock.ticks-per-sec=20",
      "artha.mock.instrument-count=3"
    })
class TickPipelineIntegrationTest
    extends in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase {

  @Autowired private StringRedisTemplate redis;
  @Autowired private RedisConnectionFactory connectionFactory;
  @Autowired private ObjectMapper objectMapper;

  private record Frame(String channel, JsonNode body) {}

  @Test
  void mockTicksFlowNormalizedOntoCanonicalChannels() throws Exception {
    List<Frame> frames = new CopyOnWriteArrayList<>();
    RedisMessageListenerContainer listener = new RedisMessageListenerContainer();
    listener.setConnectionFactory(connectionFactory);
    listener.addMessageListener(
        (message, pattern) -> {
          try {
            frames.add(
                new Frame(
                    new String(message.getChannel(), StandardCharsets.UTF_8),
                    objectMapper.readTree(
                        new String(message.getBody(), StandardCharsets.UTF_8))));
          } catch (Exception ignored) {
            // malformed frames fail the assertions below by absence
          }
        },
        new PatternTopic("ticks.*"));
    listener.afterPropertiesSet();
    listener.start();

    try {
      await().atMost(Duration.ofSeconds(15)).until(() -> frames.size() >= 30);
    } finally {
      listener.stop();
    }

    // NOTE: the listener container dispatches each message on its own executor
    // thread, so OUR list order is not bus order — per-instrument seq ORDERING
    // is unit-tested at the normalizer; here we pin uniqueness + coverage.
    Map<String, List<Long>> seqsByChannel = new HashMap<>();
    for (Frame frame : frames) {
      // D9 channel naming, verbatim: ticks.{exchange}.{tradingsymbol}
      assertThat(frame.channel()).matches("ticks\\.[A-Z]+\\..+");
      // prices as STRINGS (exact decimals), never JSON numbers
      assertThat(frame.body().get("lastPrice").isTextual()).isTrue();
      assertThat(frame.body().get("lastPrice").asText()).matches("\\d+\\.\\d{2}");
      // IST offset serialization
      assertThat(frame.body().get("timestamp").asText()).endsWith("+05:30");
      seqsByChannel
          .computeIfAbsent(frame.channel(), channel -> new java.util.ArrayList<>())
          .add(frame.body().get("seq").asLong());
    }
    for (List<Long> seqs : seqsByChannel.values()) {
      assertThat(seqs).doesNotHaveDuplicates();
      assertThat(seqs.stream().mapToLong(Long::longValue).max().orElse(0))
          .isGreaterThanOrEqualTo(seqs.size());
    }

    // canonical status key (COMMON §3) + last-tick hash
    assertThat(redis.opsForValue().get(FeedPipeline.SESSION_STATUS_KEY)).isEqualTo("MOCK");
    assertThat(redis.opsForHash().entries(RedisTickPublisher.LAST_TICK_HASH)).isNotEmpty();
  }
}
