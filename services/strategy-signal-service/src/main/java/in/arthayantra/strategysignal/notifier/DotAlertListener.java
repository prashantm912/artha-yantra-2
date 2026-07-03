package in.arthayantra.strategysignal.notifier;

import in.arthayantra.strategysignal.signals.DotHealthCanary.DotInputAlert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Bridges the dot canary's in-process {@link DotInputAlert} events to the ops ntfy topic — the
 * same notifier←signals event direction as {@code SignalEmitted} (signals cannot import this
 * module; that is a cycle). Fire-and-forget: an alert delivery failure logs and never touches the
 * canary or the live signal path.
 */
@Component
public class DotAlertListener {

  private static final Logger log = LoggerFactory.getLogger(DotAlertListener.class);

  private final NotifierClient client;

  /** Wires the outbound channel client. */
  public DotAlertListener(NotifierClient client) {
    this.client = client;
  }

  /** One canary alert → one ntfy push (skipped silently when ntfy is unconfigured, e.g. mock). */
  @Async("notifierExecutor")
  @EventListener
  public void onDotInputAlert(DotInputAlert alert) {
    try {
      if (client.configured("NTFY")) {
        client.send("NTFY", alert.title(), alert.message());
      }
    } catch (RuntimeException e) {
      log.warn("dot canary ntfy push failed: {}", e.getMessage());
    }
  }
}
