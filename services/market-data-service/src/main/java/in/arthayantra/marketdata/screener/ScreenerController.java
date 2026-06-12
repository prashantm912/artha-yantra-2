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

  /** Runs a preset over the aggregates; 422 on unanswerable combos. */
  @GetMapping
  public Map<String, List<ScreenerService.Row>> screen(
      @RequestParam String preset,
      @RequestParam(required = false) String window,
      @RequestParam(required = false) Integer lookback,
      @RequestParam(defaultValue = "25") int limit) {
    return Map.of("items", screener.run(preset, window, lookback, Math.min(limit, 500)));
  }
}
