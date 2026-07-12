package in.arthayantra.strategysignal.paper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.arthayantra.strategysignal.paper.PaperEventRepository.PaperEventRow;
import in.arthayantra.strategysignal.paper.PaperPositionRepository.PositionRow;
import java.math.BigDecimal;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Serialises the existing in-process paper-position lifecycle events ({@link PaperPositionOpened} /
 * {@link PaperPositionClosed}) into (a) an append-only {@code paper_events} row and (b) a
 * {@code paper.events} Redis frame — audit §7.2.2 ("this is serialization + publication, not new
 * logic"). Fires AFTER_COMMIT so an event is recorded only for a trade that actually committed (no
 * phantom row/frame on a rolled-back settle), and off the trade transaction. Fail-soft end to end: a
 * ledger or broadcast hiccup logs and is swallowed — an observability event must NEVER break the
 * trade it observes (mirrors {@link in.arthayantra.strategysignal.journal.AutoJournalListener}). The
 * durable row is the record of truth; the frame is best-effort live delivery, healed by the REST read
 * on reconnect. Lives in the paper module (its only inputs are paper events + the paper repos), so the
 * module graph stays acyclic.
 */
@Component
public class PaperEventPublisher {

  /** The pub/sub channel — the gateway relays it to {@code /topic/paper.events} (WS allowlist §9.3). */
  public static final String CHANNEL = "paper.events";

  private static final Logger log = LoggerFactory.getLogger(PaperEventPublisher.class);

  private final PaperEventRepository eventsRepo;
  private final PaperPositionRepository positions;
  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;

  /** Wires the ledger + position lookup + Redis. */
  public PaperEventPublisher(
      PaperEventRepository eventsRepo,
      PaperPositionRepository positions,
      StringRedisTemplate redis,
      ObjectMapper objectMapper) {
    this.eventsRepo = eventsRepo;
    this.objectMapper = objectMapper;
    this.positions = positions;
    this.redis = redis;
  }

  /** OPENED (or averaged-into) — the event carries every field, no lookup needed. */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onOpened(PaperPositionOpened e) {
    record(
        e.positionId(), "OPENED", e.book(), e.exchange(), e.tradingsymbol(), e.side(), e.qty(),
        null, null);
  }

  /**
   * A close — the {@link PaperPositionClosed} event carries only id/realized/reason, so the position
   * (now CLOSED, but {@code find} is status-agnostic) is looked up for book/instrument/side/qty. The
   * {@code kind} is derived from the close reason.
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onClosed(PaperPositionClosed e) {
    Optional<PositionRow> pos = positions.find(e.positionId());
    if (pos.isEmpty()) {
      // Only reachable if the position was hard-deleted (a book reset) in the AFTER_COMMIT window —
      // nothing to attribute the event to, so skip rather than write a book-less row.
      log.warn("paper.events: closed position {} not found — no event recorded", e.positionId());
      return;
    }
    PositionRow p = pos.get();
    record(
        e.positionId(), kindFor(e.closeReason()), p.book(), p.exchange(), p.tradingsymbol(),
        p.side(), p.qty(), e.closeReason(), e.realized());
  }

  /**
   * Maps a {@code close_reason} to the event {@code kind}: bracket breaches → BRACKET_HIT, expiry
   * intrinsic/spot settlement → SETTLED, everything else (manual close, engine exit, 15:45
   * mark-to-close) → CLOSED.
   */
  static String kindFor(String closeReason) {
    if ("STOP_LOSS".equals(closeReason) || "TAKE_PROFIT".equals(closeReason)) {
      return "BRACKET_HIT";
    }
    if ("EXPIRY_SETTLEMENT".equals(closeReason)) {
      return "SETTLED";
    }
    return "CLOSED";
  }

  private void record(
      long positionId,
      String kind,
      String book,
      String exchange,
      String tradingsymbol,
      String side,
      long qty,
      String reason,
      BigDecimal realizedPnl) {
    long eventId;
    try {
      eventId =
          eventsRepo.insert(
              positionId, kind, book, exchange, tradingsymbol, side, qty, reason, realizedPnl);
    } catch (Exception ex) {
      log.warn("paper.events row not written for position {} ({}): {}", positionId, kind, ex.getMessage());
      return;
    }
    try {
      ObjectNode f = objectMapper.createObjectNode();
      f.put("id", eventId);
      f.put("positionId", positionId);
      f.put("kind", kind);
      f.put("book", book);
      f.put("exchange", exchange);
      f.put("tradingsymbol", tradingsymbol);
      f.put("side", side);
      f.put("qty", qty);
      f.put("reason", reason);
      f.put("realizedPnl", realizedPnl == null ? null : realizedPnl.toPlainString());
      f.put("at", java.time.OffsetDateTime.now().toString());
      redis.convertAndSend(CHANNEL, objectMapper.writeValueAsString(f));
    } catch (Exception ex) {
      log.warn("paper.events frame not published for position {} ({}): {}", positionId, kind, ex.getMessage());
    }
  }
}
