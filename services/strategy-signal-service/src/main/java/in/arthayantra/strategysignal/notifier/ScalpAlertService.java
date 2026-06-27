package in.arthayantra.strategysignal.notifier;

import in.arthayantra.strategysignal.notifier.NotificationRepository.Target;
import in.arthayantra.strategysignal.signals.SignalEmitted;
import in.arthayantra.strategysignal.signals.SignalEmitted.ScalpDetail;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Scalp-signal alerts (Z1): an additive, LIVE-only push for SCALPER entries. It subscribes to the
 * same in-process {@link SignalEmitted} event as {@link NotifierService}, but fires ONLY when the
 * event carries the {@link ScalpDetail} side-channel (a scalper leg) — non-scalper signals fall
 * straight through. The alert renders the underlying, option side/strike, BUY/SELL, entry/SL/target
 * and confluence via the existing {@link NotifierClient}.
 *
 * <p>Gating (default OFF — never spams):
 *
 * <ul>
 *   <li>global {@code artha.notifier.scalp-alerts.enabled} (default {@code false}),
 *   <li>the per-strategy opt-in + channel already on the {@code strategies} row (reused, no new
 *       column/migration),
 *   <li>setup-level dedupe ({@link ScalpAlertDedupe}) so the same leg never re-fires within a window.
 * </ul>
 *
 * <p>Parity: {@link SignalEmitted} is published only by the live {@code SignalEngine} — deterministic
 * replay never reaches it, so this listener (like {@code MarketOiClient}) cannot touch the golden
 * path. It does not change the generic {@link NotifierService} path.
 */
@Service
public class ScalpAlertService {

  private static final Logger log = LoggerFactory.getLogger(ScalpAlertService.class);

  private final NotificationRepository repo;
  private final NotifierClient client;
  private final ScalpAlertDedupe dedupe;
  private final boolean enabled;
  private final int retryMax;

  /** Wires the audit repo, client, setup dedupe + the global enable flag/retry budget. */
  public ScalpAlertService(
      NotificationRepository repo,
      NotifierClient client,
      ScalpAlertDedupe dedupe,
      @Value("${artha.notifier.scalp-alerts.enabled:false}") boolean enabled,
      @Value("${artha.notifier.retry-max-attempts:3}") int retryMax) {
    this.repo = repo;
    this.client = client;
    this.dedupe = dedupe;
    this.enabled = enabled;
    this.retryMax = retryMax;
  }

  /** In-process async push for an emitted SCALPER ENTRY signal (opt-in, deduped, bounded retry). */
  @Async("notifierExecutor")
  @EventListener
  public void onScalpSignal(SignalEmitted e) {
    if (!enabled || e.scalp() == null) {
      return; // feature off, or not a scalper signal
    }
    Target target = repo.targetForVersion(e.strategyVersionId()).orElse(null);
    if (target == null || !target.enabled() || target.channel() == null) {
      return; // strategy not opted in
    }
    ScalpDetail s = e.scalp();
    String setupKey =
        target.strategyId() + "|" + s.underlying() + "|" + s.optionSide() + "|" + nz(s.strike());
    if (!dedupe.admit(setupKey)) {
      repo.record(e.signalId(), target.strategyId(), target.channel(), "SUPPRESSED", 1, "SCALP_DEDUPE");
      return;
    }
    sendWithRetry(e, target);
  }

  private void sendWithRetry(SignalEmitted e, Target target) {
    String title = title(e);
    String body = body(e);
    RuntimeException last = null;
    for (int attempt = 1; attempt <= retryMax; attempt++) {
      try {
        client.send(target.channel(), title, body);
        repo.record(e.signalId(), target.strategyId(), target.channel(), "SENT", attempt, "SCALP");
        return;
      } catch (RuntimeException ex) {
        last = ex;
        backoff(attempt);
      }
    }
    repo.record(
        e.signalId(), target.strategyId(), target.channel(), "FAILED", retryMax,
        last == null ? "SCALP" : "SCALP " + last.getMessage());
    log.warn("scalp alert giving up on signal #{} after {} attempts", e.signalId(), retryMax);
  }

  /** "ArthaYantra scalp BUY NIFTY 22500 CE" — underlying + option-side/strike at a glance. */
  private static String title(SignalEmitted e) {
    ScalpDetail s = e.scalp();
    return "ArthaYantra scalp "
        + e.side()
        + " "
        + s.underlying()
        + " "
        + nz(s.strike())
        + " "
        + s.optionSide();
  }

  private static String body(SignalEmitted e) {
    ScalpDetail s = e.scalp();
    return s.tradeable()
        + " @ "
        + nz(s.optionLtp())
        + " · entry "
        + nz(e.entryPrice())
        + " · SL "
        + nz(e.stopLoss())
        + " · target "
        + nz(e.target())
        + " · confluence "
        + nz(s.confluence())
        // W4 6c: the Open=High probability read rides the alert when an open-high-low strategy graded it.
        + (s.ohTier() == null ? "" : " · OIP " + s.ohTier() + " " + s.ohProbPct() + "%");
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
