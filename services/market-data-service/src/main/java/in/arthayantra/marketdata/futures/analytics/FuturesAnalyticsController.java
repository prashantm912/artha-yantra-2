package in.arthayantra.marketdata.futures.analytics;

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

  public FuturesAnalyticsController(FuturesSnapshotReader reader) {
    this.reader = reader;
  }

  @GetMapping("/oi-analysis")
  public Map<String, Object> oiAnalysis(
      @RequestParam(required = false) String mode,
      @RequestParam String name,
      @RequestParam(required = false) String date,
      @RequestParam(required = false) String interval,
      @RequestParam(required = false) String expiry) {
    OiQuery q = OiQuery.of(mode, name, date, interval, expiry);
    // live = the most recent snapshot bucket per contract (clock-independent via max(ts));
    // a date-scoped window in history mode is a follow-on.
    List<FuturesSnapshotReader.FutPoint> pts = reader.latest(q.name(), q.interval());
    return Map.of("items", pts);
  }
}
