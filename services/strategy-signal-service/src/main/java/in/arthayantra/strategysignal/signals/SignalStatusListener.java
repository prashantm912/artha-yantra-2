package in.arthayantra.strategysignal.signals;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Serialises a {@link SignalStatusChanged} into the {@code signals.status} Redis frame (audit
 * §7.2.1). Fires AFTER_COMMIT — a transition rolled back by its enclosing transaction (an engine /
 * swing / settle path) never emits a phantom frame — with {@code fallbackExecution = true} so the
 * many callers that mutate OUTSIDE any transaction (the manual {@code /signals/{id}/taken|dismiss}
 * controller — JdbcTemplate autocommits) still publish, synchronously and inline. The book is
 * resolved here (a read, off the mutation's connection) rather than carried on the event, so the
 * event record stays a minimal domain fact. Fail-soft is handled inside {@link
 * SignalPublisher#publishStatus} — a broadcast hiccup never breaks the mutation it reports.
 */
@Component
public class SignalStatusListener {

  private static final Logger log = LoggerFactory.getLogger(SignalStatusListener.class);

  private final SignalRepository repository;
  private final SignalPublisher publisher;

  /** Wires the book resolver + the Redis publisher. */
  public SignalStatusListener(SignalRepository repository, SignalPublisher publisher) {
    this.repository = repository;
    this.publisher = publisher;
  }

  /** Publishes the status-transition frame for one committed signal mutation. */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onStatusChanged(SignalStatusChanged e) {
    try {
      publisher.publishStatus(
          e.signalId(), e.toStatus(), repository.bookForSignal(e.signalId()), e.fromStatus());
    } catch (Exception ex) {
      log.warn("signal.status frame not published for #{}: {}", e.signalId(), ex.getMessage());
    }
  }
}
