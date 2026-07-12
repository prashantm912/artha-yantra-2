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

  /** §9.3: the bridge allowlist grows by exactly these two Phase-2A channels (audit §7.2.1/§7.2.2). */
  @Test
  void phase2aEventChannelsAreAllowlisted() {
    assertThat(StompWebSocketHandler.channelFor("/topic/signals.status")).isEqualTo("signals.status");
    assertThat(StompWebSocketHandler.channelFor("/topic/paper.events")).isEqualTo("paper.events");
  }
}
