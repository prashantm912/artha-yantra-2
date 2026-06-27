package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategyengine.config.StrategyDefinition;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * W3 PR-4: the {@code index_points} additive fallback/cap stop helpers. The point SL is a FALLBACK
 * when no other stop is set and a CAP (the tighter of two) on a too-wide structural stop. The basis
 * is absent from every existing YAML, so production behaviour is unchanged.
 */
class SignalEnginePointStopTest {

  private static StrategyDefinition.ExitRuleSpec pointStop(int pts) {
    return new StrategyDefinition.ExitRuleSpec(
        "stop_loss", Map.of("basis", "index_points", "value", pts));
  }

  @Test
  void indexPointStopBelowEntryForLongAboveForShort() {
    BigDecimal entry = new BigDecimal("100");
    assertThat(SignalEngine.indexPointStopLevel(List.of(pointStop(55)), false, entry))
        .isEqualByComparingTo("45"); // long: entry - 55
    assertThat(SignalEngine.indexPointStopLevel(List.of(pointStop(55)), true, entry))
        .isEqualByComparingTo("155"); // short: entry + 55
  }

  @Test
  void noIndexPointRuleYieldsNull() {
    BigDecimal entry = new BigDecimal("100");
    assertThat(SignalEngine.indexPointStopLevel(List.of(), false, entry)).isNull();
    assertThat(
            SignalEngine.indexPointStopLevel(
                List.of(
                    new StrategyDefinition.ExitRuleSpec(
                        "stop_loss", Map.of("basis", "premium_pct", "value", 50))),
                false,
                entry))
        .isNull();
  }

  @Test
  void closerToEntryPicksTheTighterStop() {
    BigDecimal entry = new BigDecimal("100");
    // structural 70 (dist 30) vs point 45 (dist 55) -> keep the structural stop (tighter)
    assertThat(SignalEngine.closerToEntry(entry, new BigDecimal("70"), new BigDecimal("45")))
        .isEqualByComparingTo("70");
    // structural 10 (dist 90) vs point 45 (dist 55) -> cap to the point stop (tighter)
    assertThat(SignalEngine.closerToEntry(entry, new BigDecimal("10"), new BigDecimal("45")))
        .isEqualByComparingTo("45");
  }
}
