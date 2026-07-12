package in.arthayantra.strategysignal.signals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes emitted signals on the {@code signals} channel (COMMON 7.3.1) — the Stage-A gateway
 * STOMP bridge relays it to {@code /topic/signals} for the browser. The payload embeds the SAME
 * breakdown JSON that was persisted (a Phase 23 FAIL criterion is any divergence between row
 * and channel payload).
 */
@Component
public class SignalPublisher {

  /** The pub/sub channel. */
  public static final String CHANNEL = "signals";

  /**
   * The sibling status-transition channel (app-platform audit §7.2.1 / §9.3 — the WS bridge
   * allowlist grows by exactly this + {@code paper.events}). The gateway relays it to
   * {@code /topic/signals.status}; the browser {@code useSignalsLive} patches the cached row's
   * status in place. Kept separate from {@link #CHANNEL} so the full-frame ACTIVE broadcast and the
   * tiny status patch never collide in the FE merge, and so a status frame need not re-serialise the
   * whole (frozen) signal payload.
   */
  public static final String STATUS_CHANNEL = "signals.status";

  private static final Logger log = LoggerFactory.getLogger(SignalPublisher.class);

  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;

  /** Wires Redis. */
  public SignalPublisher(StringRedisTemplate redis, ObjectMapper objectMapper) {
    this.redis = redis;
    this.objectMapper = objectMapper;
  }

  /** Emits one signal event; failures log and never break persistence. */
  public void publish(
      long signalId,
      UUID strategyVersionId,
      String strategyName,
      String slug,
      String version,
      String checksum,
      String book,
      String exchange,
      String tradingsymbol,
      String interval,
      String signalType,
      String side,
      BigDecimal entryPrice,
      BigDecimal stopLoss,
      BigDecimal target,
      BigDecimal compositeScore,
      JsonNode scoreBreakdown,
      OffsetDateTime generatedAt) {
    try {
      ObjectNode payload = objectMapper.createObjectNode();
      payload.put("id", signalId);
      payload.put("strategyVersionId", strategyVersionId.toString());
      payload.put("strategyName", strategyName);
      payload.put("strategyId", slug);
      payload.put("version", version);
      payload.put("checksum", checksum);
      // The paper BOOK (strategy family, Books.fromTags) so a book-filtered live view can drop a
      // frame for another book — else every browser session merges every book's signals (audit M17).
      payload.put("book", book);
      payload.put("exchange", exchange);
      payload.put("tradingsymbol", tradingsymbol);
      payload.put("interval", interval);
      payload.put("signalType", signalType);
      payload.put("side", side);
      payload.put("entryPrice", entryPrice == null ? null : entryPrice.toPlainString());
      payload.put("stopLoss", stopLoss == null ? null : stopLoss.toPlainString());
      payload.put("target", target == null ? null : target.toPlainString());
      payload.put("compositeScore", compositeScore.toPlainString());
      payload.set("scoreBreakdown", scoreBreakdown);
      payload.put("generatedAt", generatedAt.toString());
      payload.put("status", "ACTIVE");
      redis.convertAndSend(CHANNEL, objectMapper.writeValueAsString(payload));
    } catch (Exception e) {
      log.warn("signal publish failed for #{}: {}", signalId, e.getMessage());
    }
  }

  /**
   * Emits one status-transition frame on {@link #STATUS_CHANNEL} (audit §7.2.1). The frame is a
   * PATCH — {@code {id, status, book, prevStatus, at}} — never the full signal payload: the browser
   * only updates the matching cached row's status. {@code book} lets a book-filtered live view drop
   * a frame for another book (the M17 rule); {@code prevStatus} is null when the mutation used the
   * unguarded transition. Fail-soft: a status frame must never break the mutation it reports (the DB
   * row is already committed; the next REST snapshot heals a dropped frame).
   */
  public void publishStatus(long signalId, String status, String book, String prevStatus) {
    try {
      ObjectNode payload = objectMapper.createObjectNode();
      payload.put("id", signalId);
      payload.put("status", status);
      payload.put("book", book);
      payload.put("prevStatus", prevStatus);
      payload.put("at", OffsetDateTime.now().toString());
      redis.convertAndSend(STATUS_CHANNEL, objectMapper.writeValueAsString(payload));
    } catch (Exception e) {
      log.warn("signal status publish failed for #{}: {}", signalId, e.getMessage());
    }
  }
}
