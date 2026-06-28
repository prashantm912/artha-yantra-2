package in.arthayantra.strategysignal.paper;

import in.arthayantra.strategysignal.signals.SignalTaken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Opens a paper position when a TAKEN signal carries a qty (§F.6 "optionally opens a paper
 * position"). Synchronous {@code @EventListener} so the position is visible by the time the
 * {@code /taken} response returns; a paper-open failure is logged, never propagated to the caller.
 */
@Component
public class PaperSignalListener {

  private static final Logger log = LoggerFactory.getLogger(PaperSignalListener.class);

  private final PaperService paper;
  private final ScalperAccountModel scalperAccounts;

  /** Wires the ledger service and the 5-account sub-ledger. */
  public PaperSignalListener(PaperService paper, ScalperAccountModel scalperAccounts) {
    this.paper = paper;
    this.scalperAccounts = scalperAccounts;
  }

  /** Opens a position from the signal when a qty was supplied. */
  @EventListener
  public void onSignalTaken(SignalTaken event) {
    if (event.qty() == null || event.qty() <= 0) {
      return;
    }
    try {
      // E10: a scalper take is charged to a round-robin sub-account (the per-account first-loss
      // freeze reads it); a non-scalper / manual take leaves the ledger key NULL.
      Integer subaccountIdx = event.scalper() ? scalperAccounts.nextFreeAccount() : null;
      paper.openOrder(
          new PaperService.OrderRequest(
              event.signalId(), null, null, null, event.qty(), event.fillPrice(), null, null,
              subaccountIdx));
    } catch (Exception e) {
      log.warn("paper position not opened for taken signal {}: {}", event.signalId(), e.getMessage());
    }
  }
}
