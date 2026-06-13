package in.arthayantra.backtest.replay.options;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/**
 * Pins the §D.15 provenance resolution: non-options strategies are {@code NA}, options strategies
 * are detected, and the current candle-replay path is honestly {@code NA} (never masquerading as
 * snapshot-grade) while the premium-as-primary integration remains the documented deep piece.
 */
class PremiumProvenanceTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final PremiumProvenance provenance = new PremiumProvenance();

  @Test
  void detectsAnOptionsStrategy() {
    assertThat(provenance.isOptionsStrategy(optionsConfig())).isTrue();
  }

  @Test
  void aNonOptionsStrategyIsNotDetectedAsOptions() {
    assertThat(provenance.isOptionsStrategy(explicitConfig())).isFalse();
    assertThat(provenance.isOptionsStrategy(mapper.createObjectNode())).isFalse();
    assertThat(provenance.isOptionsStrategy(null)).isFalse();
  }

  @Test
  void candleReplayPathIsNaForNonOptions() {
    assertThat(provenance.forCandleReplay(explicitConfig())).isEqualTo(PremiumSource.NA);
  }

  @Test
  void candleReplayPathIsNaForOptionsToo() {
    // honest: the candle path trades the underlying close, not the premium series, so an options
    // run is NOT snapshot-grade and is recorded NA — never SNAPSHOT — until premium-as-primary lands.
    assertThat(provenance.forCandleReplay(optionsConfig())).isEqualTo(PremiumSource.NA);
  }

  private ObjectNode optionsConfig() {
    ObjectNode config = mapper.createObjectNode();
    ObjectNode universe = config.putObject("universe");
    universe.put("mode", "options_of_underlying");
    ObjectNode underlying = universe.putObject("underlying");
    underlying.put("exchange", "NSE");
    underlying.put("tradingsymbol", "NIFTY 50");
    return config;
  }

  private ObjectNode explicitConfig() {
    ObjectNode config = mapper.createObjectNode();
    config.putObject("universe").put("mode", "explicit");
    return config;
  }
}
