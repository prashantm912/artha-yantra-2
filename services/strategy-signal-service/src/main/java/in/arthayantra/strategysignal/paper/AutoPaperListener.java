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
  private final ApplicationEventPublisher events;

  /** Wires the risk toggle, the signal store, and the event bus the paper listener reads. */
  public AutoPaperListener(
      RiskService risk, SignalRepository signals, ApplicationEventPublisher events) {
    this.risk = risk;
    this.signals = signals;
    this.events = events;
  }

  /** Auto-take an emitted entry at its suggested qty when the toggle is ON. */
  @EventListener
  public void onSignalEmitted(SignalEmitted event) {
    if (!risk.autoPaperTradeEnabled()) {
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
      signals.transition(event.signalId(), "TAKEN");
      // the fill is the entry price captured on the signal (mirrors a manual take with no override).
      events.publishEvent(new SignalTaken(event.signalId(), qty, event.entryPrice(), scalper));
    } catch (Exception e) {
      log.warn("auto-paper-trade failed for signal {}: {}", event.signalId(), e.getMessage());
    }
  }
}
