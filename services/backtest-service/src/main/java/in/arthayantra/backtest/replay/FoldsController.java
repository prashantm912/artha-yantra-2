package in.arthayantra.backtest.replay;

import com.fasterxml.jackson.databind.JsonNode;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.error.NotFoundException;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves {@code GET /api/v1/backtests/{id}/folds} (§D.5, guard 6 surface): the persisted per-fold
 * train/OOS metric array for a walk-forward (or implicit-70/30) run. Returns {@code []} when the run
 * had no fold structure (plain full-window backtest) and {@code 404} when the run id is unknown —
 * the contract distinction is "no folds" vs "no run". A single repository read decides both: {@link
 * RunRepository#findFoldMetrics} returns {@code null} <b>only</b> when no run row exists (a present
 * row always maps to a non-null array, {@code []} for the NULL column), so {@code null} is the sole,
 * sufficient 404 signal — no second existence query is needed.
 */
@RestController
@RequestMapping("/api/v1/backtests")
public class FoldsController {

  private final RunRepository runs;

  /** Wires the run repository. */
  public FoldsController(RunRepository runs) {
    this.runs = runs;
  }

  /** The fold-metrics array for one run ([] when no walk_forward, 404 when no such run). */
  @GetMapping("/{backtestId}/folds")
  public JsonNode folds(@PathVariable UUID backtestId) {
    JsonNode foldMetrics = runs.findFoldMetrics(backtestId);
    if (foldMetrics == null) {
      throw new NotFoundException(
          ErrorCodes.NOT_FOUND_RESOURCE, "no such backtest run: " + backtestId);
    }
    return foldMetrics;
  }
}
