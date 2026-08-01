package in.arthayantra.strategysignal.insights;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.arthayantra.strategysignal.signals.InsightDeliveryAlert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * The insight WS delivery seam (INT design §9.3) — publishes a tiny {@code insights} Redis frame that
 * the gateway STOMP bridge relays to {@code /topic/insights} (the WS bridge allowlist +1, mirroring
 * {@link in.arthayantra.strategysignal.paper.PaperEventPublisher}'s {@code paper.events}). The durable
 * {@code insights} row is the record of truth; the frame is best-effort live delivery, healed by the
 * REST feed read on reconnect.
 *
 * <p><b>Staged-rollout gates (§10.3).</b> WS delivery remains behind {@code
 * artha.insights.delivery.ws}. Phone delivery is emitted as a typed in-process event so this module
 * never imports {@code notifier}; the notifier listener owns the existing outbound client and audit.
 * Fail-soft: a broadcast or event-publish hiccup logs and is swallowed — delivery must NEVER perturb
 * generation.
 */
@Component
public class InsightPublisher {

  /** The pub/sub channel the gateway relays to {@code /topic/insights} (WS allowlist §9.3). */
  public static final String CHANNEL = "insights";

  private static final Logger log = LoggerFactory.getLogger(InsightPublisher.class);

  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;
  private final ApplicationEventPublisher events;
  private final InsightRepository repository;
  private final boolean wsEnabled;
  private final boolean ntfyEnabled;
  private final boolean telegramEnabled;
  private final Severity severityFloor;

  /** Wires Redis + the staged-rollout delivery flags (all phone flags default false). */
  public InsightPublisher(
      StringRedisTemplate redis,
      ObjectMapper objectMapper,
      ApplicationEventPublisher events,
      InsightRepository repository,
      InsightProperties props) {
    this.redis = redis;
    this.objectMapper = objectMapper;
    this.events = events;
    this.repository = repository;
    this.wsEnabled = props.delivery().ws();
    this.ntfyEnabled = props.delivery().ntfyEnabled();
    this.telegramEnabled = props.delivery().telegramEnabled();
    this.severityFloor = props.delivery().severityFloor();
  }

  /**
   * Publishes a NOTICE-or-higher insight as a compact frame ({@code {id,type,severity,scope,title}},
   * §9.3). A no-op when delivery is disabled (shadow mode) or the insight is below the WS floor /
   * suppressed. WS is independently flag-gated; INFO is never sent to phone channels, and the common
   * phone floor is clamped to NOTICE.
   * Returns whether a WS frame was actually published (for the engine's counter/test).
   */
  public boolean publish(Insight insight) {
    if (insight == null || insight.suppressed()) {
      return false;
    }
    Severity severity = Severity.valueOf(insight.severity());
    boolean phoneEligible = severity.compareTo(severityFloor) >= 0 && (ntfyEnabled || telegramEnabled);
    boolean wsEligible = wsEnabled && severity.compareTo(Severity.NOTICE) >= 0;
    if (!phoneEligible && !wsEligible) {
      return false;
    }
    if (repository.isMuted(insight.type(), insight.scope())) {
      return false;
    }
    boolean wsPublished = false;
    if (wsEligible) { // WS floor: >= NOTICE
      wsPublished = publishWs(insight);
    }
    if (phoneEligible) {
      publishPhoneEvent(insight, "NTFY", ntfyEnabled);
      publishPhoneEvent(insight, "TELEGRAM", telegramEnabled);
    }
    return wsPublished;
  }

  private boolean publishWs(Insight insight) {
    try {
      ObjectNode f = objectMapper.createObjectNode();
      f.put("id", insight.id().toString());
      f.put("type", insight.type());
      f.put("severity", insight.severity());
      f.put("scope", insight.scope());
      f.put("title", insight.title());
      f.put("at", insight.generatedAt().toString());
      redis.convertAndSend(CHANNEL, objectMapper.writeValueAsString(f));
      return true;
    } catch (Exception e) {
      log.warn("insight frame not published ({}): {}", insight.dedupeKey(), e.getMessage());
      return false;
    }
  }

  private void publishPhoneEvent(Insight insight, String channel, boolean enabled) {
    if (!enabled) {
      return;
    }
    try {
      events.publishEvent(
          new InsightDeliveryAlert(
              insight.id(), insight.title(), insight.explanation(), insight.scope(), channel));
    } catch (RuntimeException e) {
      log.warn("insight {} delivery event not published for {}: {}", insight.id(), channel, e.getMessage());
    }
  }

  /** Whether WS delivery is armed (shadow mode = false). Exposed for the engine's guard + tests. */
  public boolean wsEnabled() {
    return wsEnabled;
  }
}
