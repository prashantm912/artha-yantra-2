package in.arthayantra.backtest.experiments;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The audit §11.4 / §13 #8+#11 experiment read layer — a runs-as-experiments list and a server-side
 * compare matrix, so Prompt 2 / EVO target ONE experiment API instead of scraping the jobs table and
 * stitching per-run {@code /results} fetches client-side.
 *
 * <p>Hosted under the already-allowlisted {@code /api/v1/backtests/**} gateway route (§11.4 sketches
 * {@code /api/v1/experiments}, which would need a new gateway prefix; the surface is backtest-service's
 * — it owns the run rows — so it lands under {@code /backtests/experiments}). Both handlers return
 * TYPED records (the Map-return ratchet is at capacity) and read only the {@code experiment_runs} view.
 */
@RestController
@RequestMapping("/api/v1/backtests")
public class ExperimentController {

  /** Upper bound on runs compared in one call — a wider set is a list-endpoint job, not a matrix. */
  private static final int MAX_COMPARE = 24;

  private final ExperimentRepository experiments;

  /** Wires the repository. */
  public ExperimentController(ExperimentRepository experiments) {
    this.experiments = experiments;
  }

  /**
   * Runs-as-experiments, newest first. Optional filters: {@code strategyId} (the originating strategy
   * UUID), {@code strategyVersionId} (the resolved version), {@code kind} (BACKTEST / TRIAL). Bounded
   * page (limit 1..500, default 50).
   */
  @GetMapping("/experiments")
  public ExperimentListResponse experiments(
      @RequestParam(required = false) String strategyId,
      @RequestParam(required = false) UUID strategyVersionId,
      @RequestParam(required = false) String kind,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    int boundedLimit = Math.min(Math.max(limit, 1), 500);
    int boundedOffset = Math.max(offset, 0);
    List<ExperimentSummary> items =
        experiments.list(strategyId, strategyVersionId, kind, boundedLimit, boundedOffset);
    return new ExperimentListResponse(items, boundedLimit, boundedOffset);
  }

  /**
   * The server-side compare matrix for {@code runIds} (comma-separated, capped at {@value
   * #MAX_COMPARE}) — metrics per run in requested order + the {@link LikeForLike} comparability
   * verdict. Ids that resolve to no completed run are absent. Empty / absent {@code runIds} ⇒ empty
   * matrix.
   */
  @GetMapping("/experiments/compare")
  public ExperimentCompareResponse compare(@RequestParam(required = false) List<UUID> runIds) {
    List<UUID> bounded = runIds == null ? List.of() : runIds.stream().limit(MAX_COMPARE).toList();
    List<ExperimentCompareRun> runs = experiments.compare(bounded);
    return new ExperimentCompareResponse(runs, likeForLike(runs));
  }

  /**
   * True on a legacy axis iff every compared run shares one value (a NULL is its own value — the FE
   * banner convention). The two roadmap-#30 axes are STRICTER (review F3): a NULL there means UNKNOWN
   * content, and unknown is never comparable — any NULL {@code contentHash}/{@code datasetEpoch} in the
   * set makes that axis {@code false}, so a set of pre-V015 rows can't pass the refuse-to-rank gate.
   */
  private static LikeForLike likeForLike(List<ExperimentCompareRun> runs) {
    return new LikeForLike(
        allSame(runs, ExperimentCompareRun::dataHash),
        allSame(runs, ExperimentCompareRun::universeChecksum),
        allSame(runs, ExperimentCompareRun::engineSha),
        allSame(runs, ExperimentCompareRun::premiumSource),
        // roadmap #30: the content-stable data axis + the epoch-scope axis — a ranking client refuses a
        // set that is not like-for-like on (engineSha, contentHash, datasetEpoch) unless it re-runs.
        allSameNonNull(runs, ExperimentCompareRun::contentHash),
        allSameNonNull(runs, ExperimentCompareRun::datasetEpoch),
        // review F2: any compared run whose content hash carries count-only option-premium legs makes
        // the whole set's content-equality claim weaker — surface it beside the flags it qualifies.
        runs.stream().anyMatch(r -> Boolean.TRUE.equals(r.premiumContentUnverified())));
  }

  private static boolean allSame(
      List<ExperimentCompareRun> runs, Function<ExperimentCompareRun, String> axis) {
    Set<String> distinct = new HashSet<>();
    for (ExperimentCompareRun run : runs) {
      String value = axis.apply(run);
      distinct.add(value == null ? " null" : value);
    }
    return distinct.size() <= 1;
  }

  /** Like {@link #allSame} but any NULL fails the axis (unknown ≠ comparable); empty set stays true. */
  private static boolean allSameNonNull(
      List<ExperimentCompareRun> runs, Function<ExperimentCompareRun, String> axis) {
    Set<String> distinct = new HashSet<>();
    for (ExperimentCompareRun run : runs) {
      String value = axis.apply(run);
      if (value == null) {
        return false;
      }
      distinct.add(value);
    }
    return distinct.size() <= 1;
  }
}
