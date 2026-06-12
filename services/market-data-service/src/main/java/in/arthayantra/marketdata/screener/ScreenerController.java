package in.arthayantra.marketdata.screener;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The Phase-17 screener surface (B-1). */
@RestController
@RequestMapping("/api/v1/market/screener")
public class ScreenerController {

  private final ScreenerService screener;

  /** Wires the screener. */
  public ScreenerController(ScreenerService screener) {
    this.screener = screener;
  }

  /** Runs a preset OR explicit filters over the aggregates; 422 on unanswerable combos. */
  @GetMapping
  public Map<String, Object> screen(
      @RequestParam(required = false) String preset,
      @RequestParam(required = false) String window,
      @RequestParam(required = false) Integer lookback,
      @RequestParam(required = false) Integer lookbackDays,
      @RequestParam(required = false) java.math.BigDecimal minReturnPct,
      @RequestParam(required = false) java.math.BigDecimal minAvgVolume,
      @RequestParam(required = false) java.math.BigDecimal minPrice,
      @RequestParam(required = false) java.math.BigDecimal maxPrice,
      @RequestParam(required = false) String exchange,
      @RequestParam(defaultValue = "25") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    Integer effectiveLookback = lookbackDays != null ? lookbackDays : lookback;
    List<ScreenerService.Row> items =
        screener.run(
            preset,
            window,
            effectiveLookback,
            new ScreenerService.Filters(minReturnPct, minAvgVolume, minPrice, maxPrice, exchange),
            Math.min(limit, 500),
            offset);
    return Map.of("items", items, "limit", Math.min(limit, 500), "offset", offset);
  }
}
