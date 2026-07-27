package in.arthayantra.strategysignal.minervini;

import in.arthayantra.strategysignal.signals.SwingBatchRunRepository;
import in.arthayantra.strategysignal.swing.SwingBatchEngine;
import in.arthayantra.strategysignal.swing.SwingBatchRecorder;
import in.arthayantra.strategysignal.swing.SwingCatchUpStateRepository;
import in.arthayantra.strategysignal.swing.SwingMarketHoursGuard;
import in.arthayantra.strategysignal.swing.SwingSellDecisionService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ops/verify + sell-decision surface for the Minervini swing batch — a thin per-family shell over the
 * shared {@link SwingBatchRecorder}/{@link SwingSellDecisionService}, bound to the {@link
 * MinerviniDoctrine}. {@code POST /run} fires the daily batch on demand (the scheduler runs it at
 * 20:00 IST); {@code GET /sell-decisions} is the read-only triad over the open holdings; {@code
 * GET /admission-probe} is the ledger-F3 slot-cap exceedance history. Under {@code /api/v1/signals/**}
 * so the edge-gateway allowlist already covers it; all return TYPED records (never a Map — the strategy
 * ratchet).
 */
@RestController
@RequestMapping("/api/v1/signals/minervini-swing")
public class MinerviniSwingController {

  private final SwingBatchRecorder recorder;
  private final SwingSellDecisionService sellDecisions;
  private final SwingBatchRunRepository batchRuns;
  private final SwingCatchUpStateRepository catchUpState;
  private final SwingMarketHoursGuard marketHoursGuard;
  private final MinerviniDoctrine doctrine;

  /** Wires the shared recorder + sell-decision service + batch-run repo and the Minervini doctrine. */
  public MinerviniSwingController(
      SwingBatchRecorder recorder,
      SwingSellDecisionService sellDecisions,
      SwingBatchRunRepository batchRuns,
      SwingCatchUpStateRepository catchUpState,
      SwingMarketHoursGuard marketHoursGuard,
      MinerviniDoctrine doctrine) {
    this.recorder = recorder;
    this.sellDecisions = sellDecisions;
    this.batchRuns = batchRuns;
    this.catchUpState = catchUpState;
    this.marketHoursGuard = marketHoursGuard;
    this.doctrine = doctrine;
  }

  /**
   * Runs one daily swing batch now (entry pass + exit pass) and returns the counts. Goes through the
   * recorder (audit P0-4 review): a manual catch-up run records its {@code swing_batch_runs} marker,
   * else the did-not-run canary keeps alerting for a date the owner already ran.
   */
  @PostMapping("/run")
  public SwingBatchEngine.SwingRun run(
      @RequestParam(name = "force", defaultValue = "false") boolean force) {
    marketHoursGuard.check(force);
    return recorder.runAndRecord(doctrine);
  }

  /** The latest durable catch-up state for the Minervini family, or a typed empty response. */
  @GetMapping("/catchup-status")
  public CatchUpStatus catchUpStatus() {
    return catchUpState
        .latest(doctrine.batchName())
        .map(
            row ->
                new CatchUpStatus(
                    doctrine.batchName(),
                    row.sessionDate(),
                    row.status(),
                    row.attempts(),
                    row.reason(),
                    row.updatedAt()))
        .orElseGet(() -> CatchUpStatus.empty(doctrine.batchName()));
  }

  /** Typed response for the latest catch-up row; null row fields mean no row exists yet. */
  public record CatchUpStatus(
      String batch,
      @Schema(types = {"string", "null"}, format = "date") LocalDate sessionDate,
      @Schema(types = {"string", "null"}) String status,
      @Schema(types = {"integer", "null"}) Integer attempts,
      @Schema(types = {"string", "null"}) String reason,
      @Schema(types = {"string", "null"}, format = "date-time") OffsetDateTime updatedAt) {

    static CatchUpStatus empty(String batch) {
      return new CatchUpStatus(batch, null, null, null, null, null);
    }
  }

  /** The daily sell-decision triad for every open swing position (MV-9.3) — read-only. */
  @GetMapping("/sell-decisions")
  public SwingSellDecisionService.SwingSellReport sellDecisions() {
    return sellDecisions.report(doctrine);
  }

  /**
   * The ledger-F3 slot-cap admission probe history (measurement-only), newest first: per batch run, how
   * many funnel candidates would have entered vs how many the cap admitted, and the RS-ordered names it
   * dropped. {@code limit} is clamped to 1..200.
   */
  @GetMapping("/admission-probe")
  public SwingBatchRunRepository.AdmissionProbes admissionProbe(
      @RequestParam(defaultValue = "30") int limit) {
    return new SwingBatchRunRepository.AdmissionProbes(
        batchRuns.recentProbes(doctrine.batchName(), Math.min(Math.max(limit, 1), 200)));
  }
}
