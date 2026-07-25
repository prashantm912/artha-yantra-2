package in.arthayantra.strategysignal.notifier;

import in.arthayantra.strategysignal.signals.StrategyCoverageAlert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/** Bridges signals-owned T9 coverage alerts to the ops ntfy topic. */
@Component
public class StrategyCoverageAlertListener {

  private static final Logger log = LoggerFactory.getLogger(StrategyCoverageAlertListener.class);

  private final NotifierClient client;

  public StrategyCoverageAlertListener(NotifierClient client) {
    this.client = client;
  }

  @Async("notifierExecutor")
  @EventListener
  public void onStrategyCoverageAlert(StrategyCoverageAlert alert) {
    try {
      if (client.configured("NTFY")) {
        client.send("NTFY", alert.title(), alert.message());
      }
    } catch (RuntimeException e) {
      log.warn("strategy coverage ntfy push failed: {}", e.getMessage());
    }
  }
}
