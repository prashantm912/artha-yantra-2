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

  @Test
  void higherTimeframesAreTheNonContextDeclarationsAbovePrimaryAnd1m() {
    // §3.2a: only signal-future (instrument == null) indicators whose timeframe is neither the primary
    // (3m) nor 1m must be separately warmed — context indicators + the primary/1m are already covered.
    var primary3m = spec("VWMA", "vwma20", "3m", null);
    var bias1h = spec("SUPERTREND", "bias60m", "1h", null);
    var daily = spec("RSI", "rsiDaily", "1d", null);
    var oneMinute = spec("RSI", "rsi1m", "1m", null);
    var contextHourly = spec("SUPERTREND", "ctx", "1h", new StrategyDefinition.InstrumentRef("NSE", "NIFTY 50"));

    assertThat(
            SignalEngine.higherTimeframes(
                "3m", List.of(primary3m, bias1h, daily, oneMinute, contextHourly)))
        .containsExactlyInAnyOrder("1h", "1d"); // primary 3m, 1m, and the CONTEXT 1h are all excluded
    // no higher-TF indicators → empty set (the common single-timeframe scalper).
    assertThat(SignalEngine.higherTimeframes("3m", List.of(primary3m))).isEmpty();
  }

  private static StrategyDefinition.IndicatorSpec spec(
      String name, String alias, String timeframe, StrategyDefinition.InstrumentRef instrument) {
    return new StrategyDefinition.IndicatorSpec(
        name, alias, timeframe, Map.of(), null, false, null, instrument);
  }
}
