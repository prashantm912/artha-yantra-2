package in.arthayantra.marketdata.nse.analytics;

import org.springframework.web.bind.annotation.GetMapping;
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

  public EquityController(
      EquityDeliveryService delivery,
      EquityReturnsService returns,
      EquitySectorService sector,
      EquityIndexContributionService contribution) {
    this.delivery = delivery;
    this.returns = returns;
    this.sector = sector;
    this.contribution = contribution;
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
}
