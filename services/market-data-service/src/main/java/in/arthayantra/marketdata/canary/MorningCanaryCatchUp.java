package in.arthayantra.marketdata.canary;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Boot-time trigger for the morning canaries' missed-cron catch-up (task_e2e01c). Spring's
 * {@code @Scheduled} never replays a fire that ticked while the process was down, and the owner's
 * machine routinely boots ~08:56 IST — so the 08:45 ingest-coverage sweep, whose whole job is to
 * catch a broken overnight batch, is precisely what does not run on the mornings the stack was down
 * overnight (E2E audit 2026-07-31 §2.1: down 02:29–08:56, neither morning canary fired). This fires
 * {@link IngestCoverageCanary#catchUpIfMissed()} once per start; that method owns all the gating
 * (live-only, fire-time-passed, trading-day, and the durable once-per-IST-day claim), so a boot
 * before the fire time, a second boot the same morning, a holiday and a mock stack are all no-ops.
 *
 * <p><b>Why a separate, disableable bean</b> — same reasoning as {@code BhavcopyStartupCatchup}: a
 * startup runner on the shared singleton Testcontainers DB is a cached-context hazard (Spring caches
 * test contexts, so it fires inside unrelated tests long after the context that created it), so it
 * needs an off switch that is independent of the canary itself. {@code
 * artha.ingest-canary.startup-catchup=false} is registered on {@code MarketDataIntegrationTestBase},
 * the substrate all IT contexts extend; production keeps it on via {@code matchIfMissing}. There is
 * NO in-hierarchy opt-out (the base's {@code @DynamicPropertySource} runs LAST and wins) — a test
 * that wants this path must not extend the substrate, or call {@code catchUpIfMissed()} directly.
 *
 * <p>Dispatched onto the detector pool rather than run inline: the listener must not hold
 * {@code ApplicationReadyEvent} for a DB sweep plus a best-effort ntfy push (bounded by the global
 * 60 s read-timeout, not by anything this class controls). {@code monitorTaskScheduler} is the right
 * pool by its own fence — this is a pure liveness DETECTOR that reads and alerts, exactly like
 * {@code DataHealthCanary.sweep} which already lives there — and it is a single one-shot task.
 */
@Component
@ConditionalOnProperty(
    name = "artha.ingest-canary.startup-catchup",
    havingValue = "true",
    matchIfMissing = true)
public class MorningCanaryCatchUp {

  private static final Logger log = LoggerFactory.getLogger(MorningCanaryCatchUp.class);

  private final IngestCoverageCanary ingestCoverage;
  private final ThreadPoolTaskScheduler monitorTaskScheduler;

  /** Wires the canary and the detector pool the one-shot runs on. */
  public MorningCanaryCatchUp(
      IngestCoverageCanary ingestCoverage,
      @Qualifier("monitorTaskScheduler") ThreadPoolTaskScheduler monitorTaskScheduler) {
    this.ingestCoverage = ingestCoverage;
    this.monitorTaskScheduler = monitorTaskScheduler;
  }

  /** Off-thread by construction so a slow sweep never delays startup. */
  @EventListener(ApplicationReadyEvent.class)
  public void onStartup() {
    monitorTaskScheduler.execute(this::runCatchUp);
  }

  private void runCatchUp() {
    try {
      ingestCoverage.catchUpIfMissed();
    } catch (RuntimeException failure) {
      log.warn("ingest canary boot catch-up failed: {}", failure.getMessage());
    }
  }
}
