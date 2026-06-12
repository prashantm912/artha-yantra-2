package in.arthayantra.gateway.ws;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** B-13: /topic/system deltas ride the kite.status channel; the other mappings stay put. */
class TopicChannelMappingTest {

  @Test
  void topicSystemMapsToKiteStatus() {
    assertThat(StompWebSocketHandler.channelFor("/topic/system")).isEqualTo("kite.status");
    assertThat(StompWebSocketHandler.channelFor("/topic/ticks.NSE.RELIANCE"))
        .isEqualTo("ticks.NSE.RELIANCE");
    assertThat(StompWebSocketHandler.channelFor("/topic/jobs/abc")).isEqualTo("jobs.progress");
    assertThat(StompWebSocketHandler.channelFor("/topic/anything-else")).isNull();
  }
}
