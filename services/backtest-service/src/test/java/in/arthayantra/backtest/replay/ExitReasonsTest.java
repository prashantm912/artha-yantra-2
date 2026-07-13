package in.arthayantra.backtest.replay;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The canonical UPPERCASE exit-reason casing helper (chip task_cf5d58c8). */
class ExitReasonsTest {

  @Test
  void upperCasesLowercaseCandlePathReasons() {
    assertThat(ExitReasons.canonical("stop_loss")).isEqualTo("STOP_LOSS");
    assertThat(ExitReasons.canonical("trailing_stop")).isEqualTo("TRAILING_STOP");
    assertThat(ExitReasons.canonical("take_profit")).isEqualTo("TAKE_PROFIT");
    assertThat(ExitReasons.canonical("square_off")).isEqualTo("SQUARE_OFF");
    assertThat(ExitReasons.canonical("time_stop")).isEqualTo("TIME_STOP");
    assertThat(ExitReasons.canonical("signal_exit")).isEqualTo("SIGNAL_EXIT");
    assertThat(ExitReasons.canonical("end_of_data")).isEqualTo("END_OF_DATA");
  }

  @Test
  void isIdempotentForAlreadyUppercasePaths() {
    // the options-premium / deep-swing paths already emit UPPERCASE — canonical() must be a no-op
    for (String reason :
        new String[] {"STOP_LOSS", "TAKE_PROFIT", "EXPIRY_SETTLEMENT", "FAST_MOVE", "PARABOLIC"}) {
      assertThat(ExitReasons.canonical(reason)).isEqualTo(reason);
    }
  }

  @Test
  void passesNullThrough() {
    // an open-at-end trade may carry a null reason — never NPE, never fabricate a value
    assertThat(ExitReasons.canonical(null)).isNull();
  }
}
