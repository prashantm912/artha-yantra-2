package in.arthayantra.marketdata.alerts;

import in.arthayantra.common.web.http.HttpHeaderText;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The first-party ntfy client (B-14): a five-line POST to an env-configured topic, shared by the
 * contract canary and the corporate-action job. No topic configured → silent no-op (mock mode
 * needs no ntfy config). The db-backup sidecar's curl POST is the same CONVENTION, not shared
 * code.
 */
@Component
public class NtfyClient {

  private static final Logger log = LoggerFactory.getLogger(NtfyClient.class);

  private final RestClient restClient;
  private final MeterRegistry meterRegistry;
  private final String topic;

  /** Wires the env-configured endpoint. */
  public NtfyClient(
      RestClient.Builder builder,
      MeterRegistry meterRegistry,
      @Value("${artha.ntfy.url:https://ntfy.sh}") String url,
      @Value("${artha.ntfy.topic:}") String topic) {
    this.restClient = builder.baseUrl(url).build();
    this.meterRegistry = meterRegistry;
    this.topic = topic;
  }

  /**
   * Sends one alert; failures log, count and never propagate. Delegates to {@link #trySend}
   * and discards the outcome — for a caller that needs to know whether delivery actually
   * succeeded (to decide whether to retry), use {@link #trySend} directly.
   */
  public void send(String title, String priority, String message) {
    trySend(title, priority, message);
  }

  /**
   * Like {@link #send}, but reports whether the POST actually succeeded instead of swallowing
   * the outcome — for a caller whose own durability guarantee depends on delivery having
   * happened (e.g. confirming a claimed publish only once the push is actually out), rather
   * than a fire-and-forget alert where "we tried" is good enough.
   *
   * <p>Title and priority ride HTTP HEADERS, so both are normalised to header-safe ASCII: the JDK
   * client rejects a non-ASCII header value outright with {@code invalid header value}, which is
   * what silently killed the {@code "ArthaYantra — Manas Arora backtest failed"} and Minervini
   * alerts (both hardcode an em-dash). The body is free-form and stays full UTF-8 — sent explicitly
   * as such, since the converter default is ISO-8859-1 and mangled every non-ASCII char to '?'.
   * Every failure increments {@code ay_ntfy_send_failed_total} so a dead ops channel is visible.
   *
   * @return {@code true} iff the POST completed without throwing; {@code false} when no topic is
   *     configured (mock mode — nothing was sent, by design) or the POST itself failed.
   */
  public boolean trySend(String title, String priority, String message) {
    if (topic == null || topic.isBlank()) {
      return false;
    }
    try {
      restClient
          .post()
          .uri("/{topic}", topic)
          .header("Title", HttpHeaderText.toHeaderValue(title))
          .header("Priority", HttpHeaderText.toHeaderValue(priority))
          .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
          .body(message)
          .retrieve()
          .toBodilessEntity();
      return true;
    } catch (Exception e) {
      meterRegistry.counter("ay_ntfy_send_failed_total").increment();
      log.warn("ntfy send failed: {}", e.getMessage());
      return false;
    }
  }
}
