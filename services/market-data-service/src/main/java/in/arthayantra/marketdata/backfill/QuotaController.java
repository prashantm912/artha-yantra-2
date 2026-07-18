package in.arthayantra.marketdata.backfill;

import in.arthayantra.marketdata.upstox.UpstoxRateLimiter;
import in.arthayantra.marketdata.upstox.UpstoxRateLimiter.WindowStat;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Market-data admin surface — Upstox per-token quota usage (B4 quota widget). Read-only snapshot of the
 * three sliding windows (1s / 1m / 30m) the ONE shared token budget enforces (EXT-02), so the operator
 * can see quota burn across EVERY analytics-token client before a long pull stalls. Reads the shared
 * {@link UpstoxRateLimiter} bean directly, so it reports {@code configured=true} in ANY Upstox config
 * (quote-only / chain-only / ticker-only), not only when the expired-backfill client is bound.
 * {@code configured=false} when no Upstox token consumer is bound (mock stack / all sources on Kite) —
 * the widget then renders "not configured". Auto-proxied by the edge-gateway {@code /api/v1/market/**}.
 */
@RestController
@RequestMapping("/api/v1/market/admin")
public class QuotaController {

  /** {@code configured} = the shared token budget is bound on this stack (some Upstox consumer is on). */
  public record QuotaStatus(boolean configured, List<WindowStat> windows) {}

  private final ObjectProvider<UpstoxRateLimiter> limiterProvider;

  public QuotaController(ObjectProvider<UpstoxRateLimiter> limiterProvider) {
    this.limiterProvider = limiterProvider;
  }

  /** Current per-window Upstox quota usage (used / max / remaining). */
  @GetMapping("/upstox-quota-status")
  public QuotaStatus quota() {
    UpstoxRateLimiter limiter = limiterProvider.getIfAvailable();
    if (limiter == null) {
      return new QuotaStatus(false, List.of());
    }
    return new QuotaStatus(true, limiter.getUsageStats());
  }
}
