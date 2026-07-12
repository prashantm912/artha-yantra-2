package in.arthayantra.marketdata.nse.analytics;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.freshness.DataFreshness;
import in.arthayantra.marketdata.nse.preopen.PreOpenEquityScanService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
  private final Clock clock;

  public EquityController(
      EquityDeliveryService delivery,
      EquityReturnsService returns,
      EquitySectorService sector,
      EquityIndexContributionService contribution,
      PreOpenEquityScanService preOpenScan,
      Clock clock) {
    this.delivery = delivery;
    this.returns = returns;
    this.sector = sector;
    this.contribution = contribution;
    this.preOpenScan = preOpenScan;
    this.clock = clock;
  }

  /** The bhavcopy EOD freshness envelope for an equity read — asOf pinned to the session close. */
  private DataFreshness eod(LocalDate asOf) {
    OffsetDateTime at =
        asOf == null ? null : asOf.atTime(MarketCalendar.SESSION_CLOSE).atOffset(Ist.OFFSET);
    return DataFreshness.eod(at, "bhavcopy", clock);
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
    EquityReturnsService.Returns r = returns.returns();
    return r.withFreshness(eod(r.asOf()));
  }

  /** An index's constituents grouped by sector, each stock's latest % change (sector-heatmap treemap). */
  @GetMapping("/sector-heatmap")
  public EquitySectorService.SectorHeatmap sectorHeatmap(@RequestParam String name) {
    EquitySectorService.SectorHeatmap h = sector.sectorHeatmap(name);
    return h.withFreshness(eod(h.asOf()));
  }

  /** Per-sector roll-up (avg change + advancer/decliner split) + the per-stock factor table. */
  @GetMapping("/sector-stats")
  public EquitySectorService.SectorStats sectorStats() {
    EquitySectorService.SectorStats s = sector.sectorStats();
    return s.withFreshness(eod(s.asOf()));
  }

  /**
   * Per-constituent index contribution (weight × % change), split advances / declines.
   * {@code mode=live} (audit §9.4, Wave 5) folds intraday quotes — points off the LIVE index
   * level, EOD fallback when quotes are unavailable; default stays the EOD bhavcopy read.
   */
  @GetMapping("/index-contribution")
  public EquityIndexContributionService.IndexContribution indexContribution(
      @RequestParam String name, @RequestParam(required = false) String mode) {
    return "live".equalsIgnoreCase(mode)
        ? contribution.liveContribution(name)
        : contribution.contribution(name);
  }

  /**
   * The preserved equity pre-open FULL scan (audit §9.4, Wave 5): every F&O stock's captured
   * pre-open price vs prev close + the prev-day H/L break, for {@code date} (default = today IST).
   * Empty items before the day's ~09:09:30 capture; {@code dates} lists captured sessions
   * (history mode). Advances/declines split client-side by change sign.
   */
  @GetMapping("/pre-open-scan")
  public PreOpenEquityScanService.PreOpenScanView preOpenScan(
      @RequestParam(required = false) String date) {
    LocalDate day = date == null || date.isBlank() ? LocalDate.now(Ist.ZONE) : LocalDate.parse(date);
    return preOpenScan.view(day);
  }

  /** On-demand capture for today (owner verify / a missed 09:09:30 run); returns the row count. */
  @PostMapping("/pre-open-scan/capture")
  public PreOpenEquityScanService.CaptureResult preOpenScanCapture() {
    LocalDate today = LocalDate.now(Ist.ZONE);
    return new PreOpenEquityScanService.CaptureResult(today, preOpenScan.capture(today));
  }
}
