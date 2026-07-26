package in.arthayantra.strategysignal.paper;

import in.arthayantra.strategysignal.signals.SignalExited;
import in.arthayantra.strategysignal.signals.SignalRepository;
import in.arthayantra.strategysignal.signals.SwingPaperEffectRepository;
import in.arthayantra.strategysignal.signals.SwingPaperEffectRetry;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Closes the paper position(s) linked to an ENTRY anchor when the engine emits its EXIT. Mirrors
 * {@link PaperSignalListener}'s posture: synchronous listener, a paper failure is logged and never
 * propagated into the signal-eval loop. Without this, a TAKEN entry's position outlived every
 * engine exit and only the 15:45 mark-to-close resolved it (audit P0-2).
 */
@Component
public class EngineExitListener {

  private static final Logger log = LoggerFactory.getLogger(EngineExitListener.class);

  private final PaperService paper;
  private final SignalRepository signals;
  private final PaperPositionRepository positions;
  private final SwingPaperEffectRepository paperEffects;

  /** Wires the paper ledger. */
  @Autowired
  public EngineExitListener(
      PaperService paper,
      SignalRepository signals,
      PaperPositionRepository positions,
      SwingPaperEffectRepository paperEffects) {
    this.paper = paper;
    this.signals = signals;
    this.positions = positions;
    this.paperEffects = paperEffects;
  }

  /** Backwards-compatible constructor for focused paper-listener tests. */
  public EngineExitListener(PaperService paper) {
    this.paper = paper;
    this.signals = null;
    this.positions = null;
    this.paperEffects = null;
  }

  /**
   * Settles every open position linked to the exited anchor with the engine's reason — at the event's
   * explicit price when present (the Phase-9 swing batch's daily-bar close for non-ticking equities),
   * else at the live LTP (the tick-engine default).
   */
  @EventListener
  public void onSignalExited(SignalExited event) {
    SwingContext context = swingContext(event.anchorSignalId(), event.exitSignalId());
    if (context != null) {
      closeSwingEffect(
          event.anchorSignalId(),
          event.reason(),
          event.price());
      return;
    }
    try {
      int closed = paper.closeForSignal(event.anchorSignalId(), event.reason(), event.price());
      if (closed > 0) {
        log.info(
            "engine EXIT ({}) closed {} paper position(s) for signal {}",
            event.reason(), closed, event.anchorSignalId());
      }
    } catch (Exception e) {
      log.warn(
          "paper close failed for exited signal {}: {}", event.anchorSignalId(), e.getMessage());
    }
  }

  /** Retries an already-claimed close after the engine has expired its anchor. */
  @EventListener
  public void onSwingPaperEffectRetry(SwingPaperEffectRetry retry) {
    if (retry.kind() != SwingPaperEffectRetry.Kind.EXIT) {
      return;
    }
    closeSwingEffect(
        retry.anchorSignalId(),
        retry.reason(),
        retry.price());
  }

  private void closeSwingEffect(
      long anchorSignalId,
      String reason,
      java.math.BigDecimal price) {
    try {
      if (positions != null && !hasOpenSwingPosition(anchorSignalId)) {
        paperEffects.findCloseByAnchor(anchorSignalId).ifPresent(effect -> paperEffects.confirm(effect.id()));
        return;
      }
      var effect = paperEffects.findCloseByAnchor(anchorSignalId);
      if (effect.isEmpty()) {
        return; // a sibling SignalExited event; the primary effect owns the shared position
      }
      var lease = paperEffects.claimClose(effect.get().id());
      if (lease.isEmpty()) {
        return; // already confirmed or another retry owns the lease
      }
      int closed = closeSwingPosition(anchorSignalId, reason, price);
      if (positions == null || !hasOpenSwingPosition(anchorSignalId)) {
        paperEffects.confirm(lease.get().id());
      }
      if (closed > 0) {
        log.info(
            "engine EXIT ({}) closed {} paper position(s) for signal {}",
            reason,
            closed,
            anchorSignalId);
      }
    } catch (Exception e) {
      log.warn("paper close failed for exited swing signal {}: {}", anchorSignalId, e.getMessage());
    }
  }

  /** A pyramid add can own the shared paper row even when the oldest exit anchor has no order. */
  private boolean hasOpenSwingPosition(long anchorSignalId) {
    SignalRepository.SignalRow anchor = signals.find(anchorSignalId).orElse(null);
    if (anchor == null) {
      return !positions.openForSignal(anchorSignalId).isEmpty();
    }
    return positions
        .findOpen(
            signals.bookForSignal(anchorSignalId),
            anchor.exchange(),
            anchor.tradingsymbol(),
            anchor.side())
        .isPresent();
  }

  /** Close every signal-linked order on the shared key, once, preserving averaged-pyramid semantics. */
  private int closeSwingPosition(
      long anchorSignalId, String reason, java.math.BigDecimal price) {
    SignalRepository.SignalRow anchor = signals.find(anchorSignalId).orElse(null);
    if (anchor == null) {
      return paper.closeForSignal(anchorSignalId, reason, price);
    }
    String book = signals.bookForSignal(anchorSignalId);
    int closed = 0;
    for (long signalId :
        positions.signalIdsFor(book, anchor.exchange(), anchor.tradingsymbol(), anchor.side())) {
      closed += paper.closeForSignal(signalId, reason, price);
    }
    return closed;
  }

  private SwingContext swingContext(long anchorSignalId, long exitSignalId) {
    if (signals == null || paperEffects == null || positions == null) {
      return null;
    }
    String book = signals.bookForSignal(anchorSignalId);
    String batch = swingBatch(book);
    if (batch == null) {
      return null;
    }
    LocalDate sessionDate =
        signals
            .find(exitSignalId)
            .or(() -> signals.find(anchorSignalId))
            .map(row -> row.generatedAt().atZoneSameInstant(ZoneId.of("Asia/Kolkata")).toLocalDate())
            .orElse(null);
    return sessionDate == null ? null : new SwingContext(batch, sessionDate);
  }

  private static String swingBatch(String book) {
    if (BookResolver.MINERVINI.equals(book)) {
      return "minervini";
    }
    if (BookResolver.MANAS_ARORA.equals(book)) {
      return "manas-arora";
    }
    return null;
  }

  private record SwingContext(String batch, LocalDate sessionDate) {}
}
