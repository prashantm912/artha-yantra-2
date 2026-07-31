package in.arthayantra.strategysignal.notifier;

import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Boot-time trigger for the notifier-health check's missed-cron catch-up (task_7e754e11). Spring's
 * {@code @Scheduled} never replays a fire that ticked while the process was down, and the owner's
 * machine routinely boots ~08:56 IST — so the 08:30 delivery-health check, whose whole job is to
 * notice that the push channel died overnight, is precisely what does not run on the mornings the
 * stack was down overnight (E2E audit 2026-07-31 §2.1: down 02:29–08:56, neither morning canary
 * fired). This fires {@link NotifierHealthCheck#catchUpIfMissed()} once per start; that method owns
 * all the gating (fire-time-passed plus the durable once-per-IST-day claim), so a boot before the
 * fire time and a second boot the same morning are both no-ops. The sibling half — market-data's
 * 08:45 ingest-coverage canary — is PR #1155.
 *
 * <p><b>Why a separate, disableable bean.</b> A startup runner on the shared singleton Testcontainers
 * DB is a cached-context hazard: Spring caches test contexts, so this would fire inside unrelated
 * tests long after the context that created it, INSERTing into {@code canary_runs} (and pushing) at
 * an arbitrary later moment. It therefore needs an off switch independent of the check itself.
 * {@code artha.notifier.health.startup-catchup=false} is registered on {@code
 * StrategySignalIntegrationTestBase}, the substrate every IT context extends; production keeps it on
 * via {@code matchIfMissing}. There is NO in-hierarchy opt-out (a subclass {@code
 * @DynamicPropertySource} runs BEFORE the base's, so the base registers last and wins) — a test that
 * wants this path must not extend the substrate, or call {@code catchUpIfMissed()} directly.
 *
 * <p><b>Why {@code notifierExecutor} and not a scheduler pool.</b> The listener must not hold
 * {@code ApplicationReadyEvent} for a DB read plus two best-effort pushes (bounded by the global
 * 2 s connect / 30 s read timeouts, not by anything this class controls), so the work is dispatched
 * off-thread. {@code notifierExecutor} is this module's own pool, built precisely to absorb notifier
 * pushes and their retries — a stall here degrades only other notifier pushes, which is the risk
 * those pushes already carry. The alternatives are all worse: the DEFAULT {@code taskScheduler} is
 * the single thread that also carries {@code PaperScheduler.bracketEvaluation} (the 15-second
 * stop-loss sweep), and {@code monitorTaskScheduler} is explicitly fenced (audit BEJ-01 / #919) for
 * pure in-memory detectors doing bounded local work — every detector on it offloads its own push via
 * {@code @Async("notifierExecutor")} exactly so no blocking HTTP ever lands on that thread. This
 * would have been the first task to break that fence.
 */
@Component
@ConditionalOnProperty(
    name = "artha.notifier.health.startup-catchup",
    havingValue = "true",
    matchIfMissing = true)
public class NotifierHealthCatchUp {

  private static final Logger log = LoggerFactory.getLogger(NotifierHealthCatchUp.class);

  private final NotifierHealthCheck healthCheck;
  private final Executor notifierExecutor;

  /** Wires the check and the notifier pool the one-shot runs on. */
  public NotifierHealthCatchUp(
      NotifierHealthCheck healthCheck, @Qualifier("notifierExecutor") Executor notifierExecutor) {
    this.healthCheck = healthCheck;
    this.notifierExecutor = notifierExecutor;
  }

  /** Off-thread by construction so a slow check never delays startup. */
  @EventListener(ApplicationReadyEvent.class)
  public void onStartup() {
    notifierExecutor.execute(this::runCatchUp);
  }

  private void runCatchUp() {
    try {
      healthCheck.catchUpIfMissed();
    } catch (RuntimeException failure) {
      log.warn("notifier health boot catch-up failed: {}", failure.getMessage());
    }
  }
}
