package in.arthayantra.strategysignal.insights;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * The insight WS delivery seam (INT design §9.3) — publishes a tiny {@code insights} Redis frame that
 * the gateway STOMP bridge relays to {@code /topic/insights} (the WS bridge allowlist +1, mirroring
 * {@link in.arthayantra.strategysignal.paper.PaperEventPublisher}'s {@code paper.events}). The durable
 * {@code insights} row is the record of truth; the frame is best-effort live delivery, healed by the
 * REST feed read on reconnect.
 *
 * <p><b>Staged-rollout gate (§10.3 Stage 1).</b> Delivery is behind {@code artha.insights.delivery.ws}
 * (default FALSE) — in I3 SHADOW mode NOTHING pushes: the engine calls this on every generated NOTICE+
 * insight, but the publisher no-ops until the owner flips the flag (the arming is I4, with its compose
 * passthrough added THEN so the #653 exact-name check lands with the .env override). Fail-soft: a
 * broadcast hiccup logs and is swallowed — an observability frame must NEVER perturb generation.
 */
@Component
public class InsightPublisher {

  /** The pub/sub channel the gateway relays to {@code /topic/insights} (WS allowlist §9.3). */
  public static final String CHANNEL = "insights";

  private static final Logger log = LoggerFactory.getLogger(InsightPublisher.class);

  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;
  private final boolean wsEnabled;

  /** Wires Redis + the staged-rollout WS delivery flag (default false → shadow mode). */
  public InsightPublisher(StringRedisTemplate redis, ObjectMapper objectMapper, InsightProperties props) {
    this.redis = redis;
    this.objectMapper = objectMapper;
    this.wsEnabled = props.delivery().ws();
  }

  /**
   * Publishes a NOTICE-or-higher insight as a compact frame ({@code {id,type,severity,scope,title}},
   * §9.3). A no-op when delivery is disabled (shadow mode) or the insight is below the WS floor /
   * suppressed. Returns whether a frame was actually published (for the engine's counter/test).
   */
  public boolean publish(Insight insight) {
    if (!wsEnabled || insight == null || insight.suppressed()) {
      return false;
    }
    Severity severity = Severity.valueOf(insight.severity());
    if (severity.ordinal() < Severity.NOTICE.ordinal()) { // WS floor: >= NOTICE (§2.5.3)
      return false;
    }
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

  /** Whether WS delivery is armed (shadow mode = false). Exposed for the engine's guard + tests. */
  public boolean wsEnabled() {
    return wsEnabled;
  }
}
