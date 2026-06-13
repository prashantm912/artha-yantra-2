package in.arthayantra.backtest.replay.options;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * Resolves the {@link PremiumSource} provenance for a run from the strategy config's universe mode
 * (§D.15). Every run MUST carry a non-null premium source, written before results persist.
 *
 * <p><b>Scope note (Phase 30A).</b> The premium-as-primary integration — replaying the option
 * PREMIUM series (snapshot LTP or synthetic) as the tradeable price instead of the underlying candle
 * close — is the remaining DEEP piece (see {@code OptionsReplayNote}). The Phase-30 candle replay
 * still trades the underlying candle close, so an options run on the current path is NOT
 * snapshot-grade and is recorded as {@link PremiumSource#NA} rather than masquerading as {@code
 * SNAPSHOT}. The {@link SnapshotPremiumReader} archive-coverage pre-flight and {@link
 * SyntheticPremium} reconstruction machinery are nonetheless fully built and unit/IT-tested (their
 * wiring into {@code ReplayEngine} is the deferred swap), so the integration is a localized swap of
 * the replay tradeable series once parity goldens for the premium-as-primary path exist. When that
 * swap lands, the synthetic path MUST merge {@link SyntheticPremium.Result#caveats()} into the
 * metrics JSONB (the {@code caveats} carrier {@code RunRepository.findResult} reads) so the caveats
 * surface in the results payload, and {@code forCandleReplay}'s successor MUST return {@code
 * SNAPSHOT}/{@code SYNTHETIC_B76} for those runs.
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
   * Resolves the provenance for the current Phase-30 candle-replay path: {@link PremiumSource#NA}
   * for non-options strategies, and {@link PremiumSource#NA} for options strategies too while the
   * premium-as-primary integration remains the documented deep piece — never {@code SNAPSHOT}, so a
   * non-snapshot-grade run can never masquerade as snapshot-grade.
   *
   * @param config the resolved strategy config (may be null)
   * @return the non-null premium source to persist
   */
  public PremiumSource forCandleReplay(JsonNode config) {
    // Both branches return NA on the candle path: a non-options run has no premium series, and an
    // options run trades the underlying candle close here (not the premium series) so it is not
    // snapshot-grade. The provenance flag stays honest rather than overstating fidelity.
    return PremiumSource.NA;
  }
}
