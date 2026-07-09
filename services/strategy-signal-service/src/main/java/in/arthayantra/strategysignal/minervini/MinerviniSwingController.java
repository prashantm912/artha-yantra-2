package in.arthayantra.strategysignal.minervini;

import in.arthayantra.strategysignal.swing.SwingBatchEngine;
import in.arthayantra.strategysignal.swing.SwingBatchRecorder;
import in.arthayantra.strategysignal.swing.SwingSellDecisionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ops/verify + sell-decision surface for the Minervini swing batch — a thin per-family shell over the
 * shared {@link SwingBatchRecorder}/{@link SwingSellDecisionService}, bound to the {@link
 * MinerviniDoctrine}. {@code POST /run} fires the daily batch on demand (the scheduler runs it at
 * 20:00 IST); {@code GET /sell-decisions} is the read-only triad over the open holdings. Under
 * {@code /api/v1/signals/**} so the edge-gateway allowlist already covers it; both return TYPED
 * records (never a Map — the strategy ratchet).
 */
@RestController
@RequestMapping("/api/v1/signals/minervini-swing")
public class MinerviniSwingController {

  private final SwingBatchRecorder recorder;
  private final SwingSellDecisionService sellDecisions;
  private final MinerviniDoctrine doctrine;

  /** Wires the shared recorder + sell-decision service and the Minervini doctrine. */
  public MinerviniSwingController(
      SwingBatchRecorder recorder,
      SwingSellDecisionService sellDecisions,
      MinerviniDoctrine doctrine) {
    this.recorder = recorder;
    this.sellDecisions = sellDecisions;
    this.doctrine = doctrine;
  }

  /**
   * Runs one daily swing batch now (entry pass + exit pass) and returns the counts. Goes through the
   * recorder (audit P0-4 review): a manual catch-up run records its {@code swing_batch_runs} marker,
   * else the did-not-run canary keeps alerting for a date the owner already ran.
   */
  @PostMapping("/run")
  public SwingBatchEngine.SwingRun run() {
    return recorder.runAndRecord(doctrine);
  }

  /** The daily sell-decision triad for every open swing position (MV-9.3) — read-only. */
  @GetMapping("/sell-decisions")
  public SwingSellDecisionService.SwingSellReport sellDecisions() {
    return sellDecisions.report(doctrine);
  }
}
