package in.arthayantra.marketdata.upstox;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketdata.upstox.ExpiredBackfillService.Status;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the expired-instruments backfill self-driving so the owner never has to re-POST it.
 *
 * <p>The backfill job is an in-memory daemon thread (a fire-and-forget POST) — ANY market-data /
 * Docker / host restart kills it and it does NOT auto-resume. This component closes that gap:
 *
 * <ul>
 *   <li><b>On boot</b> ({@link ApplicationReadyEvent}) it fires the coverage-aware resume — the
 *       reboot fix.
 *   <li><b>Hourly</b> ({@code @Scheduled}) it re-fires when the job is idle and work remains —
 *       recovering a mid-run crash/DNS-blip stall and picking up newly-expired contracts.
 * </ul>
 *
 * <p>Every trigger calls the existing {@link ExpiredBackfillService#triggerAsync} with the standard
 * defaults (NIFTY+SENSEX, trailing 365 days, 1-minute, {@code force=false}) — coverage-aware (skips
 * already-complete contracts) and lock-guarded (a redundant trigger 409s harmlessly). Gated on
 * {@code artha.marketdata.expired-backfill.auto-resume} AND the analytics client being present, so
 * the mock profile (no Upstox client) is a no-op.
 */
@Component
@EnableConfigurationProperties(ExpiredBackfillProperties.class)
public class ExpiredBackfillAutoResume {

  private static final Logger log = LoggerFactory.getLogger(ExpiredBackfillAutoResume.class);
  private static final List<String> UNDERLYINGS = List.of("NIFTY", "SENSEX");

  private final ExpiredBackfillService service;
  private final ExpiredBackfillRepository repo;
  private final ObjectProvider<UpstoxExpiredInstrumentsClient> clientProvider;
  private final ExpiredBackfillProperties props;

  /** Wires the backfill service + the gap probe + the (optional) analytics client + config. */
  public ExpiredBackfillAutoResume(
      ExpiredBackfillService service,
      ExpiredBackfillRepository repo,
      ObjectProvider<UpstoxExpiredInstrumentsClient> clientProvider,
      ExpiredBackfillProperties props) {
    this.service = service;
    this.repo = repo;
    this.clientProvider = clientProvider;
    this.props = props;
  }

  /** On startup: resume immediately if enabled + the analytics source is available. */
  @EventListener(ApplicationReadyEvent.class)
  public void onReady() {
    if (!enabledAndAvailable()) {
      return;
    }
    trigger("startup");
  }

  /** Hourly self-heal: resume if the job is idle and there is work to do. */
  @Scheduled(cron = "${artha.marketdata.expired-backfill.self-heal-cron}")
  public void selfHeal() {
    if (!enabledAndAvailable()) {
      return;
    }
    Status s = service.status();
    if ("RUNNING".equals(s.state())) {
      return; // already working — never double-trigger (the service lock would 409 anyway)
    }
    if (shouldResume(s)) {
      trigger("self-heal");
    }
  }

  /** Resume when never-run / failed, when incomplete coverage remains, or a daily catch-up is due. */
  private boolean shouldResume(Status s) {
    if ("NEVER_RUN".equals(s.state()) || "FAILED".equals(s.state())) {
      return true;
    }
    // "Work remaining" must mean work the resume can actually REACH: scope the probe to the same
    // trailing-year window trigger() walks. An incomplete contract whose expiry rolled out of that
    // window is unreachable (Upstox no longer serves it), so it must not keep the self-heal re-walking
    // the roster every hour and starving the shared Upstox limiter.
    if (repo.hasIncompleteCoverage(LocalDate.now(Ist.ZONE).minusYears(1))) {
      return true;
    }
    // state == OK with every contract complete: re-walk at most once per catch-up window (new expiries).
    Instant last = s.lastRun();
    return last == null || Duration.between(last, Instant.now()).toHours() >= props.catchupHours();
  }

  private boolean enabledAndAvailable() {
    return props.autoResume() && clientProvider.getIfAvailable() != null;
  }

  private void trigger(String reason) {
    try {
      LocalDate to = LocalDate.now(Ist.ZONE);
      service.triggerAsync(UNDERLYINGS, to.minusYears(1), to, null, false);
      log.info("expired-backfill auto-resume ({}): coverage-aware resume started", reason);
    } catch (RuntimeException e) {
      // 409 already-running or 503 source-absent (a race with another trigger) — benign; skip quietly.
      log.debug("expired-backfill auto-resume ({}) skipped: {}", reason, e.getMessage());
    }
  }
}
