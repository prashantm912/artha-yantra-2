package in.arthayantra.strategysignal.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** Audit M24: the bot token must never survive into a log line via a transport-failure message. */
class TelegramApiTest {

  private static final String TOKEN = "7654321:AAABBBcccDDDeeeFFFgggHHHiiiJJJkkk123";

  private TelegramApi api() {
    return new TelegramApi(
        RestClient.builder(), new ObjectMapper(), "https://api.telegram.org", TOKEN);
  }

  @Test
  void redactsLiteralTokenFromMessage() {
    String raw = "I/O error on GET request for \"https://api.telegram.org/bot" + TOKEN + "/getUpdates\"";
    String out = api().redact(raw);
    assertThat(out).doesNotContain(TOKEN);
    assertThat(out).contains("/bot***/");
  }

  @Test
  void redactsAnyBotPathEvenWhenTokenDiffers() {
    // Defence in depth: even if the exact token text does not match (encoding, a rotated token in
    // the message), the /bot<...>/ path shape is scrubbed.
    String out = api().redact("connect timed out calling /bot99:OTHERtok/sendMessage");
    assertThat(out).doesNotContain("OTHERtok");
    assertThat(out).contains("/bot***/");
  }

  @Test
  void nullMessageStaysNull() {
    assertThat(api().redact(null)).isNull();
  }
}
