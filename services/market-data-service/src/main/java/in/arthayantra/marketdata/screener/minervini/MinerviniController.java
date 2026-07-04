package in.arthayantra.marketdata.screener.minervini;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Minervini SEPA screener surface (Track-1). Returns a TYPED record (not {@code Map}) so it
 * enumerates into the springdoc contract and does not bump the market-data Map-return ratchet.
 * {@code GET} serves the persisted daily screen (fast path, the watchlist consumer);
 * {@code POST /run} recomputes on demand. Decimals cross the wire as JSON strings (global Jackson
 * config; the React {@code core/decimal} helper parses them). Path is under {@code /api/v1/market/**}
 * so the edge-gateway allowlist already covers it.
 */
@RestController
@RequestMapping("/api/v1/market/screener/minervini")
public class MinerviniController {

  /** One screener row (the 8 gates + RS-rank + Stage + low-cap inputs). */
  public record Row(
      String symbol,
      String exchange,
      BigDecimal close,
      BigDecimal sma50,
      BigDecimal sma150,
      BigDecimal sma200,
      BigDecimal high52w,
      BigDecimal low52w,
      BigDecimal pctFromHigh,
      BigDecimal pctAboveLow,
      BigDecimal rsRank,
      BigDecimal avgTurnover50,
      BigDecimal freeFloatMcapCr,
      BigDecimal freeFloatPct,
      boolean[] gates,
      int gatesPassed,
      boolean passesAll,
      Integer stage) {}

  /** The {items} envelope + as-of + coverage. */
  public record ScreenResponse(
      List<Row> items, LocalDate screenDate, int coverage, int limit, int offset) {}

  private final TrendTemplateService screener;
  private final MinerviniScreenRepository repo;

  /** Wires the screener + repository. */
  public MinerviniController(TrendTemplateService screener, MinerviniScreenRepository repo) {
    this.screener = screener;
    this.repo = repo;
  }

  /** Serves the persisted daily screen (default = latest date, passers only). */
  @GetMapping
  public ScreenResponse get(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
      @RequestParam(defaultValue = "true") boolean passesAllOnly,
      @RequestParam(required = false) BigDecimal minRsRank,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    LocalDate date = asOf != null ? asOf : screener.latestScreenDate();
    int cappedLimit = Math.min(Math.max(1, limit), 500);
    if (date == null) {
      return new ScreenResponse(List.of(), null, 0, cappedLimit, offset);
    }
    List<TrendCandidate> rows = repo.latest(date, passesAllOnly, minRsRank, cappedLimit, offset);
    return new ScreenResponse(
        rows.stream().map(MinerviniController::toRow).toList(),
        date, repo.coverage(date), cappedLimit, offset);
  }

  /** Recomputes the screen now, persists it, and returns the fresh result. */
  @PostMapping("/run")
  public ScreenResponse run(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
      @RequestParam(defaultValue = "true") boolean passesAllOnly,
      @RequestParam(required = false) BigDecimal minRsRank,
      @RequestParam(defaultValue = "50") int limit) {
    int cappedLimit = Math.min(Math.max(1, limit), 500);
    TrendTemplateService.ScreenResult res = screener.screen(asOf);
    if (res.screenDate() == null) {
      return new ScreenResponse(List.of(), null, 0, cappedLimit, 0);
    }
    repo.upsertAll(res.screenDate(), res.candidates());
    List<Row> items =
        res.candidates().stream()
            .filter(c -> !passesAllOnly || c.passesAll())
            .filter(c -> minRsRank == null || c.rsRank().compareTo(minRsRank) >= 0)
            .limit(cappedLimit)
            .map(MinerviniController::toRow)
            .toList();
    return new ScreenResponse(items, res.screenDate(), res.coverage(), cappedLimit, 0);
  }

  private static Row toRow(TrendCandidate c) {
    return new Row(
        c.symbol(), c.exchange(), c.close(), c.sma50(), c.sma150(), c.sma200(),
        c.high52w(), c.low52w(), c.pctFromHigh(), c.pctAboveLow(), c.rsRank(), c.avgTurnover50(),
        c.freeFloatMcapCr(), c.freeFloatPct(), c.gates(), c.gatesPassed(), c.passesAll(), c.stage());
  }
}
