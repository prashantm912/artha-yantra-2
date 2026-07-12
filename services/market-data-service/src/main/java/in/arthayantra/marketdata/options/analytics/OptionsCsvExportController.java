package in.arthayantra.marketdata.options.analytics;

import in.arthayantra.common.web.csv.CsvExport;
import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketdata.options.analytics.OptionsAnalyticsController.ChainTable;
import in.arthayantra.marketdata.options.analytics.OptionsAnalyticsController.ChainTableLeg;
import in.arthayantra.marketdata.options.analytics.OptionsAnalyticsController.ChainTableRow;
import java.time.format.DateTimeFormatter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CSV export for the two highest-traffic OI surfaces — the Options Chain table and OI Trending
 * (app-platform audit 2026-07-10 §2.4 "no CSV export anywhere in options"; §10 Phase-3 CSV export
 * standard). Reuses {@link OptionsAnalyticsController}'s already-built {@code chainTable}/{@code
 * trending} folds verbatim (same params, same live/history + basket logic) and formats the result
 * through the shared {@link CsvExport} builder, so the truncation contract is identical to every other
 * export. A dedicated controller keeps the big analytics controller untouched. Nested under the
 * already-allowlisted {@code /api/v1/market/**} prefix — no gateway change.
 */
@RestController
@RequestMapping("/api/v1/market/options")
public class OptionsCsvExportController {

  private static final DateTimeFormatter IST_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

  private final OptionsAnalyticsController analytics;

  public OptionsCsvExportController(OptionsAnalyticsController analytics) {
    this.analytics = analytics;
  }

  /** The Options Chain table (CE | strike | PE, OI/ΔOI/LTP/IV/volume per leg) as a CSV download. */
  @GetMapping("/chain-table/export")
  public ResponseEntity<byte[]> chainTableCsv(
      @RequestParam(required = false) String mode,
      @RequestParam String name,
      @RequestParam(required = false) String date,
      @RequestParam(required = false) String interval,
      @RequestParam(required = false) String expiry,
      @RequestParam(required = false) String window) {
    ChainTable table = analytics.chainTable(mode, name, date, interval, expiry, window);
    CsvExport.Writer w =
        CsvExport.writer(
            CsvExport.DEFAULT_MAX_ROWS,
            "strike",
            "ce_oi", "ce_oi_change", "ce_ltp", "ce_iv", "ce_volume",
            "pe_oi", "pe_oi_change", "pe_ltp", "pe_iv", "pe_volume");
    for (ChainTableRow r : table.rows()) {
      w.row(
          r.strike(),
          legOi(r.ce()), legOiChange(r.ce()), legLtp(r.ce()), legIv(r.ce()), legVolume(r.ce()),
          legOi(r.pe()), legOiChange(r.pe()), legLtp(r.pe()), legIv(r.pe()), legVolume(r.pe()));
    }
    return w.download("chain_" + slug(table.underlying()) + "_" + table.expiry() + ".csv");
  }

  /** OI Trending (per-bucket total/CE/PE OI + spot + trend + CE/PE premium) as a CSV download. */
  @GetMapping("/trending/export")
  public ResponseEntity<byte[]> trendingCsv(
      @RequestParam(required = false) String mode,
      @RequestParam String name,
      @RequestParam(required = false) String date,
      @RequestParam(required = false) String interval,
      @RequestParam(required = false) String expiry,
      @RequestParam(required = false) Integer buckets,
      @RequestParam(required = false) String strikes,
      @RequestParam(required = false) String baseline) {
    OiTrendingService.TrendSeries series =
        analytics.trending(mode, name, date, interval, expiry, buckets, strikes, baseline);
    CsvExport.Writer w =
        CsvExport.writer(
            CsvExport.DEFAULT_MAX_ROWS,
            "time_ist", "total_oi", "ce_oi", "pe_oi", "spot", "trend", "ce_ltp", "pe_ltp");
    for (OiTrendingService.TrendPoint p : series.items()) {
      w.row(
          p.bucket() == null ? "" : p.bucket().atZoneSameInstant(Ist.ZONE).format(IST_TS),
          p.totalOi(), p.ceOi(), p.peOi(), p.spot(), p.trend(), p.ceLtp(), p.peLtp());
    }
    return w.download("trending_" + slug(name) + ".csv");
  }

  private static Long legOi(ChainTableLeg l) {
    return l == null || l.leg() == null ? null : l.leg().oi();
  }

  private static Long legOiChange(ChainTableLeg l) {
    return l == null || l.deltas() == null ? null : l.deltas().oiChange();
  }

  private static Object legLtp(ChainTableLeg l) {
    return l == null || l.leg() == null ? null : l.leg().ltp();
  }

  private static Object legIv(ChainTableLeg l) {
    return l == null || l.leg() == null ? null : l.leg().iv();
  }

  private static Object legVolume(ChainTableLeg l) {
    return l == null || l.leg() == null ? null : l.leg().volume();
  }

  private static String slug(String s) {
    return s == null ? "chain" : s.replace(' ', '_');
  }
}
