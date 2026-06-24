package in.arthayantra.marketdata.upstox;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pre-Open Market read surface — live exchange session-phase + an index pre-open snapshot from Upstox.
 * Two endpoints:
 *
 * <ul>
 *   <li>{@code GET /api/v1/market/market-status} → {@code {items:[MarketStatus for NSE,BSE,MCX]}} (each
 *       carries the raw phase enum + the derived {@code preOpen} boolean). Distinct from the existing
 *       {@code MarketSurfaceController} {@code /status} (the calendar session-open/closed surface).
 *   <li>{@code GET /api/v1/market/pre-open} → {@code {phase, preOpen, indices:[PreOpenIndex...]}} (the
 *       NSE phase + the four index quotes: Nifty 50 / Bank / Fin / Sensex).
 * </ul>
 *
 * <p>The {@link UpstoxMarketStatusClient} is an OPTIONAL bean (bound only on the live profile with
 * {@code artha.upstox.analytics.enabled=true}), so the controller resolves it via an {@link
 * ObjectProvider}: when absent (mock / analytics off) {@code /status} returns an empty {@code items}
 * list and {@code /pre-open} returns phase {@code "UNKNOWN"} with an empty indices list rather than a
 * 503 — the page renders its empty/closed state. The client itself NEVER throws (a dead Upstox degrades
 * to UNKNOWN / price-less rows), so the endpoints never 500. Map-envelope so they do not enumerate a
 * springdoc schema.
 */
@RestController
@RequestMapping("/api/v1/market")
public class PreOpenController {

  private final ObjectProvider<UpstoxMarketStatusClient> client;

  public PreOpenController(ObjectProvider<UpstoxMarketStatusClient> client) {
    this.client = client;
  }

  /** {@code {items:[MarketStatus for NSE,BSE,MCX]}} — empty when the Upstox status client is not wired. */
  @GetMapping("/market-status")
  public Map<String, Object> status() {
    UpstoxMarketStatusClient resolved = client.getIfAvailable();
    List<MarketStatus> items = resolved == null ? List.of() : resolved.marketStatuses();
    return Map.of("items", items);
  }

  /**
   * {@code {phase, preOpen, indices:[PreOpenIndex...]}} — the NSE session phase + the four index quotes.
   * When the Upstox client is not wired the phase is {@code "UNKNOWN"} with an empty indices list.
   */
  @GetMapping("/pre-open")
  public Map<String, Object> preOpen() {
    UpstoxMarketStatusClient resolved = client.getIfAvailable();
    if (resolved == null) {
      return Map.of("phase", "UNKNOWN", "preOpen", false, "indices", List.of());
    }
    UpstoxMarketStatusClient.PreOpen snapshot = resolved.preOpen();
    return Map.of(
        "phase", snapshot.phase(),
        "preOpen", snapshot.preOpen(),
        "indices", snapshot.indices());
  }
}
