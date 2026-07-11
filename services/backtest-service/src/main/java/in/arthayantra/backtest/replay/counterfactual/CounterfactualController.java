package in.arthayantra.backtest.replay.counterfactual;

import in.arthayantra.backtest.jobs.Job;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.error.NotFoundException;
import in.arthayantra.common.web.http.ArthaHeaders;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The counterfactual-replay surface (EVO E3 item 9) under {@code /api/v1/backtests/counterfactual}. A
 * dedicated pair of endpoints — kept off {@code /run} + {@code /{id}/results} so the strategy-backtest
 * contract stays clean, and typed-record only (never {@code Map<String,Object>}) so both enumerate into
 * the OpenAPI spec and stay clear of the Map-return ratchet.
 */
@RestController
@RequestMapping("/api/v1/backtests/counterfactual")
public class CounterfactualController {

  private final CounterfactualService service;
  private final CounterfactualRunRepository runs;

  /** Wires the service + run repository (for the result read). */
  public CounterfactualController(CounterfactualService service, CounterfactualRunRepository runs) {
    this.service = service;
    this.runs = runs;
  }

  /** Submit a counterfactual replay → 202 with the queued jobId. */
  @PostMapping
  public ResponseEntity<CounterfactualSubmitResponse> submit(
      @RequestBody CounterfactualRunRequest request) {
    Job job = service.submit(request, MDC.get(ArthaHeaders.MDC_REQUEST_ID));
    return ResponseEntity.accepted()
        .body(new CounterfactualSubmitResponse(job.id().toString(), job.status().db()));
  }

  /** Per-variant metrics for one completed counterfactual run (keyed by the resultRef). */
  @GetMapping("/{runId}")
  public CounterfactualResult result(@PathVariable UUID runId) {
    return runs
        .findResult(runId)
        .orElseThrow(
            () ->
                new NotFoundException(
                    ErrorCodes.NOT_FOUND_RESOURCE, "no such counterfactual run: " + runId));
  }
}
