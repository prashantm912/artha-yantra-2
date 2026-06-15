package in.arthayantra.marketdata.futures.analytics;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.marketdata.options.OiQuery;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
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
  private final FuturesBuzzService buzzService;
  private final int buzzBuckets;

  public FuturesAnalyticsController(
      FuturesSnapshotReader reader,
      FuturesSpurtService spurtService,
      FuturesMoversService moversService,
      FuturesBuzzService buzzService,
      @Value("${artha.futures.buzz-buckets:12}") int buzzBuckets) {
    this.reader = reader;
    this.spurtService = spurtService;
    this.moversService = moversService;
    this.buzzService = buzzService;
    this.buzzBuckets = buzzBuckets;
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

  /** /buzz: a time x contract heatmap of the 4-state OI interpretation over the last N buckets. */
  @GetMapping("/buzz")
  public FuturesBuzzService.BuzzMatrix buzz(
      @RequestParam(required = false) String mode,
      @RequestParam String name,
      @RequestParam(required = false) String date,
      @RequestParam(required = false) String interval,
      @RequestParam(required = false) String expiry) {
    OiQuery q = OiQuery.of(mode, name, date, interval, expiry);
    List<FuturesSnapshotReader.FutPoint> latest = reader.latest(q.name(), q.interval(), q.date());
    if (latest.isEmpty()) {
      return new FuturesBuzzService.BuzzMatrix(List.of(), List.of(), List.of(), null);
    }
    OffsetDateTime newest = latest.get(0).bucket();
    OffsetDateTime from = newest.minus(q.interval().bucket().multipliedBy(buzzBuckets - 1L));
    List<FuturesSnapshotReader.FutPoint> series =
        reader.series(q.name(), q.interval(), from, newest.plus(q.interval().bucket()));
    return buzzService.buzz(series);
  }

  /** /eod: per-contract daily OHLC + OI rollup over [from, to] (defaults to=from). */
  @GetMapping("/eod")
  public Map<String, Object> eod(
      @RequestParam String name,
      @RequestParam String from,
      @RequestParam(required = false) String to) {
    LocalDate fromDate = parseDate(from);
    LocalDate toDate = to == null || to.isBlank() ? fromDate : parseDate(to);
    return Map.of("items", reader.eod(name, fromDate, toDate));
  }

  private static LocalDate parseDate(String raw) {
    try {
      return LocalDate.parse(raw);
    } catch (DateTimeParseException e) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "date must be ISO yyyy-MM-dd");
    }
  }
}
