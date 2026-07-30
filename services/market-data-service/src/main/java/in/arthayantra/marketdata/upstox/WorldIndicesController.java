package in.arthayantra.marketdata.upstox;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * World Indices read surface — live global-index quotes from Upstox ({@code GET
 * /api/v1/market/world-indices}). Lists every {@code GLOBAL_INDEX} / {@code GLOBAL_INDICATOR} row of
 * the Upstox global master joined with its live quote (LTP + day OHLC + net change + derived %change).
 *
 * <p>The {@link UpstoxGlobalInstrumentsClient} is an OPTIONAL bean (bound only on the live profile with
 * {@code artha.upstox.analytics.enabled=true}), so the controller resolves it via an {@link
 * ObjectProvider}: when absent (mock / analytics off) it returns an empty {@code items} list rather than
 * a 503 — the page renders its empty state. The client itself NEVER throws (a dead Upstox degrades to
 * empty / price-less rows), so the endpoint never 500s. Map-envelope so it does not enumerate a
 * springdoc schema.
 */
@RestController
@RequestMapping("/api/v1/market")
public class WorldIndicesController {

  private final ObjectProvider<UpstoxGlobalInstrumentsClient> client;

  public WorldIndicesController(ObjectProvider<UpstoxGlobalInstrumentsClient> client) {
    this.client = client;
  }

  /** {@code {items:[WorldIndex...]}} — empty when the Upstox global client is not wired. */
  @GetMapping("/world-indices")
  public WorldIndicesResponse worldIndices() {
    UpstoxGlobalInstrumentsClient resolved = client.getIfAvailable();
    List<WorldIndex> items = resolved == null ? List.of() : resolved.worldIndices();
    return new WorldIndicesResponse(items);
  }

  /** The {items} envelope. Single-key {@code Map.of} before D3, so key order was never in play. */
  public record WorldIndicesResponse(List<WorldIndex> items) {}
}
