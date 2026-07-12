package in.arthayantra.marketdata.screener;

import in.arthayantra.common.web.csv.CsvExport;
import in.arthayantra.marketdata.screener.manas.ManasCandidate;
import in.arthayantra.marketdata.screener.manas.ManasScreenRepository;
import in.arthayantra.marketdata.screener.minervini.MinerviniScreenRepository;
import in.arthayantra.marketdata.screener.minervini.TrendCandidate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CSV export for the two swing screeners (app-platform audit 2026-07-10 §10 Phase-3 CSV export
 * standard). Reuses each screen repository's persisted-row read and formats it through the shared
 * {@link CsvExport} builder (identical truncation contract to every other export). Defaults to the
 * latest persisted screen date; {@code asOf} time-travels. Nested under the already-allowlisted
 * {@code /api/v1/market/screener/**} prefix — no gateway change.
 */
@RestController
@RequestMapping("/api/v1/market/screener")
public class ScreenerCsvExportController {

  // The universe is ~2000 names, so the cap is never hit in practice — but keep the loud-truncation
  // contract by asking for one row over the cap.
  private static final int CAP = CsvExport.DEFAULT_MAX_ROWS;

  private final MinerviniScreenRepository minervini;
  private final ManasScreenRepository manas;

  public ScreenerCsvExportController(
      MinerviniScreenRepository minervini, ManasScreenRepository manas) {
    this.minervini = minervini;
    this.manas = manas;
  }

  /** The Minervini screen (all scanned gates + RS-rank + Stage) as a CSV download. */
  @GetMapping("/minervini/export")
  public ResponseEntity<byte[]> minerviniCsv(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
      @RequestParam(defaultValue = "true") boolean passesAllOnly,
      @RequestParam(required = false) BigDecimal minRsRank) {
    LocalDate date = asOf != null ? asOf : minervini.latestScreenDate();
    CsvExport.Writer w =
        CsvExport.writer(
            CAP,
            "symbol", "exchange", "close", "sma50", "sma150", "sma200", "high_52w", "low_52w",
            "pct_from_high", "pct_above_low", "rs_rank", "avg_turnover_50", "free_float_mcap_cr",
            "free_float_pct", "gates_passed", "passes_all", "stage");
    if (date != null) {
      List<TrendCandidate> rows = minervini.latest(date, passesAllOnly, minRsRank, CAP + 1, 0);
      for (TrendCandidate c : rows) {
        w.row(
            c.symbol(), c.exchange(), c.close(), c.sma50(), c.sma150(), c.sma200(),
            c.high52w(), c.low52w(), c.pctFromHigh(), c.pctAboveLow(), c.rsRank(),
            c.avgTurnover50(), c.freeFloatMcapCr(), c.freeFloatPct(),
            c.gatesPassed(), c.passesAll(), c.stage());
      }
    }
    return w.download("minervini_screen_" + (date == null ? "empty" : date) + ".csv");
  }

  /** The Manas Arora screen (all scanned gates + liquidity/float flags + RS-rank) as a CSV download. */
  @GetMapping("/manas-arora/export")
  public ResponseEntity<byte[]> manasCsv(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
      @RequestParam(defaultValue = "true") boolean passesAllOnly) {
    LocalDate date = asOf != null ? asOf : manas.latestScreenDate();
    CsvExport.Writer w =
        CsvExport.writer(
            CAP,
            "symbol", "exchange", "close", "sma50", "sma200", "high_52w", "low_52w",
            "avg_vol_20", "avg_vol_50", "turnover_50", "within_high_pct", "above_low_pct", "rs_rank",
            "within_high", "above_sma50", "liquid_volume", "liquid_depth", "low_cap",
            "free_float_mcap_cr", "free_float_pct", "gates_passed", "passes_all");
    if (date != null) {
      List<ManasCandidate> rows = manas.latest(date, passesAllOnly, CAP + 1, 0);
      for (ManasCandidate c : rows) {
        w.row(
            c.symbol(), c.exchange(), c.close(), c.sma50(), c.sma200(), c.high52w(), c.low52w(),
            c.avgVol20(), c.avgVol50(), c.turnover50(), c.withinHighPct(), c.aboveLowPct(), c.rsRank(),
            c.withinHigh(), c.aboveSma50(), c.liquidVolume(), c.liquidDepth(), c.lowCap(),
            c.freeFloatMcapCr(), c.freeFloatPct(), c.gatesPassed(), c.passesAll());
      }
    }
    return w.download("manas_screen_" + (date == null ? "empty" : date) + ".csv");
  }
}
