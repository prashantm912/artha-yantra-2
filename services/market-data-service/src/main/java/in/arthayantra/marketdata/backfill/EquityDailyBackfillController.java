package in.arthayantra.marketdata.backfill;

import in.arthayantra.marketdata.upstox.EquityDailyBackfillService;
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
 * Market-data admin surface — the Phase-5 200-day equity daily backfill trigger. Seeds the native
 * {@code candles}@1d history (source {@code BACKFILL}) the Minervini 150/200-day moving averages need,
 * by pulling daily OHLCV for the NSE cash-equity universe from the Upstox historical-candle API. 202 +
 * {@code jobId} (async, long-running), 409 if a backfill is already running, 503 if the Upstox
 * analytics source is not configured (live profile + {@code artha.upstox.analytics.enabled=true}).
 */
@RestController
@RequestMapping("/api/v1/market/admin")
public class EquityDailyBackfillController {

  /**
   * Backfill request — both fields optional. {@code symbols} null/empty ⇒ the whole active-NSE-equity
   * universe; {@code days} null ⇒ the service default (~300 calendar days ≈ 200 trading sessions).
   */
  public record EquityDailyBackfillRequest(List<String> symbols, Integer days) {}

  private final EquityDailyBackfillService service;

  /** Wires the equity-daily-backfill service. */
  public EquityDailyBackfillController(EquityDailyBackfillService service) {
    this.service = service;
  }

  /** Triggers an equity daily backfill run — 202 + jobId. */
  @PostMapping("/equity-daily-backfill")
  public ResponseEntity<Map<String, String>> backfill(
      @RequestBody(required = false) EquityDailyBackfillRequest request) {
    EquityDailyBackfillRequest r =
        request == null ? new EquityDailyBackfillRequest(null, null) : request;
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(service.triggerAsync(r.symbols(), r.days()));
  }

  /** Live progress while a run is in flight, else the last-run audit. */
  @GetMapping("/equity-daily-backfill/status")
  public EquityDailyBackfillService.Status status() {
    return service.status();
  }
}
