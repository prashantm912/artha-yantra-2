package in.arthayantra.backtest.replay.options;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.backtest.replay.options.PremiumExitEvaluator.Exit;
import in.arthayantra.backtest.replay.options.PremiumExitEvaluator.Rules;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Fixture test for {@link PremiumExitEvaluator}: the premium-pct exits of a long-premium leg fire off
 * the option's OWN premium series, by the fixed precedence STOP_LOSS → TAKE_PROFIT → TRAILING_STOP →
 * TIME_STOP → SIGNAL_EXIT. Entry premium is 100 throughout.
 */
class PremiumExitEvaluatorTest {

  private static final BigDecimal ENTRY = new BigDecimal("100");

  private static List<BigDecimal> series(String... vals) {
    return Stream.of(vals).map(BigDecimal::new).toList();
  }

  private static Rules rules(String sl, String tp, String trailAt, String trailBy, Integer timeBars) {
    return new Rules(
        sl == null ? null : new BigDecimal(sl),
        tp == null ? null : new BigDecimal(tp),
        trailAt == null ? null : new BigDecimal(trailAt),
        trailBy == null ? null : new BigDecimal(trailBy),
        timeBars);
  }

  private static int noSignal(List<BigDecimal> s) {
    return s.size(); // signal exit never reached before end of data
  }

  @Test
  void stopLossFiresWhenPremiumFallsToTheStop() {
    var s = series("100", "90", "78"); // 78 ≤ 80 (100 × (1-0.20))
    Exit e = PremiumExitEvaluator.evaluate(ENTRY, s, rules("20", "35", null, null, null), noSignal(s));
    assertThat(e.reason()).isEqualTo("STOP_LOSS");
    assertThat(e.barOffset()).isEqualTo(2);
    assertThat(e.premium()).isEqualByComparingTo("78");
  }

  @Test
  void takeProfitFiresWhenPremiumRisesToTheTarget() {
    var s = series("100", "120", "140"); // 140 ≥ 135 (100 × 1.35)
    Exit e = PremiumExitEvaluator.evaluate(ENTRY, s, rules("20", "35", null, null, null), noSignal(s));
    assertThat(e.reason()).isEqualTo("TAKE_PROFIT");
    assertThat(e.barOffset()).isEqualTo(2);
  }

  @Test
  void trailingStopFiresAfterArmingOnTheGiveback() {
    // arm at +20% (120), trail 10% off peak. peak 130 → trail stop 117; 116 ≤ 117 triggers.
    var s = series("100", "110", "130", "125", "116");
    Exit e = PremiumExitEvaluator.evaluate(ENTRY, s, rules(null, null, "20", "10", null), noSignal(s));
    assertThat(e.reason()).isEqualTo("TRAILING_STOP");
    assertThat(e.barOffset()).isEqualTo(4);
    assertThat(e.premium()).isEqualByComparingTo("116");
  }

  @Test
  void trailingDoesNotFireBeforeItArms() {
    // never reaches +20% (max 118) → not armed → no trailing exit → end of data.
    var s = series("100", "112", "118", "108");
    Exit e = PremiumExitEvaluator.evaluate(ENTRY, s, rules(null, null, "20", "10", null), noSignal(s));
    assertThat(e.reason()).isEqualTo("END_OF_DATA");
    assertThat(e.barOffset()).isEqualTo(3);
  }

  @Test
  void timeStopFiresAtMaxBars() {
    var s = series("100", "101", "102", "103", "104");
    Exit e = PremiumExitEvaluator.evaluate(ENTRY, s, rules(null, null, null, null, 3), noSignal(s));
    assertThat(e.reason()).isEqualTo("TIME_STOP");
    assertThat(e.barOffset()).isEqualTo(3);
  }

  @Test
  void signalExitFiresAtItsBarWhenNoBracketHit() {
    var s = series("100", "101", "102", "103");
    Exit e = PremiumExitEvaluator.evaluate(ENTRY, s, rules("50", "99", null, null, null), 2);
    assertThat(e.reason()).isEqualTo("SIGNAL_EXIT");
    assertThat(e.barOffset()).isEqualTo(2);
  }

  @Test
  void endOfDataWhenNothingTriggers() {
    var s = series("100", "101", "102");
    Exit e = PremiumExitEvaluator.evaluate(ENTRY, s, rules(null, null, null, null, null), noSignal(s));
    assertThat(e.reason()).isEqualTo("END_OF_DATA");
    assertThat(e.barOffset()).isEqualTo(2);
  }

  @Test
  void stopLossTakesPrecedenceOverSignalExitOnTheSameBar() {
    // at bar 1 the premium is below the stop AND the signal exit is due → STOP_LOSS wins (risk-first).
    var s = series("100", "75");
    Exit e = PremiumExitEvaluator.evaluate(ENTRY, s, rules("20", null, null, null, null), 1);
    assertThat(e.reason()).isEqualTo("STOP_LOSS");
    assertThat(e.barOffset()).isEqualTo(1);
  }
}
