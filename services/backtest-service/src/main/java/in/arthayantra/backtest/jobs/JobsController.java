package in.arthayantra.backtest.jobs;

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

  /** Wires the service. */
  public JobsController(JobsService service) {
    this.service = service;
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
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    int boundedLimit = Math.min(Math.max(limit, 1), 500);
    int boundedOffset = Math.max(offset, 0);
    List<Map<String, Object>> items =
        service.list(status, strategyId, boundedLimit, boundedOffset).stream()
            .map(JobsController::summary)
            .toList();
    return Map.of("items", items, "limit", boundedLimit, "offset", boundedOffset);
  }

  /** Single job status/progress. */
  @GetMapping("/jobs/{jobId}")
  public Map<String, Object> job(@PathVariable UUID jobId) {
    return detail(service.get(jobId));
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

  private static Map<String, Object> summary(Job job) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("jobId", job.id().toString());
    map.put("kind", job.kind().name());
    map.put("status", job.status().db());
    map.put("progress", job.progress());
    map.put("createdAt", job.createdAt());
    return map;
  }

  private static Map<String, Object> detail(Job job) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("jobId", job.id().toString());
    map.put("kind", job.kind().name());
    map.put("status", job.status().db());
    map.put("progress", job.progress());
    map.put("startedAt", job.startedAt());
    map.put("finishedAt", job.finishedAt());
    map.put("error", job.error());
    map.put("resultRef", null); // backtest_runs ref lands in Phase 30
    return map;
  }
}
