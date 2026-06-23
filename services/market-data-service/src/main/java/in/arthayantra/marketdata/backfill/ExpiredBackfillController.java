package in.arthayantra.marketdata.backfill;

import in.arthayantra.marketdata.upstox.ExpiredBackfillService;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Market-data admin surface — expired-instruments backfill trigger (data-foundation milestone,
 * backtest data). Imports a year of NIFTY/SENSEX expired option + future per-minute OHLCV+OI into the
 * {@code candles} hypertable via the Upstox Plus expired-instruments API, so strategies can be
 * backtested on real traded option prices. 202 + {@code jobId} (async, long-running), 409 if a
 * backfill is already running, 503 if the Upstox analytics source is not configured (live profile +
 * {@code artha.upstox.analytics.enabled=true}).
 */
@RestController
@RequestMapping("/api/v1/market/admin")
public class ExpiredBackfillController {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final List<String> DEFAULT_UNDERLYINGS = List.of("NIFTY", "SENSEX");

  /**
   * Backfill request — all fields optional. Defaults: both indices, the trailing 365 days, 1-minute.
   * {@code force} = re-fetch + merge even already-complete contracts (a verify pass that closes any
   * residual holes); default false (normal resume — skip complete, re-fetch only short/missing).
   */
  public record ExpiredBackfillRequest(
      List<String> underlyings, LocalDate from, LocalDate to, String interval, Boolean force) {}

  private final ExpiredBackfillService service;

  /** Wires the expired-backfill service. */
  public ExpiredBackfillController(ExpiredBackfillService service) {
    this.service = service;
  }

  /** Triggers an expired backfill run — 202 + jobId. */
  @PostMapping("/expired-backfill")
  public ResponseEntity<Map<String, String>> backfill(
      @RequestBody(required = false) ExpiredBackfillRequest request) {
    ExpiredBackfillRequest r =
        request == null ? new ExpiredBackfillRequest(null, null, null, null, null) : request;
    LocalDate to = r.to() != null ? r.to() : LocalDate.now(IST);
    LocalDate from = r.from() != null ? r.from() : to.minusYears(1);
    List<String> underlyings =
        r.underlyings() == null || r.underlyings().isEmpty() ? DEFAULT_UNDERLYINGS : r.underlyings();
    boolean force = Boolean.TRUE.equals(r.force());
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(service.triggerAsync(underlyings, from, to, r.interval(), force));
  }

  /** Live progress while a run is in flight, else the last-run audit (B1 Collection Status). */
  @GetMapping("/expired-backfill/status")
  public ExpiredBackfillService.Status status() {
    return service.status();
  }
}
