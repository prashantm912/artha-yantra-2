package in.arthayantra.strategysignal.paper;

import in.arthayantra.strategysignal.signals.SignalExited;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

  /** Wires the paper ledger. */
  public EngineExitListener(PaperService paper) {
    this.paper = paper;
  }

  /** Settles every open position linked to the exited anchor at LTP, with the engine's reason. */
  @EventListener
  public void onSignalExited(SignalExited event) {
    try {
      int closed = paper.closeForSignal(event.anchorSignalId(), event.reason());
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
}
