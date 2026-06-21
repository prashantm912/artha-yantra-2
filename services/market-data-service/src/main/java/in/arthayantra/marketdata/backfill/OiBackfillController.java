package in.arthayantra.marketdata.backfill;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Market-data admin surface — OI-backfill trigger (data-foundation milestone, plan §2). Imports a
 * past session's OI into the snapshot tables so the Wave-1 data pages can be value-verified in
 * History mode. 202 + {@code jobId} (async), 409 if a backfill is already running, 503 if the
 * OpenAlgo history source is not configured (live profile + {@code
 * artha.openalgo.oi-backfill-enabled=true}).
 */
@RestController
@RequestMapping("/api/v1/market/admin")
public class OiBackfillController {

  /** Backfill request: which (underlying, option-expiry) chain on which session date. */
  public record BackfillRequest(String underlying, LocalDate expiry, LocalDate date) {}

  private final OiBackfillService service;

  public OiBackfillController(OiBackfillService service) {
    this.service = service;
  }

  /** Triggers a backfill run — 202 + jobId. */
  @PostMapping("/oi-backfill")
  public ResponseEntity<Map<String, String>> backfill(@RequestBody BackfillRequest request) {
    if (request.underlying() == null || request.expiry() == null || request.date() == null) {
      throw new ApiException(
          400, ErrorCodes.VALIDATION_FAILED, "underlying, expiry and date are required");
    }
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(service.triggerAsync(request.underlying(), request.expiry(), request.date()));
  }
}
