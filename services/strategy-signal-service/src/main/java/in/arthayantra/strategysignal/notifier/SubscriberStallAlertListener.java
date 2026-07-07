package in.arthayantra.strategysignal.notifier;

import in.arthayantra.strategysignal.signals.SubscriberHealthCanary.SubscriberStallAlert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Bridges the subscriber watchdog's in-process {@link SubscriberStallAlert} events to the ops ntfy
 * topic — the same notifier←signals event direction as {@code SignalEmitted}/{@code DotInputAlert}
 * (signals cannot import this module; that is a cycle). Fire-and-forget: a delivery failure logs and
 * never touches the canary or the live signal path.
 */
@Component
public class SubscriberStallAlertListener {

  private static final Logger log = LoggerFactory.getLogger(SubscriberStallAlertListener.class);

  private final NotifierClient client;

  /** Wires the outbound channel client. */
  public SubscriberStallAlertListener(NotifierClient client) {
    this.client = client;
  }

  /** One watchdog alert → one ntfy push (skipped silently when ntfy is unconfigured, e.g. mock). */
  @Async("notifierExecutor")
  @EventListener
  public void onSubscriberStall(SubscriberStallAlert alert) {
    try {
      if (client.configured("NTFY")) {
        client.send("NTFY", alert.title(), alert.message());
      }
    } catch (RuntimeException e) {
      log.warn("subscriber watchdog ntfy push failed: {}", e.getMessage());
    }
  }
}
