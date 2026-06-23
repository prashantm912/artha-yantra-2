package in.arthayantra.marketdata.backfill;

import in.arthayantra.marketdata.upstox.UpstoxExpiredInstrumentsClient;
import in.arthayantra.marketdata.upstox.UpstoxRateLimiter.WindowStat;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Market-data admin surface — Upstox per-token quota usage (B4 quota widget). Read-only snapshot of the
 * three sliding windows (1s / 1m / 30m) the expired-backfill limiter enforces, so the operator can see
 * quota burn before a long pull stalls. {@code configured=false} when the analytics source is absent
 * (mock stack / {@code analytics.enabled=false}) — the widget then renders "not configured" rather than
 * erroring. Auto-proxied by the edge-gateway {@code /api/v1/market/**} route.
 */
@RestController
@RequestMapping("/api/v1/market/admin")
public class QuotaController {

  /** {@code configured} = the analytics client (and thus the limiter) is bound on this stack. */
  public record QuotaStatus(boolean configured, List<WindowStat> windows) {}

  private final ObjectProvider<UpstoxExpiredInstrumentsClient> clientProvider;

  public QuotaController(ObjectProvider<UpstoxExpiredInstrumentsClient> clientProvider) {
    this.clientProvider = clientProvider;
  }

  /** Current per-window Upstox quota usage (used / max / remaining). */
  @GetMapping("/upstox-quota-status")
  public QuotaStatus quota() {
    UpstoxExpiredInstrumentsClient client = clientProvider.getIfAvailable();
    if (client == null) {
      return new QuotaStatus(false, List.of());
    }
    return new QuotaStatus(true, client.rateLimiter().getUsageStats());
  }
}
