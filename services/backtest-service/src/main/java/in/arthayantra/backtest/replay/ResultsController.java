package in.arthayantra.backtest.replay;

import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.error.NotFoundException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The §D.5 results + trades surface for a completed run, under {@code /api/v1/backtests/**}. */
@RestController
@RequestMapping("/api/v1/backtests")
public class ResultsController {

  private final RunRepository runs;
  private final TradeRepository trades;

  /** Wires the repositories. */
  public ResultsController(RunRepository runs, TradeRepository trades) {
    this.runs = runs;
    this.trades = trades;
  }

  /** Metrics + curves + reproducibility triple for one run. */
  @GetMapping("/{backtestId}/results")
  public Map<String, Object> results(@PathVariable UUID backtestId) {
    return runs
        .findResult(backtestId)
        .orElseThrow(
            () ->
                new NotFoundException(
                    ErrorCodes.NOT_FOUND_RESOURCE, "no such backtest run: " + backtestId));
  }

  /** Paged trades (incl. per-trade indicator contributions). */
  @GetMapping("/{backtestId}/trades")
  public Map<String, Object> trades(
      @PathVariable UUID backtestId,
      @RequestParam(defaultValue = "100") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    int boundedLimit = Math.min(Math.max(limit, 1), 1000);
    int boundedOffset = Math.max(offset, 0);
    List<Map<String, Object>> items = trades.findByRun(backtestId, boundedLimit, boundedOffset);
    return Map.of("items", items, "limit", boundedLimit, "offset", boundedOffset);
  }
}
