package in.arthayantra.strategysignal.notifier;

import in.arthayantra.strategysignal.notifier.NotificationRepository.Target;
import in.arthayantra.strategysignal.signals.SignalEmitted;
import in.arthayantra.strategysignal.signals.SignalEmitted.ScalpDetail;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
  private final String appBaseUrl;

  /**
   * Wires the audit repo, client, setup dedupe + the global enable flag/retry budget. {@code
   * appBaseUrl} is the deployed app origin used to build the {@code /orders} deep link (§18.4); blank
   * (the default) → the alert carries no link, since a relative path can't be tapped from a phone push.
   */
  public ScalpAlertService(
      NotificationRepository repo,
      NotifierClient client,
      ScalpAlertDedupe dedupe,
      @Value("${artha.notifier.scalp-alerts.enabled:false}") boolean enabled,
      @Value("${artha.notifier.retry-max-attempts:3}") int retryMax,
      @Value("${artha.notifier.app-base-url:}") String appBaseUrl) {
    this.repo = repo;
    this.client = client;
    this.dedupe = dedupe;
    this.enabled = enabled;
    this.retryMax = retryMax;
    this.appBaseUrl = appBaseUrl;
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

  private String body(SignalEmitted e) {
    ScalpDetail s = e.scalp();
    StringBuilder b =
        new StringBuilder()
            .append(s.tradeable())
            .append(" @ ")
            .append(nz(s.optionLtp()))
            .append(" · entry ")
            .append(nz(e.entryPrice()))
            .append(" · SL ")
            .append(nz(e.stopLoss()))
            .append(" · target ")
            .append(nz(e.target()))
            .append(" · confluence ")
            .append(nz(s.confluence()));
    // W4 6c: the Open=High probability read rides the alert when an open-high-low strategy graded it.
    if (s.ohTier() != null) {
      b.append(" · OIP ").append(s.ohTier()).append(" ").append(s.ohProbPct()).append("%");
    }
    // §18.4 payload extras: the stamped advisory qty (omitted honestly when the leg was unsized) and a
    // deep link that pre-fills the /orders paper ticket. Both ride the body text — ntfy/Telegram render
    // a URL there as tappable, so no channel-client change is needed.
    if (s.suggestedQty() != null) {
      b.append(" · qty ").append(s.suggestedQty().toPlainString());
    }
    String link = orderTicketLink(e);
    if (link != null) {
      b.append(" · take ").append(link);
    }
    return b.toString();
  }

  /**
   * Deep link into the {@code /orders} pre-fill paper ticket (§18.4/§18.1) for THIS signal. Carries the
   * signal id (the paper BE resolves the option leg + book from it), the option-leg symbol, the side
   * and the stamped qty, so a phone tap lands on a prefilled ticket. Returns {@code null} when no app
   * origin is configured ({@code artha.notifier.app-base-url} blank) — a relative path is untappable
   * from a push, so we omit the link rather than emit a dead one.
   */
  private String orderTicketLink(SignalEmitted e) {
    if (appBaseUrl == null || appBaseUrl.isBlank()) {
      return null;
    }
    ScalpDetail s = e.scalp();
    String base =
        appBaseUrl.endsWith("/") ? appBaseUrl.substring(0, appBaseUrl.length() - 1) : appBaseUrl;
    StringBuilder url =
        new StringBuilder(base)
            .append("/orders?sig=")
            .append(e.signalId())
            .append("&symbol=")
            .append(enc(e.exchange() + ":" + s.tradeable()))
            .append("&side=")
            .append(enc(e.side()));
    if (s.suggestedQty() != null) {
      url.append("&qty=").append(enc(s.suggestedQty().toPlainString()));
    }
    return url.toString();
  }

  private static String enc(String v) {
    return URLEncoder.encode(v, StandardCharsets.UTF_8);
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
