package in.arthayantra.strategysignal.paper;

import in.arthayantra.strategysignal.signals.SignalEmitted;
import in.arthayantra.strategysignal.signals.SignalRepository;
import in.arthayantra.strategysignal.signals.SignalTaken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Auto-paper-trade: when the {@code auto_paper_trade} risk toggle is ON, every emitted ENTRY is
 * automatically TAKEN at its suggested qty — no manual click. It does exactly what {@code
 * POST /signals/{id}/taken} does (transition the signal to TAKEN + publish {@link SignalTaken}), so the
 * existing {@link PaperSignalListener} opens the paper position (incl. the scalper sub-account
 * routing). Default OFF — the manual take flow is unchanged. The emission itself is still gated by the
 * risk caps + kill-switch ({@code RiskService.entryAllowed}); a null/zero suggested qty is skipped.
 */
@Component
public class AutoPaperListener {

  private static final Logger log = LoggerFactory.getLogger(AutoPaperListener.class);

  private final RiskService risk;
  private final SignalRepository signals;
  private final BookResolver books;
  private final ApplicationEventPublisher events;

  /** Wires the risk toggle, the signal store, the book resolver, and the paper listener's event bus. */
  public AutoPaperListener(
      RiskService risk, SignalRepository signals, BookResolver books, ApplicationEventPublisher events) {
    this.risk = risk;
    this.signals = signals;
    this.books = books;
    this.events = events;
  }

  /** Auto-take an emitted entry at its suggested qty when the signal's BOOK has the toggle ON. */
  @EventListener
  public void onSignalEmitted(SignalEmitted event) {
    if (!risk.autoPaperTradeEnabled(books.bookForSignal(event.signalId()))) {
      return;
    }
    SignalRepository.SignalRow row = signals.find(event.signalId()).orElse(null);
    if (row == null || row.suggestedQty() == null) {
      return; // nothing to size — leave the signal ACTIVE for a manual take
    }
    int qty = row.suggestedQty().intValue();
    if (qty <= 0) {
      return;
    }
    boolean scalper = row.scalperDetail() != null;
    try {
      // Guarded CAS ACTIVE→TAKEN: only the winner opens a paper position, so a race with a concurrent
      // manual take (POST /signals/{id}/taken) or a duplicate SignalEmitted can't double-open the leg.
      if (signals.transitionIf(event.signalId(), "ACTIVE", "TAKEN")) {
        // the fill is the entry price captured on the signal (mirrors a manual take with no override).
        events.publishEvent(new SignalTaken(event.signalId(), qty, event.entryPrice(), scalper));
      }
    } catch (Exception e) {
      log.warn("auto-paper-trade failed for signal {}: {}", event.signalId(), e.getMessage());
    }
  }
}
