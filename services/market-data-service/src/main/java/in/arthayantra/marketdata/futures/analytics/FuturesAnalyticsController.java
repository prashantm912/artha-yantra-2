package in.arthayantra.marketdata.futures.analytics;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketdata.options.OiInterval;
import in.arthayantra.marketdata.options.OiQuery;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
  private final FuturesBankGridService bankGridService;
  private final int buzzBuckets;
  private final List<String> bankStocks;

  public FuturesAnalyticsController(
      FuturesSnapshotReader reader,
      FuturesSpurtService spurtService,
      FuturesMoversService moversService,
      FuturesBuzzService buzzService,
      FuturesBankGridService bankGridService,
      @Value("${artha.futures.buzz-buckets:12}") int buzzBuckets,
      @Value(
              "${artha.futures.bank-stocks:HDFCBANK,ICICIBANK,SBIN,AXISBANK,KOTAKBANK,INDUSINDBK,"
                  + "BANKBARODA,PNB,AUBANK,FEDERALBNK,IDFCFIRSTB,CANBK,BANDHANBNK,BANKINDIA,"
                  + "RBLBANK,UNIONBANK,YESBANK}")
          List<String> bankStocks) {
    this.reader = reader;
    this.spurtService = spurtService;
    this.moversService = moversService;
    this.buzzService = buzzService;
    this.bankGridService = bankGridService;
    this.buzzBuckets = buzzBuckets;
    this.bankStocks = bankStocks;
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

  /**
   * /oi-analysis-series: the per-interval Futures OI Analysis table (oipulse §futures/oi-analysis) for
   * a SINGLE contract. Returns that contract's RAW per-bucket points for the session containing the
   * latest snapshot (oldest-first); the FE folds the interval ΔOI / ΔLTP / cumulative ΔOI / level-break
   * (off the captured day high/low) / 4-state interpretation columns — mirroring the options
   * strike-series page. The contract = the requested {@code expiry}, else the active front (the
   * tradingsymbol with the most captured buckets that day; ties broken by the nearest expiry). 422 only
   * when the underlying has NO snapshot at all; an {@code expiry} matching no contract -> 200 + empty.
   */
  @GetMapping("/oi-analysis-series")
  public Map<String, Object> oiAnalysisSeries(
      @RequestParam(required = false) String mode,
      @RequestParam String name,
      @RequestParam(required = false) String date,
      @RequestParam(required = false) String interval,
      @RequestParam(required = false) String expiry) {
    OiQuery q = OiQuery.of(mode, name, date, interval, expiry);
    List<FuturesSnapshotReader.FutPoint> latest = reader.latest(q.name(), q.interval(), q.date());
    if (latest.isEmpty()) {
      throw new ApiException(422, ErrorCodes.DATA_GAP, "no snapshot for " + q.name());
    }
    OffsetDateTime newest = latest.get(0).bucket();
    LocalDate day = newest.atZoneSameInstant(Ist.ZONE).toLocalDate();
    OffsetDateTime from = day.atStartOfDay().atOffset(Ist.OFFSET);
    OffsetDateTime to = newest.plus(q.interval().bucket());
    List<FuturesSnapshotReader.FutPoint> series = reader.series(q.name(), q.interval(), from, to);
    List<FuturesSnapshotReader.FutPoint> picked = pickContract(series, q.expiry());
    return Map.of(
        "items", picked,
        "underlying", q.name(),
        "interval", q.interval().token(),
        "asOf", newest);
  }

  /**
   * Picks ONE contract's points from a multi-contract series: the requested {@code expiry} when given
   * (empty when no contract matches), else the active front — the tradingsymbol with the most captured
   * buckets, ties broken by the nearest (earliest) expiry. Deterministic.
   */
  private static List<FuturesSnapshotReader.FutPoint> pickContract(
      List<FuturesSnapshotReader.FutPoint> series, LocalDate expiry) {
    if (expiry != null) {
      return series.stream().filter(p -> expiry.equals(p.expiry())).toList();
    }
    Map<String, Long> counts = new LinkedHashMap<>();
    Map<String, LocalDate> exp = new LinkedHashMap<>();
    for (FuturesSnapshotReader.FutPoint p : series) {
      counts.merge(p.tradingsymbol(), 1L, Long::sum);
      exp.putIfAbsent(p.tradingsymbol(), p.expiry());
    }
    String front =
        counts.keySet().stream()
            .max(
                Comparator.comparingLong((String s) -> counts.get(s))
                    .thenComparing(
                        s -> exp.get(s), Comparator.nullsLast(Comparator.<LocalDate>reverseOrder())))
            .orElse(null);
    return front == null
        ? List.of()
        : series.stream().filter(p -> front.equals(p.tradingsymbol())).toList();
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

  /**
   * /banks-grid: one row per bank-sector underlying = its FRONT future's buildup (sector-wide; no
   * {@code name}). Forward-only data, so it 422s until ≥2 snapshot buckets have accrued.
   */
  @GetMapping("/banks-grid")
  public FuturesBankGridService.BankGrid banksGrid(
      @RequestParam(required = false) String mode,
      @RequestParam(required = false) String date,
      @RequestParam(required = false) String interval) {
    boolean live = mode == null || mode.isBlank() || "live".equalsIgnoreCase(mode);
    OiInterval iv =
        interval == null || interval.isBlank() ? OiInterval.M3 : OiInterval.parse(interval);
    LocalDate d = date == null || date.isBlank() ? null : parseDate(date);
    if (!live && d == null) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "history mode requires date");
    }
    List<FuturesSnapshotReader.FutPoint> pair = reader.latestPairAll(bankStocks, iv, d);
    if (pair.isEmpty()) {
      throw new ApiException(422, ErrorCodes.DATA_GAP, "no bank-stock futures snapshots");
    }
    return bankGridService.grid(pair);
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
