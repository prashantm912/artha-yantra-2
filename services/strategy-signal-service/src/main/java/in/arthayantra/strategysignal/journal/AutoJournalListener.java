package in.arthayantra.strategysignal.journal;

import in.arthayantra.strategysignal.paper.PaperPositionClosed;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Auto-journals every closed paper trade (operative discipline rail "journal every trade"): on each
 * {@link PaperPositionClosed} it stubs one journal entry linked to the position, tagged {@code auto}
 * + win/loss, with ratings left null for the trader to fill in. Fires AFTER_COMMIT so a journal
 * failure can never roll back the trade close, and the insert is wrapped (logged, never propagated)
 * — mirroring the PaperSignalListener philosophy. Disable via {@code artha.paper.auto-journal-enabled=false}.
 */
@Component
public class AutoJournalListener {

  private static final Logger log = LoggerFactory.getLogger(AutoJournalListener.class);

  private final JournalRepository journal;
  private final boolean enabled;

  /** Wires the journal repository + the opt-out flag (default on). */
  public AutoJournalListener(
      JournalRepository journal,
      @Value("${artha.paper.auto-journal-enabled:true}") boolean enabled) {
    this.journal = journal;
    this.enabled = enabled;
  }

  /** Stubs a linked, machine-tagged journal entry for a just-closed paper position. */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onPaperPositionClosed(PaperPositionClosed e) {
    if (!enabled) {
      return;
    }
    try {
      journal.insert(
          new JournalRepository.EntryInput(
              null,
              e.positionId(),
              null,
              null,
              "Auto: " + e.closeReason() + " realized=" + e.realized().toPlainString(),
              List.of("auto", e.realized().signum() >= 0 ? "win" : "loss"),
              null,
              null));
    } catch (Exception ex) {
      log.warn("auto-journal entry not written for closed position {}: {}", e.positionId(), ex.getMessage());
    }
  }
}
