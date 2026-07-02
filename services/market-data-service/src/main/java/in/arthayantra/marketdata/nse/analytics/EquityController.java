package in.arthayantra.marketdata.nse.analytics;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketdata.futures.preopen.FuturesPreOpenScan;
import in.arthayantra.marketdata.nse.preopen.PreOpenEquityScanService;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Equity EOD analytics from the NSE bhavcopy (oipulse Equity section). */
@RestController
@RequestMapping("/api/v1/market/equity")
public class EquityController {

  private final EquityDeliveryService delivery;
  private final EquityReturnsService returns;
  private final EquitySectorService sector;
  private final EquityIndexContributionService contribution;
  private final PreOpenEquityScanService preOpenScan;

  public EquityController(
      EquityDeliveryService delivery,
      EquityReturnsService returns,
      EquitySectorService sector,
      EquityIndexContributionService contribution,
      PreOpenEquityScanService preOpenScan) {
    this.delivery = delivery;
    this.returns = returns;
    this.sector = sector;
    this.contribution = contribution;
    this.preOpenScan = preOpenScan;
  }

  /** One stock's daily delivery series over the most recent {@code days} sessions (default 15). */
  @GetMapping("/delivery")
  public EquityDeliveryService.Delivery delivery(
      @RequestParam String symbol, @RequestParam(defaultValue = "15") int days) {
    return delivery.delivery(symbol, days);
  }

  /** Multi-timeframe returns screener over every EQ stock (Current Day / 1W / 1M / 6M / 1Y). */
  @GetMapping("/returns")
  public EquityReturnsService.Returns returns() {
    return returns.returns();
  }

  /** An index's constituents grouped by sector, each stock's latest % change (sector-heatmap treemap). */
  @GetMapping("/sector-heatmap")
  public EquitySectorService.SectorHeatmap sectorHeatmap(@RequestParam String name) {
    return sector.sectorHeatmap(name);
  }

  /** Per-sector roll-up (avg change + advancer/decliner split) + the per-stock factor table. */
  @GetMapping("/sector-stats")
  public EquitySectorService.SectorStats sectorStats() {
    return sector.sectorStats();
  }

  /** Per-constituent index contribution (weight × % change), split advances / declines. */
  @GetMapping("/index-contribution")
  public EquityIndexContributionService.IndexContribution indexContribution(
      @RequestParam String name) {
    return contribution.contribution(name);
  }

  /**
   * The preserved equity pre-open FULL scan (audit §9.4, Wave 5): every F&O stock's captured
   * pre-open price vs prev close + the prev-day H/L break, for {@code date} (default = today IST).
   * Empty items before the day's ~09:09:30 capture; {@code dates} lists captured sessions
   * (history mode). Map envelope — advances/declines split client-side by change sign.
   */
  @GetMapping("/pre-open-scan")
  public Map<String, Object> preOpenScan(@RequestParam(required = false) String date) {
    LocalDate day = date == null || date.isBlank() ? LocalDate.now(Ist.ZONE) : LocalDate.parse(date);
    List<FuturesPreOpenScan.PreOpenRow> items = preOpenScan.scan(day);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("date", day);
    out.put("items", items);
    out.put("dates", preOpenScan.capturedDates());
    return out;
  }

  /** On-demand capture for today (owner verify / a missed 09:09:30 run); returns the row count. */
  @PostMapping("/pre-open-scan/capture")
  public Map<String, Object> preOpenScanCapture() {
    LocalDate today = LocalDate.now(Ist.ZONE);
    return Map.of("date", today, "captured", preOpenScan.capture(today));
  }
}
