package in.arthayantra.marketdata.bhavcopy;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * On-demand control for the EOD bhavcopy universe backfill (Phase A/B). Mirrors the instrument-sync
 * pattern: POST triggers an async run (202 + jobId; 409 {@code CONFLICT_BACKFILL_RUNNING} when one
 * is in flight), GET reports the last-run audit. Auto-proxied by the edge-gateway
 * {@code /api/v1/market/**} route. The frontend "Run now" buttons are deferred to the React
 * migration (no Angular changes in this branch) — see the plan doc.
 */
@RestController
@RequestMapping("/api/v1/market/eod-backfill")
public class EodBackfillController {

  private final BhavcopyBackfillService service;

  public EodBackfillController(BhavcopyBackfillService service) {
    this.service = service;
  }

  /** Trigger a self-healing catch-up (all missing trading days → today). */
  @PostMapping
  public ResponseEntity<Map<String, String>> trigger() {
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("jobId", service.triggerAsync()));
  }

  /** Last-run audit (state + per-exchange day/row/candle tallies). */
  @GetMapping("/status")
  public BhavcopyBackfillService.Status status() {
    return service.status();
  }
}
