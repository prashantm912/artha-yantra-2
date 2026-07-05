package in.arthayantra.strategysignal.paper;

import in.arthayantra.strategysignal.signals.SignalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Resolves a TAKEN entry once its paper position(s) are gone: on every position close (bracket,
 * straddle monitor, 15:45 MTM, expiry, manual, engine exit), if NO open position remains linked to
 * the originating signal, the anchor transitions TAKEN → EXPIRED. Without this, a TAKEN entry whose
 * position closed outside the engine (e.g. the MTM sweep) stayed anchored forever — the engine kept
 * evaluating exits against a closed position and never re-entered (audit P0-2).
 *
 * <p>AFTER_COMMIT (the {@code AutoJournalListener} pattern) so a resolver failure can never roll
 * back the close; straddles resolve only when BOTH legs are closed ({@code openForSignal} empty).
 */
@Component
public class TakenSignalResolver {

  private static final Logger log = LoggerFactory.getLogger(TakenSignalResolver.class);

  private final PaperPositionRepository positions;
  private final SignalRepository signals;

  /** Wires the position store + the signal store. */
  public TakenSignalResolver(PaperPositionRepository positions, SignalRepository signals) {
    this.positions = positions;
    this.signals = signals;
  }

  /** Expires the TAKEN anchor(s) of a just-closed position once no linked position remains open. */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onPaperPositionClosed(PaperPositionClosed event) {
    try {
      var pos = positions.find(event.positionId()).orElse(null);
      if (pos == null) {
        return;
      }
      for (Long signalId :
          positions.signalIdsFor(pos.book(), pos.exchange(), pos.tradingsymbol(), pos.side())) {
        if (positions.openForSignal(signalId).isEmpty()
            && signals.transitionIf(signalId, "TAKEN", "EXPIRED")) {
          log.info("taken signal {} resolved (position {} closed: {})",
              signalId, event.positionId(), event.closeReason());
        }
      }
    } catch (Exception e) {
      log.warn("taken-signal resolve failed for position {}: {}", event.positionId(), e.getMessage());
    }
  }
}
