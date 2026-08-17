package in.arthayantra.strategysignal.schedule;

import java.time.Clock;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Tells market-data that one of this service's evening-window jobs has finished, so its 18:59
 * "can I shut the machine down yet?" check can see it.
 *
 * <p><b>Why this exists (review Major C, 2026-08-17).</b> {@code EveningChainCanary} in market-data
 * announces "chain complete — safe to shut down" at 18:59 against a HARD 19:00 shutdown. Four jobs
 * in THIS service run inside that window — {@code SwingBatchHeartbeat} 18:54, {@code
 * GraduationPromotionScheduler} 18:55, and {@code InsightSweeper}'s two sweeps at 18:56 and 18:57 —
 * and none of them was visible to that check. The sell-decision sweep begins two minutes before the
 * check and the check declared the evening finished over the top of it. The owner chose to extend
 * the coverage rather than narrow the claim: the question is whether the MACHINE can go off, and a
 * job in this container loses work to 19:00 exactly like one in market-data's.
 *
 * <p>⚠️ It carries TWO of those four. {@code SwingBatchHeartbeat} and {@code
 * GraduationPromotionScheduler} are {@code @ConditionalOnProperty} beans — disarmed, they do not
 * exist, so their {@code @Scheduled} never fires and nothing would ever report them, leaving the leg
 * PENDING every evening forever. Both are armed on the live stack today, which is what makes that
 * trap easy to walk into. The rule and the closure path are at {@code EveningChainCanary#EXPECTED}.
 *
 * <p><b>Why a push, over this road.</b> These jobs keep no durable terminal state anywhere, so
 * something had to be built regardless. {@code strategy.canary_runs} could not hold it — its {@code
 * status} is {@code CHECK (status IN ('CLAIMED','DONE'))} (strategy V052:36) and so cannot record a
 * failure — and a cross-schema read in either direction is against the standing convention (admin
 * V001:18; strategy V052:7-9 states the mirror of it outright). The one cross-service mechanism that
 * already exists runs THIS way: {@code artha.marketdata.base-url}, the same property seventeen other
 * clients here use. So the leg travels the existing road and lands in {@code marketdata.ingest_runs},
 * which is the table the canary already reads — the remote legs are then classified by the same code
 * as market-data's own rather than through a second, half-parallel state model.
 *
 * <p><b>⚠️ FAIL-SOFT, in both directions, and that asymmetry is deliberate.</b> The report is made
 * AFTER the job body has run and its outcome is captured first, so nothing about reporting can change
 * what the job did — a market-data that is down, slow or 400-ing costs a log line, never a swing
 * heartbeat. In the other direction the failure is safe too: a report that does not arrive leaves the
 * leg reading PENDING at 18:59, so the owner is told the chain is unfinished when it is in fact
 * finished. That is the direction this whole feature is built to fail in — a false "still pending"
 * costs a second look, a false "safe to shut down" costs the work.
 *
 * <p>Bound only when {@code artha.marketdata.base-url} is set, which it always is in compose and in
 * the mock stack; a bare unit-test context without it simply has no bean.
 */
@Component
public class EveningLegReporter {

  /**
   * The {@code ingest_runs} sources market-data allow-lists for this door.
   *
   * <p>Two of the four evening jobs, not all four: {@code SwingBatchHeartbeat} and {@code
   * GraduationPromotionScheduler} are {@code @ConditionalOnProperty} beans that simply do not exist
   * when disarmed, so nothing would ever report them and the leg would sit PENDING every evening
   * forever — the never-resolving alert market-data's canary already learned about from another
   * direction. See {@code EveningChainCanary#EXPECTED} for the full reasoning and the closure path.
   */
  public static final String SOURCE_INSIGHT_STRATEGY_EVIDENCE = "INSIGHT_STRATEGY_EVIDENCE";

  public static final String SOURCE_INSIGHT_SELL_DECISION = "INSIGHT_SELL_DECISION";

  private static final Logger log = LoggerFactory.getLogger(EveningLegReporter.class);

  private final RestClient restClient;
  private final Clock clock;

  /**
   * ⚠️ No off-switch knob, deliberately. One was written and removed: a {@code @Value} with no
   * compose passthrough is exactly #653 — unreachable from {@code .env}, so it would have been a
   * control that looks like a control and is not. The sixteen sibling clients here have no
   * off-switch either, and the send is already fail-soft in every direction.
   */
  public EveningLegReporter(
      RestClient.Builder builder, Clock clock, @Value("${artha.marketdata.base-url}") String baseUrl) {
    this.restClient = builder.baseUrl(baseUrl).build();
    this.clock = clock;
  }

  /**
   * Runs {@code job}, then reports how it went. The job's own exception is CAUGHT here rather than
   * rethrown — every call site already swallowed it into a warn log, so this preserves their
   * behaviour exactly while turning the outcome into a ledger row instead of only a log line. A
   * {@code @Scheduled} method that throws also takes its own schedule down, which is the other reason
   * none of these ever propagated.
   */
  public void report(String source, Runnable job) {
    OffsetDateTime startedAt = OffsetDateTime.now(clock);
    String error = null;
    try {
      job.run();
    } catch (RuntimeException e) {
      error = e.toString();
      log.warn("evening leg {} failed: {}", source, error);
    }
    send(source, error == null ? "SUCCESS" : "FAILURE", startedAt, OffsetDateTime.now(clock), error);
  }

  private void send(
      String source, String status, OffsetDateTime startedAt, OffsetDateTime finishedAt, String error) {
    try {
      restClient
          .post()
          .uri("/api/v1/market/health/evening-chain/legs")
          .body(new LegReport(source, status, startedAt, finishedAt, error))
          .retrieve()
          .toBodilessEntity();
    } catch (Exception e) {
      // Never fatal, and never rethrown: see the class javadoc. The leg simply reads PENDING at
      // 18:59, which is the safe direction.
      log.warn(
          "evening leg {} ran but could not be reported to market-data ({}) - the 18:59 check will"
              + " read it as still pending",
          source,
          e.getMessage());
    }
  }

  /** Mirrors market-data's {@code EveningChainLegController.LegReport}. */
  private record LegReport(
      String source,
      String status,
      OffsetDateTime startedAt,
      OffsetDateTime finishedAt,
      String error) {}
}
