package in.arthayantra.marketdata.options.analytics;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read surface for the cross-source OI divergence canary (audit §8 V7). Evaluates fresh on every GET
 * (same convention as {@code /market/health/data}); nested under the already-allowlisted {@code
 * /api/v1/market/**} prefix. Lives in the options module so it can reach the canary + its readers
 * without a cross-module reach-in.
 */
@RestController
@RequestMapping("/api/v1/market/health")
public class OiCrossSourceHealthController {

  private final CrossSourceOiCanary canary;

  public OiCrossSourceHealthController(CrossSourceOiCanary canary) {
    this.canary = canary;
  }

  /** Snapshot-OI vs candle-OI divergence over each underlying's most recent expired expiry. */
  @GetMapping("/oi-cross-source")
  public CrossSourceOiCanary.CrossSourceReport oiCrossSource() {
    return canary.evaluate();
  }
}
