package in.arthayantra.strategysignal.notifier;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Outbound notifier client (Phase 41 / E-14): ntfy PRIMARY (plain POST to its own random-suffixed
 * topic, distinct from the ops topic) and Telegram ALTERNATIVE (ONE plain HTTPS POST to
 * {@code sendMessage} — no bot library, no SDK). A non-2xx throws so the caller can retry.
 */
@Component
public class NotifierClient {

  private final RestClient http;
  private final String ntfyUrl;
  private final String ntfyTopic;
  private final String telegramBotToken;
  private final String telegramChatId;

  /** Wires the HTTP client + channel config (NTFY_URL points at the WireMock stub under mock). */
  public NotifierClient(
      RestClient.Builder builder,
      @Value("${artha.notifier.ntfy-url:https://ntfy.sh}") String ntfyUrl,
      @Value("${artha.notifier.ntfy-topic:}") String ntfyTopic,
      @Value("${artha.notifier.telegram-bot-token:}") String telegramBotToken,
      @Value("${artha.notifier.telegram-chat-id:}") String telegramChatId) {
    this.http = builder.build();
    this.ntfyUrl = ntfyUrl;
    this.ntfyTopic = ntfyTopic;
    this.telegramBotToken = telegramBotToken;
    this.telegramChatId = telegramChatId;
  }

  /** True when the channel has the config it needs to send. */
  public boolean configured(String channel) {
    if ("TELEGRAM".equals(channel)) {
      return !telegramBotToken.isBlank() && !telegramChatId.isBlank();
    }
    return !ntfyTopic.isBlank();
  }

  /** Send a push; throws on a transport/non-2xx error (the caller retries). NEVER carries credentials. */
  public void send(String channel, String title, String message) {
    if ("TELEGRAM".equals(channel)) {
      MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
      form.add("chat_id", telegramChatId);
      form.add("text", title + "\n" + message);
      http.post()
          .uri("https://api.telegram.org/bot{token}/sendMessage", telegramBotToken)
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .body(form)
          .retrieve()
          .toBodilessEntity();
      return;
    }
    http.post()
        .uri(URI.create(ntfyUrl + "/" + ntfyTopic))
        .header("Title", title)
        .body(message)
        .retrieve()
        .toBodilessEntity();
  }
}
