package in.arthayantra.backtest.montecarlo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.arthayantra.backtest.replay.RunRepository;
import in.arthayantra.backtest.replay.RunRepository.MonteCarloRun;
import in.arthayantra.backtest.replay.TradeRepository;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.error.NotFoundException;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * §D.16 Monte Carlo orchestration: read the run's closed-trade P&amp;Ls + window span, bootstrap via
 * {@link MonteCarlo}, and <b>compute-once-persist-serve</b> — the first call (or an explicit
 * different {@code seed}/{@code n}) computes and persists to {@code backtest_runs.montecarlo_summary};
 * repeat calls with the same parameters serve the persisted summary verbatim. Never reruns a replay.
 */
@Service
public class MonteCarloService {

  private static final double YEAR_SECONDS = 365.25 * 24 * 3600;
  private static final int DEFAULT_N = 1000;
  private static final int MIN_N = 10;
  private static final int MAX_N = 10000;
  /** The §D.4 default {@code constraints.min_trades}; drives the "insufficient sample" framing. */
  private static final int MIN_TRADES = 30;

  private final RunRepository runs;
  private final TradeRepository trades;
  private final MonteCarlo monteCarlo;
  private final SecureRandom seedSource = new SecureRandom();

  /** Wires the repositories + the bootstrap. */
  public MonteCarloService(RunRepository runs, TradeRepository trades, MonteCarlo monteCarlo) {
    this.runs = runs;
    this.trades = trades;
    this.monteCarlo = monteCarlo;
  }

  /**
   * Computes (or serves the persisted) Monte Carlo summary for a run.
   *
   * @param runId the backtest run id
   * @param requestedN per-request resample count override ({@code null} → default 1000)
   * @param requestedSeed explicit RNG seed ({@code null} → reuse persisted, else a fresh recorded seed)
   * @return the summary JSON
   * @throws NotFoundException 404 when the run id is unknown
   * @throws ApiException 422 when the run has no closed trades to resample
   */
  public JsonNode compute(UUID runId, Integer requestedN, Long requestedSeed) {
    MonteCarloRun run =
        runs.findForMonteCarlo(runId)
            .orElseThrow(
                () ->
                    new NotFoundException(
                        ErrorCodes.NOT_FOUND_RESOURCE, "no such backtest run: " + runId));

    List<BigDecimal> pnls = trades.findClosedPnls(runId);
    if (pnls.isEmpty()) {
      throw new ApiException(
          422, ErrorCodes.VALIDATION_FAILED, "no closed trades to resample for run " + runId);
    }

    int n = requestedN == null ? DEFAULT_N : Math.min(Math.max(requestedN, MIN_N), MAX_N);
    JsonNode existing = run.montecarloSummary();

    long seed;
    if (requestedSeed != null) {
      seed = requestedSeed;
    } else if (existing != null) {
      seed = existing.path("mcSeed").asLong();
    } else {
      seed = seedSource.nextLong();
    }

    // serve the persisted summary verbatim when the parameters match (idempotent repeat call).
    if (existing != null
        && existing.path("mcSeed").asLong() == seed
        && existing.path("n").asInt() == n) {
      return existing;
    }

    double years = years(run);
    ObjectNode summary = monteCarlo.run(pnls, run.initialEquity(), years, n, seed, MIN_TRADES);
    runs.updateMonteCarloSummary(runId, summary);
    return summary;
  }

  private static double years(MonteCarloRun run) {
    if (run.startTs() == null || run.endTs() == null) {
      return 0;
    }
    return Duration.between(run.startTs(), run.endTs()).toSeconds() / YEAR_SECONDS;
  }
}
