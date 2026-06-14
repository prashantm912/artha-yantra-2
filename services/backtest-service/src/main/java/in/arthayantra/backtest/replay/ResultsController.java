package in.arthayantra.backtest.replay;

import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.error.NotFoundException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
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

  /**
   * Paged trades (incl. per-trade indicator contributions, instrument symbol and entry-time
   * stop_loss/take_profit levels). Optional {@code symbol} filters by tradingsymbol; {@code from}/
   * {@code to} (ISO-8601) bound the entry timestamp to the half-open window {@code [from, to)}.
   */
  @GetMapping("/{backtestId}/trades")
  public Map<String, Object> trades(
      @PathVariable UUID backtestId,
      @RequestParam(defaultValue = "100") int limit,
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(required = false) String symbol,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime to) {
    int boundedLimit = Math.min(Math.max(limit, 1), 1000);
    int boundedOffset = Math.max(offset, 0);
    List<Map<String, Object>> items =
        trades.findByRun(backtestId, boundedLimit, boundedOffset, symbol, from, to);
    return Map.of("items", items, "limit", boundedLimit, "offset", boundedOffset);
  }

  /**
   * Latest-backtest summary for a set of strategy versions (Stage F follow-on) — the strategy-list
   * enrichment join done client-side keeps the schema boundary intact. Returns one item per
   * requested {@code strategyVersionIds} value that has at least one completed run; unknown / never-
   * run versions are simply absent. Empty / absent query ⇒ empty list.
   */
  @GetMapping("/summary")
  public Map<String, Object> summary(
      @RequestParam(required = false) List<UUID> strategyVersionIds) {
    return Map.of("items", runs.findLatestSummaries(strategyVersionIds));
  }
}
