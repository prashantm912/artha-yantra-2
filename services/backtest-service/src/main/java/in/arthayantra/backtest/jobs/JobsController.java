package in.arthayantra.backtest.jobs;

import in.arthayantra.backtest.replay.RunRepository;
import in.arthayantra.backtest.replay.counterfactual.CounterfactualRunRepository;
import in.arthayantra.common.web.http.ArthaHeaders;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The §D.5 backtest job surface under {@code /api/v1/backtests/**}. */
@RestController
@RequestMapping("/api/v1/backtests")
public class JobsController {

  private final JobsService service;
  private final RunRepository runs;
  private final CounterfactualRunRepository counterfactualRuns;

  /** Wires the service + run repositories (for the resultRef). */
  public JobsController(
      JobsService service, RunRepository runs, CounterfactualRunRepository counterfactualRuns) {
    this.service = service;
    this.runs = runs;
    this.counterfactualRuns = counterfactualRuns;
  }

  /** Submit a backtest → 202 with the jobId (§D.5). */
  @PostMapping("/run")
  public ResponseEntity<Map<String, Object>> run(@RequestBody BacktestRunRequest request) {
    Job job = service.submit(request, MDC.get(ArthaHeaders.MDC_REQUEST_ID));
    return ResponseEntity.accepted()
        .body(Map.of("jobId", job.id().toString(), "status", job.status().db()));
  }

  /** Paged job list with optional status + strategyId filters. */
  @GetMapping("/jobs")
  public Map<String, Object> jobs(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String strategyId,
      @RequestParam(required = false) String strategyIds,
      @RequestParam(required = false) String currentVersions,
      @RequestParam(required = false) String sortBy,
      @RequestParam(required = false) String sortDir,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    int boundedLimit = Math.min(Math.max(limit, 1), 500);
    int boundedOffset = Math.max(offset, 0);
    List<Job> jobs =
        service.list(
            status, strategyId, strategyIds, currentVersions, boundedLimit, boundedOffset, sortBy,
            sortDir);
    // One batch query for the page's completed-run returns, so the list shows a returns column
    // without an N+1 per-row fetch.
    Map<UUID, String> returns =
        runs.findReturnsByJobIds(jobs.stream().map(Job::id).toList());
    List<Map<String, Object>> items =
        jobs.stream().map(job -> summary(job, returns.get(job.id()))).toList();
    return Map.of("items", items, "limit", boundedLimit, "offset", boundedOffset);
  }

  /** Single job status/progress. */
  @GetMapping("/jobs/{jobId}")
  public Map<String, Object> job(@PathVariable UUID jobId) {
    Job job = service.get(jobId);
    return detail(job, resultRef(job));
  }

  /**
   * The resultRef (run id) for a job's status payload: a COUNTERFACTUAL job's run lives in
   * {@code counterfactual_runs} (EVO E3 item 9), every other kind in {@code backtest_runs} — so the
   * poller reads the right result store from the same status shape.
   */
  private String resultRef(Job job) {
    java.util.Optional<UUID> runId =
        job.kind() == JobKind.COUNTERFACTUAL
            ? counterfactualRuns.findRunIdByJobId(job.id())
            : runs.findRunIdByJobId(job.id());
    return runId.map(UUID::toString).orElse(null);
  }

  /** Cancel: 204 if still queued, 202 {@code cancelling} if running (observed at a checkpoint). */
  @DeleteMapping("/jobs/{jobId}")
  public ResponseEntity<Map<String, Object>> cancel(@PathVariable UUID jobId) {
    JobsService.CancelOutcome outcome = service.cancel(jobId);
    if (outcome == JobsService.CancelOutcome.CANCELLED) {
      return ResponseEntity.noContent().build();
    }
    return ResponseEntity.accepted().body(Map.of("status", "cancelling"));
  }

  private static Map<String, Object> summary(Job job, String totalReturn) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("jobId", job.id().toString());
    map.put("kind", job.kind().name());
    map.put("status", job.status().db());
    map.put("progress", job.progress());
    map.put("createdAt", job.createdAt());
    // The originating strategy (UUID; the UI maps it to a name) + the completed run's total return
    // (plain decimal string, null until a run exists) so the list shows strategy + returns columns.
    map.put("strategyId", job.request() == null ? null : job.request().path("strategyId").asText(null));
    // The resolved version this job ran (pinned into the request JSONB at submit, JobsService.submit);
    // the UI compares it to the strategy's latest version to flag is-latest + filter old-version jobs.
    map.put("strategyVersion", job.request() == null ? null : job.request().path("strategyVersion").asText(null));
    map.put("totalReturn", totalReturn);
    // The tested window [from, to] (the "date the test was run over") — a plain date or full
    // offset date-time as the request carries it; the UI slices to the date for the Start/End columns.
    map.put("testFrom", job.request() == null ? null : job.request().path("from").asText(null));
    map.put("testTo", job.request() == null ? null : job.request().path("to").asText(null));
    return map;
  }

  private static Map<String, Object> detail(Job job, String resultRef) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("jobId", job.id().toString());
    map.put("kind", job.kind().name());
    map.put("status", job.status().db());
    map.put("progress", job.progress());
    map.put("startedAt", job.startedAt());
    map.put("finishedAt", job.finishedAt());
    map.put("error", job.error());
    map.put("resultRef", resultRef);
    return map;
  }
}
