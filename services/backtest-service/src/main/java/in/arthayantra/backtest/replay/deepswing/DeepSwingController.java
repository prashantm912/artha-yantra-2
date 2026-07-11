package in.arthayantra.backtest.replay.deepswing;

import in.arthayantra.backtest.jobs.Job;
import in.arthayantra.common.web.http.ArthaHeaders;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The DEEP_SWING submission surface (research-fidelity audit P0-3) under {@code
 * /api/v1/backtests/deep-swing} — kept off {@code /run} so the strategy-backtest contract stays
 * clean, and typed-record only (never {@code Map<String,Object>}) so it enumerates into the OpenAPI
 * spec and stays clear of the Map-return ratchet. Submission only: the job's status/progress rides
 * the standard {@code GET /backtests/jobs/{id}} (whose {@code resultRef} is the run id), and the
 * completed run is read via the standard {@code /backtests/{runId}/results} + {@code /trades} —
 * DEEP_SWING persists into {@code backtest_runs}/{@code backtest_trades}, so no bespoke read
 * endpoints exist.
 */
@RestController
@RequestMapping("/api/v1/backtests/deep-swing")
public class DeepSwingController {

  private final DeepSwingService service;

  /** Wires the service. */
  public DeepSwingController(DeepSwingService service) {
    this.service = service;
  }

  /** Submit a deep-swing lineage run → 202 with the queued jobId. */
  @PostMapping
  public ResponseEntity<DeepSwingSubmitResponse> submit(@RequestBody DeepSwingRunRequest request) {
    Job job = service.submit(request, MDC.get(ArthaHeaders.MDC_REQUEST_ID));
    return ResponseEntity.accepted()
        .body(new DeepSwingSubmitResponse(job.id().toString(), job.status().db()));
  }
}
