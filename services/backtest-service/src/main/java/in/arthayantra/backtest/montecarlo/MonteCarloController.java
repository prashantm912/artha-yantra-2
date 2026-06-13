package in.arthayantra.backtest.montecarlo;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The §D.5 on-demand Monte Carlo surface: {@code GET /api/v1/backtests/{id}/montecarlo?n=&seed=}.
 * Defaults to N=1000 with a fresh recorded seed; the summary is persisted on first computation and
 * served from there on repeat calls (§D.16).
 */
@RestController
@RequestMapping("/api/v1/backtests")
public class MonteCarloController {

  private final MonteCarloService service;

  /** Wires the service. */
  public MonteCarloController(MonteCarloService service) {
    this.service = service;
  }

  /** Seeded bootstrap of the run's persisted trades (404 unknown run, 422 no closed trades). */
  @GetMapping("/{backtestId}/montecarlo")
  public JsonNode montecarlo(
      @PathVariable UUID backtestId,
      @RequestParam(required = false) Integer n,
      @RequestParam(required = false) Long seed) {
    return service.compute(backtestId, n, seed);
  }
}
