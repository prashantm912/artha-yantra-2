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
   * Sends one alert; failures log, count and never propagate.
   *
   * <p>Title and priority ride HTTP HEADERS, so both are normalised to header-safe ASCII: the JDK
   * client rejects a non-ASCII header value outright with {@code invalid header value}, which is
   * what silently killed the {@code "ArthaYantra — Manas Arora backtest failed"} and Minervini
   * alerts (both hardcode an em-dash). The body is free-form and stays full UTF-8 — sent explicitly
   * as such, since the converter default is ISO-8859-1 and mangled every non-ASCII char to '?'.
   * Every failure increments {@code ay_ntfy_send_failed_total} so a dead ops channel is visible.
   */
  public void send(String title, String priority, String message) {
    if (topic == null || topic.isBlank()) {
      return;
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
    } catch (Exception e) {
      meterRegistry.counter("ay_ntfy_send_failed_total").increment();
      log.warn("ntfy send failed: {}", e.getMessage());
    }
  }
}
