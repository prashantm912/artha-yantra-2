package in.arthayantra.marketdata.canary;

import in.arthayantra.marketdata.ingest.IngestRunLedger;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one door another service uses to say "my leg of tonight's evening chain has finished".
 *
 * <p><b>Why this exists (review Major C, 2026-08-17).</b> {@link EveningChainCanary} announces "safe
 * to shut down" at 18:59, but FIVE jobs run inside that same window and the canary could not see one
 * of them: in strategy-signal the swing heartbeat (18:54), the graduation promotion eval (18:55) and
 * the two insight sweeps (18:56 and 18:57), plus market-data's own bhavcopy-close canary (18:58,
 * closed separately — it is in-process, so it just writes its own ledger row now). The sell-decision
 * sweep starts two minutes before the check and the check said the chain was complete over it.
 * Narrowing the claim to "market-data is done" was the cheaper fix and was declined by the owner: the
 * question the report answers is whether the MACHINE can be turned off, and a job in another
 * container is exactly as capable of losing work to the 19:00 shutdown.
 *
 * <p>Of the four strategy-signal jobs this door carries TWO — the insight sweeps. The other two are
 * {@code @ConditionalOnProperty} beans, and an unconditional expectation on a conditionally-loaded
 * producer is an alert that can never resolve; the reasoning is at {@code
 * EveningChainCanary#EXPECTED}, next to the list it kept them out of.
 *
 * <p><b>Why the leg is PUSHED here rather than PULLED from strategy-signal, and why the row lands in
 * {@code marketdata.ingest_runs}.</b> Four facts decided it, none of them preference:
 *
 * <ol>
 *   <li>Those jobs have NO durable terminal-state record of any kind today ({@code
 *       InsightSweeper:103-120} and {@code GraduationPromotionScheduler:34} simply run and log). So
 *       there was nothing to read, whichever transport was chosen — a state channel had to be built.
 *   <li>{@code strategy.canary_runs}, the obvious candidate for holding it, cannot: its {@code status}
 *       is {@code CHECK (status IN ('CLAIMED','DONE'))} (strategy V052:36), so it cannot record a
 *       FAILURE, and relaxing that is a migration on an applied file. Its semantics are also an
 *       alert-publishing door's claim, not a batch job's outcome.
 *   <li>Cross-schema reads are against the standing convention — admin V001:18 grants {@code
 *       ay_strategy} and {@code ay_marketdata} NO cross-schema privileges, and strategy V052:7-9
 *       states the mirror rule outright ("the strategy service must never read a marketdata table to
 *       decide whether its own canary ran"). Both services happen to connect as {@code artha}, so a
 *       schema-qualified read would physically work; that is precisely why the convention is worth
 *       keeping deliberately rather than by accident.
 *   <li>The one cross-service mechanism that DOES exist runs in this direction: {@code
 *       artha.marketdata.base-url} (strategy-signal application.yml:38-41, seventeen client classes,
 *       {@code PaperMarginClient:86} already POSTing). Adding a market-data-to-strategy-signal client
 *       would have been a new direction, a new base-url and a new compose passthrough.
 * </ol>
 *
 * <p>So the report travels the existing road, and lands in {@code marketdata.ingest_runs} — whose
 * {@code source} and {@code status} are free-form TEXT with no CHECK (V040), so this needs NO
 * migration, and which {@link EveningChainCanary} already reads. That last point is the real prize:
 * the remote legs are classified by the SAME code path as the local ones, including the per-source
 * expected-not-before boundary, rather than a second half-parallel state model.
 *
 * <p><b>⚠️ The source allow-list is a gate, not decoration.</b> This handler writes into the ledger
 * that decides whether the owner's machine may be shut down, so it accepts ONLY the sources the
 * evening tail actually reports. Anything else is refused — an unrecognised name cannot be used to
 * forge a terminal row for {@code BHAVCOPY} (which would open the screener carve-out) or for any
 * other leg this service owns and must judge for itself. Marked-up: market-data is still the single
 * writer of every source it produces; this door adds sources that only ever come from outside.
 *
 * <p>Unauthenticated, like every other service-to-service call on this port: the container is reachable
 * only on the compose network and the gateway is loopback-only. The blast radius of the worst case is
 * one spurious row for one of the allow-listed sources, which can move the evening report from
 * "pending" to "complete" for a job that is genuinely the reporter's own to declare.
 */
@RestController
@RequestMapping("/api/v1/market/health/evening-chain")
public class EveningChainLegController {

  /**
   * Exactly the strategy-signal legs this service expects — a wider door would let a caller mint a
   * terminal row for a leg market-data measures itself (a forged {@code BHAVCOPY} SUCCESS would also
   * open the screener carve-out). Two, not four: see {@code EveningChainCanary#EXPECTED} for why the
   * two {@code @ConditionalOnProperty} jobs are excluded.
   */
  static final Set<String> REPORTABLE =
      Set.of(
          IngestRunLedger.SOURCE_INSIGHT_STRATEGY_EVIDENCE,
          IngestRunLedger.SOURCE_INSIGHT_SELL_DECISION);

  private static final Set<String> TERMINAL_STATUSES = Set.of("SUCCESS", "FAILURE");

  private static final Logger log = LoggerFactory.getLogger(EveningChainLegController.class);

  private final IngestRunLedger ledger;

  public EveningChainLegController(IngestRunLedger ledger) {
    this.ledger = ledger;
  }

  /**
   * One finished evening-chain leg, as measured by the service that ran it.
   *
   * <p>{@code startedAt}/{@code finishedAt} are the REPORTER's own timings, not the moment this
   * arrived: the run happened in another process and the ledger row must carry when it actually ran,
   * or the expected-not-before boundary would judge it against the wrong clock.
   */
  public record LegReport(
      String source,
      String status,
      OffsetDateTime startedAt,
      OffsetDateTime finishedAt,
      @Schema(types = {"string", "null"}) String error) {}

  /** Whether the row landed, echoed so the reporter can log a lost report rather than assume. */
  public record LegRecorded(String source, boolean recorded) {}

  /**
   * Records one finished leg. 400 on an unknown source or a non-terminal status — a leg is reported
   * once, when it is over, so {@code RUNNING} has no meaning across this boundary and accepting it
   * would let a caller park the chain "in flight" indefinitely.
   */
  @PostMapping("/legs")
  public ResponseEntity<LegRecorded> recordLeg(@RequestBody LegReport report) {
    if (report == null
        || !REPORTABLE.contains(report.source())
        || !TERMINAL_STATUSES.contains(report.status())
        || report.startedAt() == null
        || report.finishedAt() == null) {
      log.warn("evening-chain leg report refused: {}", report);
      return ResponseEntity.badRequest()
          .body(new LegRecorded(report == null ? null : report.source(), false));
    }
    boolean recorded =
        ledger.recordCompleted(
            report.source(),
            report.status(),
            report.startedAt(),
            report.finishedAt(),
            report.error());
    return ResponseEntity.ok(new LegRecorded(report.source(), recorded));
  }
}
