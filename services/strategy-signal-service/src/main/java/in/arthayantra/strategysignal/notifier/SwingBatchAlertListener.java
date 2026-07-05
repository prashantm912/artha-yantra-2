package in.arthayantra.strategysignal.notifier;

import in.arthayantra.strategysignal.signals.SwingBatchAlert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Bridges the swing-batch chain's in-process {@link SwingBatchAlert} events (batch summaries,
 * failures, and the did-not-run canary — audit P0-4/H10) to the ops ntfy topic — the same
 * notifier←signals event direction as {@link DotAlertListener}. Fire-and-forget: a delivery
 * failure logs and never touches the batch or the canary.
 */
@Component
public class SwingBatchAlertListener {

  private static final Logger log = LoggerFactory.getLogger(SwingBatchAlertListener.class);

  private final NotifierClient client;

  /** Wires the outbound channel client. */
  public SwingBatchAlertListener(NotifierClient client) {
    this.client = client;
  }

  /** One batch alert → one ntfy push (skipped silently when ntfy is unconfigured, e.g. mock). */
  @Async("notifierExecutor")
  @EventListener
  public void onSwingBatchAlert(SwingBatchAlert alert) {
    try {
      if (client.configured("NTFY")) {
        client.send("NTFY", alert.title(), alert.message());
      }
    } catch (RuntimeException e) {
      log.warn("swing batch ntfy push failed: {}", e.getMessage());
    }
  }
}
