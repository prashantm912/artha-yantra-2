package in.arthayantra.backtest.replay.options;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * Resolves the {@link PremiumSource} provenance for a run from the strategy config's universe mode
 * (§D.15). Every run MUST carry a non-null premium source, written before results persist.
 *
 * <p><b>Part 2 (premium-as-primary, landed).</b> An options strategy is replayed premium-as-primary:
 * {@code BacktestRunner} routes it (via {@link #isOptionsStrategy}) to {@code OptionsPremiumReplay},
 * which fills/marks/exits on the option's OWN backfilled 1m premium series, so the run is recorded as
 * {@link PremiumSource#CANDLE_1M} (see {@link #forCandleReplay}). This path is pinned by the dedicated
 * {@code OptionsPremiumGoldenTest} parity golden, separate from the candle-close {@code
 * BacktestParityTest} (which stays byte-identical).
 *
 * <p>The {@link SnapshotPremiumReader} archive-coverage pre-flight (5-minute {@code
 * options_chain_snapshots}) and the {@link SyntheticPremium} Black-76 reconstruction are fully built
 * and unit/IT-tested but remain UNWIRED alternate sources — the active premium path reads the finer
 * {@code CANDLE_1M} series. Should a future run route through {@link SyntheticPremium}, that path MUST
 * merge {@link SyntheticPremium.Result#caveats()} into the metrics JSONB (the {@code caveats} carrier
 * {@code RunRepository.findResult} reads) and resolve {@link PremiumSource#SNAPSHOT}/{@link
 * PremiumSource#SYNTHETIC_B76} accordingly.
 */
@Component
public class PremiumProvenance {

  /** The universe mode that selects an options-premium series (§D.6 / schema/v1). */
  public static final String OPTIONS_MODE = "options_of_underlying";

  /** True when the strategy config replays an options-premium series (universe.mode == options). */
  public boolean isOptionsStrategy(JsonNode config) {
    return config != null
        && OPTIONS_MODE.equals(config.path("universe").path("mode").asText(null));
  }

  /**
   * Resolves the persisted premium provenance: {@link PremiumSource#CANDLE_1M} for an options strategy
   * (Part 2 — {@code BacktestRunner} routes it to {@code OptionsPremiumReplay}, which trades the
   * option's own 1m premium series), and {@link PremiumSource#NA} for a non-options strategy (no
   * premium series — it trades the underlying candle close).
   *
   * @param config the resolved strategy config (may be null)
   * @return the non-null premium source to persist
   */
  public PremiumSource forCandleReplay(JsonNode config) {
    return isOptionsStrategy(config) ? PremiumSource.CANDLE_1M : PremiumSource.NA;
  }
}
