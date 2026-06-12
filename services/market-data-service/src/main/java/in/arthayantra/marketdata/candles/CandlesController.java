package in.arthayantra.marketdata.candles;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The Phase-11 REST surface (B-1): cache-first candles + forced refresh. */
@RestController
@RequestMapping("/api/v1/market/candles")
public class CandlesController {

  /** Response shape: items + staleness markers + paging (COMMON §3). */
  public record CandlesResponse(
      List<Candle> items, boolean stale, OffsetDateTime asOf, int limit, int offset, int total) {}

  /** Refresh request body. */
  public record RefreshRequest(
      String exchange, String tradingsymbol, String interval, OffsetDateTime from, OffsetDateTime to) {}

  private final CandleQueryService queryService;

  /** Wires the read path. */
  public CandlesController(CandleQueryService queryService) {
    this.queryService = queryService;
  }

  /** Cache-first OHLCV; prices serialize as decimal strings (platform Jackson). */
  @GetMapping
  public ResponseEntity<CandlesResponse> candles(
      @RequestParam String exchange,
      @RequestParam String tradingsymbol,
      @RequestParam String interval,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
      @RequestParam(defaultValue = "back") String adjust,
      @RequestParam(defaultValue = "5000") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    CandleQueryService.CandleRead read =
        queryService.read(exchange, tradingsymbol, interval, from, to);
    int boundedLimit = Math.min(Math.max(limit, 1), 50_000);
    int boundedOffset = Math.max(offset, 0);
    List<Candle> pageItems =
        read.items().stream().skip(boundedOffset).limit(boundedLimit).toList();
    CandlesResponse body =
        new CandlesResponse(
            pageItems, read.stale(), read.asOf(), boundedLimit, boundedOffset, read.items().size());
    ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
    if (read.stale()) {
      // B-3 fallback: cached data flagged stale, plus the DATA_STALE warning header
      builder.header("X-Artha-Warning", in.arthayantra.common.web.error.ErrorCodes.DATA_STALE);
    }
    return builder.body(body);
  }

  /** Forced re-fetch — 202 + jobId, async on the service executor (rate-limit bound). */
  @PostMapping("/refresh")
  public ResponseEntity<Map<String, String>> refresh(@RequestBody RefreshRequest request) {
    Map<String, String> job =
        queryService.refreshAsync(
            request.exchange(), request.tradingsymbol(), request.interval(),
            request.from(), request.to());
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(job);
  }
}
