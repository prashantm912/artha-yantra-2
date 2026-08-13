package in.arthayantra.strategysignal.paper;

import in.arthayantra.strategysignal.notifier.NotifierClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists a Manas pyramid-add/fresh-entry open-risk-cap TRIP audit row (cross-vendor review Major
 * 3, round 4, 2026-08-02). A SEPARATE bean (not a self-invocation, mirroring {@link
 * PaperOrderRejectionRecorder}) so the {@link Propagation#REQUIRES_NEW} proxy actually engages.
 *
 * <p>{@link RiskService#recordPyramidRiskCapBreach} wraps the call to {@link #record} fail-soft AT
 * THE CALL SITE — the round-3 version instead put the try/catch INSIDE this method's own body,
 * which can only catch what the body itself throws (the audit write). It CANNOT catch a failure in
 * the surrounding Spring transaction interceptor: connection acquisition BEFORE this method's body
 * ever runs, or a COMMIT failure AFTER it returns — both throw from outside this method's stack
 * frame, straight to whoever called {@code recordPyramidRiskCapBreach}. Both its callers need that
 * failure to never propagate — {@code PaperService#openOrder} calls it from inside its OWN {@code
 * @Transactional} boundary, immediately before the refusal throw (an uncaught failure here would
 * corrupt that rollback), and {@code SwingBatchEngine}'s entry pass calls it via {@code
 * PaperEmissionGuard} BEFORE the exit pass runs, the batch's ONLY exit evaluator for open positions
 * (an uncaught failure there would abort the whole run and skip every stop evaluation that day —
 * the same "an observability write must never break the run" rule {@code SwingBatchRecorder}'s
 * flag-snapshot {@code capture()} documents). The try/catch belongs at {@code
 * RiskService#recordPyramidRiskCapBreach}, the ONE place both callers already funnel through, not
 * duplicated at each of them.
 *
 * <p>The ntfy push keeps its OWN internal catch (unchanged from round 3): a notifier hiccup must
 * stay isolated from the audit write and must never roll back this transaction (the default
 * rollback-on-RuntimeException behaviour would otherwise undo the audit row too, over a failure
 * that has nothing to do with whether the audit itself landed).
 */
@Component
public class PyramidRiskCapAuditor {

  private static final Logger log = LoggerFactory.getLogger(PyramidRiskCapAuditor.class);

  private final RiskSettingsRepository settings;
  private final NotifierClient notifier;

  /** Wires the audit ledger + ntfy notifier. */
  public PyramidRiskCapAuditor(RiskSettingsRepository settings, NotifierClient notifier) {
    this.settings = settings;
    this.notifier = notifier;
  }

  /**
   * Writes the {@code risk_audit} TRIP row and, when {@code alert} is set, pushes an ntfy alert — in
   * a fresh transaction.
   *
   * <p>{@code alert} is false for the 2nd..Nth refusal of an IST day (2026-08-13): the ROW is written
   * for EVERY distinct refused symbol because it is the measurement substrate the owner tunes the cap
   * from, while the push stays deduped per book per day so a 16-refusal run cannot become 16 ntfy
   * notifications. {@link RiskService#recordPyramidRiskCapBreach} owns both dedup decisions and its
   * javadoc carries the measured 4:1 undercount that motivated the split; this bean only obeys.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void record(String book, String key, String detail, boolean alert) {
    settings.audit(book, key, "TRIP", detail);
    if (alert) {
      pushAlert("ArthaYantra Risk — pyramid open-risk cap (" + book + ")", detail);
    }
  }

  private void pushAlert(String title, String message) {
    try {
      if (notifier.configured("NTFY")) {
        notifier.send("NTFY", title, message);
      }
    } catch (RuntimeException e) {
      log.warn("risk governor ntfy push failed: {}", e.getMessage());
    }
  }
}
