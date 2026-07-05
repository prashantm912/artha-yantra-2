package in.arthayantra.strategysignal.manas;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ops/verify + sell-decision surface for the Manas Arora swing engine. {@code POST /run} fires the
 * daily batch on demand (the scheduler runs it at 20:05 IST); {@code GET /sell-decisions} is the
 * read-only triad over the open holdings. Under {@code /api/v1/signals/**} so the edge-gateway
 * allowlist already covers it; both return TYPED records (never a Map — the strategy ratchet). The
 * direct sibling of {@code MinerviniSwingController}.
 */
@RestController
@RequestMapping("/api/v1/signals/manas-arora-swing")
public class ManasAroraSwingController {

  private final ManasSwingRunRecorder recorder;
  private final ManasAroraSellDecisionService sellDecisions;

  /** Wires the run recorder + the sell-decision service. */
  public ManasAroraSwingController(
      ManasSwingRunRecorder recorder, ManasAroraSellDecisionService sellDecisions) {
    this.recorder = recorder;
    this.sellDecisions = sellDecisions;
  }

  /**
   * Runs one daily swing batch now (entry pass + exit pass) and returns the counts. Goes through
   * the recorder (audit P0-4 review): a manual catch-up run records its {@code swing_batch_runs}
   * marker, else the did-not-run canary keeps alerting for a date the owner already ran.
   */
  @PostMapping("/run")
  public ManasAroraSwingEngine.ManasSwingRun run() {
    return recorder.runAndRecord();
  }

  /** The daily sell-decision triad for every open Manas swing position — read-only. */
  @GetMapping("/sell-decisions")
  public ManasAroraSellDecisionService.ManasSellReport sellDecisions() {
    return sellDecisions.report();
  }
}
