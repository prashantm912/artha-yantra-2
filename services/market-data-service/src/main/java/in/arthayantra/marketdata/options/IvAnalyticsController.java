package in.arthayantra.marketdata.options;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The F-42B IV-analytics surface: the trailing-1-year IV history with rank/percentile (the honest
 * insufficient-history state below the floor), plus a retroactive recompute command over the
 * archive. Reads/writes only the {@code marketdata} schema (single writer, D10).
 */
@RestController
@RequestMapping("/api/v1/market/options")
public class IvAnalyticsController {

  /** Optional bounds for the recompute command. */
  public record RollupRequest(
      String underlying,
      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {}

  private final IvAnalyticsService analytics;
  private final List<String> underlyings;

  /** Wires the analytics service over the configured snapshot underlyings. */
  public IvAnalyticsController(
      IvAnalyticsService analytics,
      @Value("${artha.options.snapshot-underlyings:NIFTY 50}") List<String> underlyings) {
    this.analytics = analytics;
    this.underlyings = underlyings;
  }

  /** Daily IV series + current rank/percentile (suppressed below the documented floor). */
  @GetMapping("/iv-history")
  public IvAnalyticsService.IvHistory ivHistory(@RequestParam String underlying) {
    return analytics.ivHistory(underlying);
  }

  /** Retroactive recompute over the archive (idempotent upserts; bumps {@code computed_at}). */
  @PostMapping("/iv-rollup")
  public Map<String, Object> rollup(@RequestBody(required = false) RollupRequest request) {
    List<String> targets =
        request != null && request.underlying() != null
            ? List.of(request.underlying())
            : underlyings;
    LocalDate from = request == null ? null : request.from();
    LocalDate to = request == null ? null : request.to();
    int total = 0;
    for (String underlying : targets) {
      total += analytics.recompute(underlying, from, to);
    }
    return Map.of("recomputed", total);
  }
}
