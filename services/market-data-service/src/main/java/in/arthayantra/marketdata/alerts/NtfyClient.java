package in.arthayantra.marketdata.alerts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
  private final String topic;

  /** Wires the env-configured endpoint. */
  public NtfyClient(
      RestClient.Builder builder,
      @Value("${artha.ntfy.url:https://ntfy.sh}") String url,
      @Value("${artha.ntfy.topic:}") String topic) {
    this.restClient = builder.baseUrl(url).build();
    this.topic = topic;
  }

  /** Sends one alert; failures log and never propagate. */
  public void send(String title, String priority, String message) {
    if (topic == null || topic.isBlank()) {
      return;
    }
    try {
      restClient
          .post()
          .uri("/{topic}", topic)
          .header("Title", title)
          .header("Priority", priority)
          .body(message)
          .retrieve()
          .toBodilessEntity();
    } catch (Exception e) {
      log.warn("ntfy send failed: {}", e.getMessage());
    }
  }
}
