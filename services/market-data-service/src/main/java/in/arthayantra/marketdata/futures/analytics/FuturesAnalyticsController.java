package in.arthayantra.marketdata.futures.analytics;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.marketdata.options.OiQuery;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/market/futures")
public class FuturesAnalyticsController {

  private final FuturesSnapshotReader reader;
  private final FuturesSpurtService spurtService;
  private final FuturesMoversService moversService;

  public FuturesAnalyticsController(
      FuturesSnapshotReader reader,
      FuturesSpurtService spurtService,
      FuturesMoversService moversService) {
    this.reader = reader;
    this.spurtService = spurtService;
    this.moversService = moversService;
  }

  @GetMapping("/oi-analysis")
  public Map<String, Object> oiAnalysis(
      @RequestParam(required = false) String mode,
      @RequestParam String name,
      @RequestParam(required = false) String date,
      @RequestParam(required = false) String interval,
      @RequestParam(required = false) String expiry) {
    OiQuery q = OiQuery.of(mode, name, date, interval, expiry);
    // live (date null) = newest bucket per contract; history (date set) = newest bucket that day.
    List<FuturesSnapshotReader.FutPoint> pts = reader.latest(q.name(), q.interval(), q.date());
    return Map.of("items", pts);
  }

  /** /spurt: futures interval buildup (per contract, 4-state + spurt %). */
  @GetMapping("/spurt")
  public FuturesSpurtService.FutSpurtChain spurt(
      @RequestParam(required = false) String mode,
      @RequestParam String name,
      @RequestParam(required = false) String date,
      @RequestParam(required = false) String interval,
      @RequestParam(required = false) String expiry) {
    OiQuery q = OiQuery.of(mode, name, date, interval, expiry);
    List<FuturesSnapshotReader.FutPoint> pair = reader.latestPair(q.name(), q.interval(), q.date());
    if (pair.isEmpty()) {
      throw new ApiException(422, ErrorCodes.DATA_GAP, "no snapshot for " + q.name());
    }
    return spurtService.spurts(pair);
  }

  /** /movers: futures gainers/losers by price% (prevClose-based) + OI%. */
  @GetMapping("/movers")
  public FuturesMoversService.Movers movers(
      @RequestParam(required = false) String mode,
      @RequestParam String name,
      @RequestParam(required = false) String date,
      @RequestParam(required = false) String interval,
      @RequestParam(required = false) String expiry) {
    OiQuery q = OiQuery.of(mode, name, date, interval, expiry);
    List<FuturesSnapshotReader.FutPoint> pair = reader.latestPair(q.name(), q.interval(), q.date());
    if (pair.isEmpty()) {
      throw new ApiException(422, ErrorCodes.DATA_GAP, "no snapshot for " + q.name());
    }
    return moversService.movers(pair);
  }

  /** /banks: the queried index's futures term structure + calendar-spread basis. */
  @GetMapping("/banks")
  public FuturesMoversService.Banks banks(
      @RequestParam(required = false) String mode,
      @RequestParam String name,
      @RequestParam(required = false) String date,
      @RequestParam(required = false) String interval,
      @RequestParam(required = false) String expiry) {
    OiQuery q = OiQuery.of(mode, name, date, interval, expiry);
    List<FuturesSnapshotReader.FutPoint> pair = reader.latestPair(q.name(), q.interval(), q.date());
    if (pair.isEmpty()) {
      throw new ApiException(422, ErrorCodes.DATA_GAP, "no snapshot for " + q.name());
    }
    return moversService.banks(pair);
  }
}
