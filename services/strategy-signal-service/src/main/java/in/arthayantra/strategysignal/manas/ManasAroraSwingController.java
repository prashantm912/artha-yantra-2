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

  private final ManasAroraSwingEngine engine;
  private final ManasAroraSellDecisionService sellDecisions;

  /** Wires the swing engine + the sell-decision service. */
  public ManasAroraSwingController(
      ManasAroraSwingEngine engine, ManasAroraSellDecisionService sellDecisions) {
    this.engine = engine;
    this.sellDecisions = sellDecisions;
  }

  /** Runs one daily swing batch now (entry pass + exit pass) and returns the counts. */
  @PostMapping("/run")
  public ManasAroraSwingEngine.ManasSwingRun run() {
    return engine.runDaily();
  }

  /** The daily sell-decision triad for every open Manas swing position — read-only. */
  @GetMapping("/sell-decisions")
  public ManasAroraSellDecisionService.ManasSellReport sellDecisions() {
    return sellDecisions.report();
  }
}
