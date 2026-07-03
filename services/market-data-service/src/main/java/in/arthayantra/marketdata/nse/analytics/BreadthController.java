package in.arthayantra.marketdata.nse.analytics;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Market breadth for a date: advance/decline + delivery%-leaders from the EQ bhavcopy. */
@RestController
@RequestMapping("/api/v1/market/breadth")
public class BreadthController {

  private final BreadthService service;
  private final EquityIndexContributionService contributions;

  public BreadthController(
      BreadthService service, EquityIndexContributionService contributions) {
    this.service = service;
    this.contributions = contributions;
  }

  @GetMapping
  public BreadthService.Breadth breadth(@RequestParam String date) {
    return service.breadth(parseDate(date));
  }

  /**
   * INTRADAY index-constituent breadth (roadmap F3.1): advance/decline counts over the index's
   * seeded constituents from live quotes (the same fold the index-contribution page serves), EOD
   * fallback when quotes are unavailable. This is the scalper breadth dot's live producer — the
   * §12.3 "advances &gt; 32" rule is a NIFTY-50-universe rule (32 of 50), which the full-bhavcopy
   * date read can never express intraday (it reads 0/0 until the post-close bhavcopy lands).
   */
  /** Constituent-universe advance/decline counts (the scalper dot's operand scale, ~50 not ~2000). */
  public record LiveBreadthSummary(int advances, int declines, int unchanged, int total) {}

  /** The live-breadth envelope; {@code live} is false when the fold fell back to the EOD read. */
  public record LiveBreadth(String index, LiveBreadthSummary summary, boolean live, LocalDate asOf) {}

  @GetMapping("/live")
  public LiveBreadth live(@RequestParam(defaultValue = "NIFTY 50") String index) {
    EquityIndexContributionService.IndexContribution c = contributions.liveContribution(index);
    return new LiveBreadth(
        c.index(),
        new LiveBreadthSummary(
            c.advances().size(), c.declines().size(), 0,
            c.advances().size() + c.declines().size()),
        c.live(),
        c.asOf());
  }

  private static LocalDate parseDate(String raw) {
    try {
      return LocalDate.parse(raw);
    } catch (DateTimeParseException e) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "date must be ISO yyyy-MM-dd");
    }
  }
}
