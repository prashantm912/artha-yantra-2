package in.arthayantra.marketdata.upstox.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Asserts the EXACT Upstox v3 WS control-message JSON for a given subscription set. */
class UpstoxSubscriptionMessageTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static JsonNode parse(byte[] frame) throws IOException {
    return MAPPER.readTree(new String(frame, StandardCharsets.UTF_8));
  }

  @Test
  void subscribeMessageHasGuidMethodSubAndModeAndInstrumentKeys() throws IOException {
    JsonNode msg =
        parse(
            UpstoxSubscriptionMessage.subscribe(
                "abc123", "full", List.of("NSE_INDEX|Nifty 50", "NSE_FO|55512")));

    assertThat(msg.get("guid").asText()).isEqualTo("abc123");
    assertThat(msg.get("method").asText()).isEqualTo("sub");
    JsonNode data = msg.get("data");
    assertThat(data.get("mode").asText()).isEqualTo("full");
    assertThat(data.get("instrumentKeys"))
        .extracting(JsonNode::asText)
        .containsExactly("NSE_INDEX|Nifty 50", "NSE_FO|55512");
  }

  @Test
  void unsubscribeMessageHasMethodUnsubAndNoMode() throws IOException {
    JsonNode msg = parse(UpstoxSubscriptionMessage.unsubscribe("g2", List.of("NSE_FO|55512")));

    assertThat(msg.get("method").asText()).isEqualTo("unsub");
    assertThat(msg.get("data").has("mode")).as("unsub carries no mode").isFalse();
    assertThat(msg.get("data").get("instrumentKeys"))
        .extracting(JsonNode::asText)
        .containsExactly("NSE_FO|55512");
  }

  @Test
  void guidIsTwentyCharactersDashless() {
    String guid = UpstoxSubscriptionMessage.newGuid();
    assertThat(guid).hasSize(20).doesNotContain("-");
  }
}
