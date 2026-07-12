package in.arthayantra.backtest.provenance;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.error.NotFoundException;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The dataset-comparability surface (audit P1-1 / R2, roadmap #22a/#30) under the already-allowlisted
 * {@code /api/v1/backtests/**} route: the data-rewrite epoch ledger (record + list), the per-run
 * provenance block, and the re-run policy staleness verdict. Every handler returns a TYPED record (the
 * backtest-service Map-return ratchet is frozen at 10 — a new Map handler would fail CI).
 */
@RestController
@RequestMapping("/api/v1/backtests")
public class DatasetComparabilityController {

  /** The ledger's reason enum (mirrors the V015 CHECK) — a bad reason is a 422, not a 500. */
  private static final Set<String> REASONS =
      Set.of("CA_READJUST", "AUTHORITATIVE_REFETCH", "BACKFILL", "BHAVCOPY_FILL", "MANUAL");

  private final DatasetComparabilityService service;

  /** Wires the service. */
  public DatasetComparabilityController(DatasetComparabilityService service) {
    this.service = service;
  }

  /**
   * Records a data-rewrite epoch (§11.3) — called by whoever rewrote candle data (a CA re-adjust /
   * authoritative re-fetch / backfill job, or the owner) so runs executed before it become detectably
   * stale. 422 on an unknown {@code reason} or a malformed {@code windowStart}/{@code windowEnd}
   * (review F4 — never a 500 through the catch-all). The recorded row (with its assigned monotonic
   * id) is returned.
   */
  @PostMapping("/dataset-epochs")
  public DatasetEpoch recordEpoch(@RequestBody DatasetEpochRequest request) {
    String reason = request.reason() == null ? null : request.reason().trim().toUpperCase(java.util.Locale.ROOT);
    if (reason == null || !REASONS.contains(reason)) {
      throw new ApiException(
          422,
          ErrorCodes.VALIDATION_FAILED,
          "reason must be one of " + REASONS + "; got " + request.reason());
    }
    DatasetEpochRequest normalized =
        new DatasetEpochRequest(
            reason,
            request.symbols(),
            request.exchange(),
            request.windowStart(),
            request.windowEnd(),
            request.interval(),
            request.jobLink(),
            request.note());
    try {
      return service.record(normalized, "owner");
    } catch (java.time.format.DateTimeParseException e) {
      throw new ApiException(
          422,
          ErrorCodes.VALIDATION_FAILED,
          "windowStart/windowEnd must be an ISO date (yyyy-MM-dd) or offset date-time; got "
              + e.getParsedString());
    }
  }

  /** The recorded epochs, newest first, + the current head (bounded page). */
  @GetMapping("/dataset-epochs")
  public DatasetEpochListResponse epochs(
      @RequestParam(defaultValue = "50") int limit, @RequestParam(defaultValue = "0") int offset) {
    return service.list(Math.min(Math.max(limit, 1), 500), Math.max(offset, 0));
  }

  /** The as-executed provenance block for a run (#22a); 404 when the run id is unknown. */
  @GetMapping("/runs/{runId}/provenance")
  public ProvenanceBlock provenance(@PathVariable UUID runId) {
    return service
        .provenance(runId)
        .orElseThrow(
            () -> new NotFoundException(ErrorCodes.NOT_FOUND_RESOURCE, "no such run: " + runId));
  }

  /** The re-run policy staleness verdict for a run (#30); 404 when the run id is unknown. */
  @GetMapping("/runs/{runId}/comparability")
  public RunComparability comparability(@PathVariable UUID runId) {
    return service
        .comparability(runId)
        .orElseThrow(
            () -> new NotFoundException(ErrorCodes.NOT_FOUND_RESOURCE, "no such run: " + runId));
  }
}
