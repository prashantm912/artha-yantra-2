package in.arthayantra.backtest.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import in.arthayantra.backtest.client.StrategyVersionClient;
import in.arthayantra.backtest.client.StrategyVersionClient.ResolvedVersion;
import in.arthayantra.backtest.provenance.EvidencePolicy;
import in.arthayantra.backtest.replay.CandleReader;
import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves {@code GET /api/v1/backtests/stress-window?strategyId=} (§D.6 S1C): the suggested CLEAN
 * stress window {@code {from, to}} = the day after the latest tested {@code to} across the strategy
 * lineage, through the latest cached candle for the strategy's primary instrument. 404 when the
 * strategy has no lineage jobs at all (a strategy that has never been tested has the whole cache
 * clean, but the suggestion endpoint is meaningful only once a lineage exists — that 404 is raised
 * by {@link StressGuard#suggestCleanWindow}).
 */
@RestController
@RequestMapping("/api/v1/backtests")
public class StressWindowController {

  private final StressGuard stressGuard;
  private final StrategyVersionClient versions;
  private final CandleReader candleReader;

  /** Wires the stress guard + strategy resolver + candle reader. */
  public StressWindowController(
      StressGuard stressGuard, StrategyVersionClient versions, CandleReader candleReader) {
    this.stressGuard = stressGuard;
    this.versions = versions;
    this.candleReader = candleReader;
  }

  /**
   * The suggested clean stress window for a strategy, plus its family {@code evidencePolicy} (roadmap
   * #22b): a StressGuard-aware optimizer client reads the policy to know whether a clean SIM holdout
   * window is even a ranking plane — SIM_FIRST families route final validation through this window,
   * LIVE_FIRST families treat any sim as smoke-only, so the clean-window pick is advisory for them.
   */
  @GetMapping("/stress-window")
  public Map<String, Object> stressWindow(@RequestParam String strategyId) {
    OffsetDateTime latestCandle = latestCachedCandle(strategyId);
    Map<String, Object> window = stressGuard.suggestCleanWindow(strategyId, latestCandle);
    window.put("evidencePolicy", evidencePolicy(strategyId));
    return window;
  }

  /** The strategy's structural evidence policy, fail-soft ({@code null} when unresolvable). */
  private String evidencePolicy(String strategyId) {
    try {
      ResolvedVersion v = versions.resolve(strategyId, null);
      JsonNode config = v == null ? null : v.config();
      return config == null ? null : EvidencePolicy.forConfig(config).name();
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * The latest cached candle for the strategy's primary instrument — resolves the (current) version
   * to read the universe's signal instrument, then reads the candle cache. Returns {@code null} when
   * the instrument cannot be resolved (an index/options universe whose resolution lands in Stage F),
   * in which case {@code to} is left null and the owner picks the end manually.
   */
  private OffsetDateTime latestCachedCandle(String strategyId) {
    try {
      ResolvedVersion v = versions.resolve(strategyId, null);
      JsonNode config = v == null ? null : v.config();
      if (config == null) {
        return null;
      }
      JsonNode instruments = config.path("universe").path("instruments");
      if (instruments.isArray() && !instruments.isEmpty()) {
        JsonNode first = instruments.get(0);
        return candleReader.latestCachedBucket(
            first.path("exchange").asText(), first.path("tradingsymbol").asText());
      }
      String underlying = config.path("universe").path("underlying").asText(null);
      if (underlying != null && underlying.contains(":")) {
        String[] parts = underlying.split(":", 2);
        return candleReader.latestCachedBucket(parts[0], parts[1]);
      }
      return null;
    } catch (RuntimeException e) {
      return null; // a missing/unresolvable strategy version leaves `to` open, never a 500
    }
  }
}
