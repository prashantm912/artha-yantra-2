package in.arthayantra.marketdata.backfill;

import in.arthayantra.marketdata.upstox.ExpiredBackfillRepository;
import in.arthayantra.marketdata.upstox.ExpiredBackfillRepository.CoverageRow;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Market-data admin surface — expired-contract coverage rollup (B2 Coverage dashboard). Read-only:
 * one row per (underlying, exchange) with contract counts (complete vs partial), the expiry span, and
 * the registered candle tally ({@code sum(bar_count)} — no 15M-row candle scan). Auto-proxied by the
 * edge-gateway {@code /api/v1/market/**} route.
 */
@RestController
@RequestMapping("/api/v1/market/admin")
public class CoverageController {

  /** {@code {items:[...]}} envelope (matches the React list convention). */
  public record CoverageSummary(List<CoverageRow> items) {}

  private final ExpiredBackfillRepository repo;

  public CoverageController(ExpiredBackfillRepository repo) {
    this.repo = repo;
  }

  /** Per-(underlying, exchange) expired-contract coverage rollup. */
  @GetMapping("/coverage-summary")
  public CoverageSummary coverageSummary() {
    return new CoverageSummary(repo.coverageSummary());
  }
}
