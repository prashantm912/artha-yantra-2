package in.arthayantra.strategysignal.notifier;

import in.arthayantra.common.web.http.HttpHeaderText;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.nio.charset.StandardCharsets;
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
  private final MeterRegistry meterRegistry;
  private final String ntfyUrl;
  private final String ntfyTopic;
  private final String telegramBotToken;
  private final String telegramChatId;

  /** Wires the HTTP client + channel config (NTFY_URL points at the WireMock stub under mock). */
  public NotifierClient(
      RestClient.Builder builder,
      MeterRegistry meterRegistry,
      @Value("${artha.notifier.ntfy-url:https://ntfy.sh}") String ntfyUrl,
      @Value("${artha.notifier.ntfy-topic:}") String ntfyTopic,
      @Value("${artha.notifier.telegram-bot-token:}") String telegramBotToken,
      @Value("${artha.notifier.telegram-chat-id:}") String telegramChatId) {
    this.http = builder.build();
    this.meterRegistry = meterRegistry;
    this.ntfyUrl = ntfyUrl;
    this.ntfyTopic = ntfyTopic;
    this.telegramBotToken = telegramBotToken;
    this.telegramChatId = telegramChatId;
  }

  /**
   * True when the channel has the config it needs to send. NTFY requires BOTH url and topic:
   * the compose defaults are fail-closed blank (audit P0-6 — the old wiremock URL default ran
   * in live too, so every push hit the catch-all stub and was audited SENT).
   */
  public boolean configured(String channel) {
    if ("TELEGRAM".equals(channel)) {
      return !telegramBotToken.isBlank() && !telegramChatId.isBlank();
    }
    return !ntfyUrl.isBlank() && !ntfyTopic.isBlank();
  }

  /**
   * Send a push; throws on a transport/non-2xx error (the caller retries). NEVER carries
   * credentials. Every failure also increments {@code ay_notifier_send_failed_total{channel}} so a
   * dead channel is visible in metrics and not only in a swallowed WARN — the 2026-07-20 outage
   * went unnoticed precisely because every call site logged and moved on.
   */
  public void send(String channel, String title, String message) {
    try {
      dispatch(channel, title, message);
    } catch (RuntimeException e) {
      meterRegistry.counter("ay_notifier_send_failed_total", "channel", channel).increment();
      throw e;
    }
  }

  private void dispatch(String channel, String title, String message) {
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
    // The title rides an HTTP HEADER, so it MUST be header-safe ASCII — an em-dash (routine in our
    // alert titles) makes the JDK client throw "invalid header value" and the push never leaves the
    // JVM. Sanitising HERE, at the one seam, is what stops any call site reintroducing it. The body
    // stays untouched free-form text, sent explicitly as UTF-8 (the converter default is ISO-8859-1,
    // which silently mangled every non-ASCII char in the message to '?').
    http.post()
        .uri(URI.create(ntfyUrl + "/" + ntfyTopic))
        .header("Title", HttpHeaderText.toHeaderValue(title))
        .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
        .body(message)
        .retrieve()
        .toBodilessEntity();
  }
}
