package in.arthayantra.backtest.analytics;

import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Post-hoc OI-confluence attribution for a finished run, under {@code /api/v1/backtests/**}: buckets
 * the run's trades by the historical Connecting-Dots composite trend at each entry and reports the
 * per-bucket win-rate / avg-P&amp;L (does our OI gate predict outcomes?). Read-only and offline — it
 * never touches the replay/golden path.
 */
@RestController
@RequestMapping("/api/v1/backtests")
public class OiAttributionController {

  private final OiAttributionService service;

  /** Wires the attribution service. */
  public OiAttributionController(OiAttributionService service) {
    this.service = service;
  }

  /**
   * OI-confluence attribution for {@code backtestId}. {@code interval} is the OI token ({@code 5m},
   * default); {@code underlying} optionally overrides the index derived from the traded symbols.
   */
  @GetMapping("/{backtestId}/oi-attribution")
  public Map<String, Object> attribution(
      @PathVariable UUID backtestId,
      @RequestParam(required = false) String interval,
      @RequestParam(required = false) String underlying) {
    return service.attribution(backtestId, interval, underlying);
  }
}
