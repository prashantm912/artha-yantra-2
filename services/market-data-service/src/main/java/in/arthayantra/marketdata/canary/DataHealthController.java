package in.arthayantra.marketdata.canary;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The canary's read surface (roadmap F4): evaluates fresh on every GET — the dashboard strip polls
 * it and the 09:42 live-health agent reads it first, deep-diving only on non-GREEN.
 */
@RestController
@RequestMapping("/api/v1/market/health")
public class DataHealthController {

  private final DataHealthCanary canary;

  /** Wires the canary. */
  public DataHealthController(DataHealthCanary canary) {
    this.canary = canary;
  }

  /** Current data-plane health: tick/bar divergence + capture freshness. */
  @GetMapping("/data")
  public CanaryReport data() {
    return canary.evaluate();
  }
}
