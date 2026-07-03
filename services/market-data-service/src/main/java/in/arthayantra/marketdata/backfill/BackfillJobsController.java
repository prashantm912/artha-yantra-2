package in.arthayantra.marketdata.backfill;

import in.arthayantra.marketdata.upstox.BackfillJobRepository;
import in.arthayantra.marketdata.upstox.BackfillJobRepository.BackfillJobRow;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Market-data admin surface — the backfill run-audit history (V030 {@code backfill_jobs}). Read-only:
 * the most recent EXPIRED / EQUITY_DAILY runs, newest first, with their status/rows/error/timings, so
 * the Data-Ops console can show a run history alongside the live Collection Status. Auto-proxied by the
 * edge-gateway {@code /api/v1/market/**} route.
 */
@RestController
@RequestMapping("/api/v1/market/admin")
public class BackfillJobsController {

  /** Newest run limit cap — the history view is a short recent list, not a full export. */
  private static final int MAX_LIMIT = 200;

  /** {@code {items:[...]}} envelope (matches the React list convention). */
  public record BackfillJobsResponse(List<BackfillJobRow> items) {}

  private final BackfillJobRepository repo;

  public BackfillJobsController(BackfillJobRepository repo) {
    this.repo = repo;
  }

  /** The most recent backfill runs (newest first); {@code limit} clamps to [1, {@value #MAX_LIMIT}]. */
  @GetMapping("/backfill-jobs")
  public BackfillJobsResponse backfillJobs(
      @RequestParam(name = "limit", defaultValue = "50") int limit) {
    int clamped = Math.max(1, Math.min(limit, MAX_LIMIT));
    return new BackfillJobsResponse(repo.recent(clamped));
  }
}
