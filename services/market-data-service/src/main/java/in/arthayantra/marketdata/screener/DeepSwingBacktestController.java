package in.arthayantra.marketdata.screener;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.marketdata.screener.manas.ManasAroraBacktestService;
import in.arthayantra.marketdata.screener.minervini.MinerviniBacktestService;
import java.util.Locale;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The DEEP_SWING internal endpoint (research-fidelity audit P0-3) under {@code
 * /api/v1/market/screener/deep-swing} — a parameterized, SYNCHRONOUS deep-sim run that returns ONE
 * variant's portfolio report + per-trade rows as a typed record, called by the backtest-service
 * DEEP_SWING worker over HTTP so the deep-sim STAYS where its ~11-year {@code candles}@1d universe
 * lives (the simulator is never ported). The existing background-thread {@code /swing-backtest}
 * trigger + GET endpoints STAY (owner uses them); this job route is purely additive.
 *
 * <p>Path is under {@code /api/v1/market/**} so the edge-gateway allowlist already covers it (it is
 * therefore OWNER-reachable through the gateway too — the same trigger surface as the existing
 * {@code /swing-backtest} POSTs, just synchronous); typed record only (never
 * {@code Map<String,Object>}) so it enumerates into the springdoc contract and stays clear of the
 * market-data Map-return ratchet. Respects the shared single-permit {@link SwingBacktestGate}
 * (audit M25) so a job-triggered sim never stacks a second ~11-year run onto the small market-data
 * heap alongside an owner-triggered one — the ACTUAL concurrency bound for the job route. A
 * DEEP_SWING worker whose call hits the busy 409 fails that job (nothing auto-retries it; the
 * worker-side single-flight budget makes that near-unreachable from the job route — only an
 * owner-triggered background sim can hold the permit).
 */
@RestController
@RequestMapping("/api/v1/market/screener/deep-swing")
public class DeepSwingBacktestController {

  private final ManasAroraBacktestService manas;
  private final MinerviniBacktestService minervini;
  private final SwingBacktestGate gate;
  private final String engineSha;
  private final String engineImage;

  /** Wires both deep-sim services + the shared heap gate; resolves this jar's engine identity once. */
  public DeepSwingBacktestController(
      ManasAroraBacktestService manas,
      MinerviniBacktestService minervini,
      SwingBacktestGate gate,
      ObjectProvider<GitProperties> git,
      ObjectProvider<BuildProperties> build) {
    this.manas = manas;
    this.minervini = minervini;
    this.gate = gate;
    GitProperties g = git.getIfAvailable();
    BuildProperties b = build.getIfAvailable();
    // Fail-soft (mirrors backtest-service EngineIdentity): a jar built without git.properties /
    // build-info leaves both null → the backtest_runs engine_sha/engine_image columns stay NULL.
    this.engineSha = g == null ? null : g.getCommitId();
    this.engineImage =
        b == null ? null : (b.getArtifact() == null ? b.getName() : b.getArtifact()) + ":" + b.getVersion();
  }

  /**
   * Runs the parameterized deep-sim synchronously and returns the variant's portfolio report +
   * per-trade rows + THIS jar's engine identity. 422 on a missing/unknown family or unknown variant;
   * 409 when the shared deep-sim permit is held (retry once it frees). The run itself is minutes —
   * the caller (the DEEP_SWING worker) holds a platform thread for its duration by design.
   */
  @PostMapping("/run")
  public DeepSwingRunResponse run(@RequestBody DeepSwingRunRequest request) {
    if (request == null || request.family() == null || request.family().isBlank()) {
      throw new ApiException(422, ErrorCodes.VALIDATION_FAILED, "family is required (manas | minervini)");
    }
    if (request.from() == null) {
      throw new ApiException(422, ErrorCodes.VALIDATION_FAILED, "from (deep-sim start date) is required");
    }
    String family = request.family().trim().toLowerCase(Locale.ROOT);
    if (!"manas".equals(family) && !"minervini".equals(family)) {
      throw new ApiException(
          422, ErrorCodes.VALIDATION_FAILED, "family must be 'manas' or 'minervini'; got " + request.family());
    }
    if (!gate.tryAcquire()) {
      throw new ApiException(
          409,
          ErrorCodes.CONFLICT_BACKFILL_RUNNING,
          "a swing deep-sim is already running; retry once it completes");
    }
    try {
      DeepSwingRunResult result =
          "manas".equals(family)
              ? manas.runForJob(request.from(), request.variant())
              : minervini.runForJob(request.from(), request.variant());
      return new DeepSwingRunResponse(engineSha, engineImage, result);
    } catch (IllegalArgumentException badVariant) {
      throw new ApiException(422, ErrorCodes.VALIDATION_FAILED, badVariant.getMessage());
    } finally {
      gate.release();
    }
  }
}
