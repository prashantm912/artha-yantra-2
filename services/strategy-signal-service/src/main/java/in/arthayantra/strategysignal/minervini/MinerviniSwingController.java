package in.arthayantra.strategysignal.minervini;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ops/verify + sell-decision surface for the Phase-9 Minervini swing engine. {@code POST /run} fires
 * the daily batch on demand (the scheduler runs it at 20:00 IST); {@code GET /sell-decisions} is the
 * read-only MV-9.3 triad over the open holdings. Under {@code /api/v1/signals/**} so the edge-gateway
 * allowlist already covers it; both return TYPED records (never a Map — the strategy ratchet).
 */
@RestController
@RequestMapping("/api/v1/signals/minervini-swing")
public class MinerviniSwingController {

  private final MinerviniSwingRunRecorder recorder;
  private final MinerviniSellDecisionService sellDecisions;

  /** Wires the run recorder + the sell-decision service. */
  public MinerviniSwingController(
      MinerviniSwingRunRecorder recorder, MinerviniSellDecisionService sellDecisions) {
    this.recorder = recorder;
    this.sellDecisions = sellDecisions;
  }

  /**
   * Runs one daily swing batch now (entry pass + exit pass) and returns the counts. Goes through
   * the recorder (audit P0-4 review): a manual catch-up run records its {@code swing_batch_runs}
   * marker, else the did-not-run canary keeps alerting for a date the owner already ran.
   */
  @PostMapping("/run")
  public MinerviniSwingEngine.SwingRun run() {
    return recorder.runAndRecord();
  }

  /** The daily sell-decision triad for every open swing position (MV-9.3) — read-only. */
  @GetMapping("/sell-decisions")
  public MinerviniSellDecisionService.Report sellDecisions() {
    return sellDecisions.report();
  }
}
