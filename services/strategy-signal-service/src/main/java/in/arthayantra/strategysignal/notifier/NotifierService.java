package in.arthayantra.strategysignal.notifier;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.strategysignal.notifier.FloodControl.Decision;
import in.arthayantra.strategysignal.notifier.NotificationRepository.Target;
import in.arthayantra.strategysignal.signals.SignalEmitted;
import java.math.BigDecimal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * The notifier (Phase 41 / E-14): subscribes IN-PROCESS to {@link SignalEmitted}, pushes opted-in
 * strategies' signals to ntfy/Telegram with flood control + bounded retry + a per-attempt audit. The
 * payload is direction/entry/SL/target/composite-vs-threshold — NEVER credentials. Opt-in lives on
 * the strategies row, so toggling it never mints a version or perturbs a D18 checksum.
 */
@Service
public class NotifierService {

  private static final Logger log = LoggerFactory.getLogger(NotifierService.class);

  private final NotificationRepository repo;
  private final NotifierClient client;
  private final FloodControl flood;
  private final int retryMax;

  /** Wires the audit repo, client, flood control + retry budget. */
  public NotifierService(
      NotificationRepository repo,
      NotifierClient client,
      FloodControl flood,
      @Value("${artha.notifier.retry-max-attempts:3}") int retryMax) {
    this.repo = repo;
    this.client = client;
    this.flood = flood;
    this.retryMax = retryMax;
  }

  /** In-process async push for an emitted ENTRY signal (bounded retry, flood-controlled). */
  @Async("notifierExecutor")
  @EventListener
  public void onSignal(SignalEmitted e) {
    Target target = repo.targetForVersion(e.strategyVersionId()).orElse(null);
    if (target == null || !target.enabled() || target.channel() == null) {
      return; // not opted in
    }
    Decision decision = flood.admit(target.strategyId());
    if (decision != Decision.ALLOW) {
      repo.record(e.signalId(), target.strategyId(), target.channel(), "SUPPRESSED", 1, decision.name());
      int suppressed = flood.capSummaryDue();
      if (suppressed > 0) {
        trySend(target.channel(), "ArthaYantra — " + suppressed + " signals suppressed", "Hourly cap reached.");
      }
      return;
    }
    String title = "ArthaYantra " + e.side() + " " + e.exchange() + ":" + e.tradingsymbol();
    sendWithRetry(e.signalId(), target.strategyId(), target.channel(), title, payload(e));
  }

  /** Manual test-send (422 when disabled/unconfigured); audited. */
  public void sendTest(UUID strategyId) {
    Target target =
        repo.targetForStrategy(strategyId)
            .orElseThrow(
                () -> new ApiException(404, ErrorCodes.NOT_FOUND_RESOURCE, "strategy not found"));
    if (!target.enabled() || target.channel() == null || !client.configured(target.channel())) {
      throw new ApiException(
          422, ErrorCodes.VALIDATION_FAILED, "notifications disabled or channel unconfigured");
    }
    client.send(target.channel(), "ArthaYantra test", "Test notification — no live signal.");
    repo.record(null, strategyId, target.channel(), "SENT", 1, "test");
  }

  private String payload(SignalEmitted e) {
    return e.side()
        + " entry "
        + nz(e.entryPrice())
        + " · SL "
        + nz(e.stopLoss())
        + " · target "
        + nz(e.target())
        + " · score "
        + nz(e.composite())
        + " vs "
        + nz(e.threshold());
  }

  private void sendWithRetry(Long signalId, UUID strategyId, String channel, String title, String body) {
    RuntimeException last = null;
    for (int attempt = 1; attempt <= retryMax; attempt++) {
      try {
        client.send(channel, title, body);
        repo.record(signalId, strategyId, channel, "SENT", attempt, null);
        return;
      } catch (RuntimeException ex) {
        last = ex;
        backoff(attempt);
      }
    }
    repo.record(signalId, strategyId, channel, "FAILED", retryMax, last == null ? null : last.getMessage());
    log.warn("notifier giving up on signal #{} after {} attempts", signalId, retryMax);
  }

  private void trySend(String channel, String title, String body) {
    try {
      client.send(channel, title, body);
    } catch (RuntimeException ex) {
      log.warn("notifier summary push failed: {}", ex.getMessage());
    }
  }

  private void backoff(int attempt) {
    try {
      Thread.sleep((long) (Math.pow(2, attempt - 1) * 200));
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    }
  }

  private static String nz(BigDecimal v) {
    return v == null ? "—" : v.toPlainString();
  }
}
