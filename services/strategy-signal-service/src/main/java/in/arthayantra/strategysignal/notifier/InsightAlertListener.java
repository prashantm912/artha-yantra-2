package in.arthayantra.strategysignal.notifier;

import in.arthayantra.strategysignal.signals.InsightDeliveryAlert;
import in.arthayantra.strategysignal.notifier.NotificationRepository.Target;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/** Bridges insight delivery events to the existing notifier client and audit ledger. */
@Component
public class InsightAlertListener {

  private static final Logger log = LoggerFactory.getLogger(InsightAlertListener.class);

  private final NotificationRepository repository;
  private final NotifierClient client;
  private final int retryMax;

  public InsightAlertListener(
      NotificationRepository repository,
      NotifierClient client,
      @Value("${artha.notifier.retry-max-attempts:3}") int retryMax) {
    this.repository = repository;
    this.client = client;
    this.retryMax = retryMax;
  }

  /** One configured insight channel → one bounded-retry delivery with an audit row. */
  @Async("notifierExecutor")
  @EventListener
  public void onInsightDelivery(InsightDeliveryAlert alert) {
    if (alert == null) {
      return;
    }
    String channel = alert.channel();
    if (!client.configured(channel)) {
      return;
    }
    UUID strategyId = strategyId(alert.scope());
    if (strategyId != null) {
      Target target = repository.targetForStrategy(strategyId).orElse(null);
      if (target == null || !target.enabled() || !channel.equals(target.channel())) {
        return;
      }
    }
    sendWithRetry(
        alert.insightId(),
        strategyId,
        channel,
        "ArthaYantra " + alert.title(),
        alert.explanation() + "\nScope: " + alert.scope());
  }

  private void sendWithRetry(
      UUID insightId, UUID strategyId, String channel, String title, String body) {
    RuntimeException last = null;
    for (int attempt = 1; attempt <= retryMax; attempt++) {
      try {
        client.send(channel, title, body);
        repository.recordInsight(insightId, strategyId, channel, "SENT", attempt, null);
        return;
      } catch (RuntimeException ex) {
        last = ex;
        backoff(attempt);
      }
    }
    repository.recordInsight(
        insightId,
        strategyId,
        channel,
        "FAILED",
        retryMax,
        last == null ? null : last.getMessage());
    log.warn("insight notifier giving up on {} after {} attempts", insightId, retryMax);
  }

  private void backoff(int attempt) {
    try {
      Thread.sleep((long) (Math.pow(2, attempt - 1) * 200));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static UUID strategyId(String scope) {
    if (scope == null || !scope.startsWith("strategy:")) {
      return null;
    }
    try {
      return UUID.fromString(scope.substring("strategy:".length()));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
