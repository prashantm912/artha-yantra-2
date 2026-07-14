package in.arthayantra.strategysignal.notifier;

import in.arthayantra.strategysignal.signals.StarvationAlert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/** Bridges signal-starvation events to the configured ntfy topic. */
@Component
public class StarvationAlertListener {

  private static final Logger log = LoggerFactory.getLogger(StarvationAlertListener.class);

  private final NotifierClient client;

  /** Wires the outbound channel client. */
  public StarvationAlertListener(NotifierClient client) {
    this.client = client;
  }

  /** One starvation alert → one ntfy push (skipped silently when ntfy is unconfigured). */
  @Async("notifierExecutor")
  @EventListener
  public void onStarvation(StarvationAlert alert) {
    try {
      if (client.configured("NTFY")) {
        client.send("NTFY", alert.title(), alert.message());
      }
    } catch (RuntimeException e) {
      log.warn("signal starvation ntfy push failed: {}", e.getMessage());
    }
  }
}
