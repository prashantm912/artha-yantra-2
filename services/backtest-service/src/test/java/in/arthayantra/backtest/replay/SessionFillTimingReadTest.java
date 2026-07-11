package in.arthayantra.backtest.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/**
 * {@link BacktestRunner#sessionFillTimingOverride} read-back (EVO §7.1.2). Absent ⇒ {@code null} ⇒
 * the byte-identical default path. STRICT (unlike {@link BacktestRunner#stressSlippageMultiplier}'s
 * fail-soft clamp): an unknown value — reachable only by hand-editing the job row, since submission
 * 422s it — THROWS rather than silently defaulting, because a silently-ignored pin would run the sim
 * at the version default and manufacture a fake divergence.
 */
class SessionFillTimingReadTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static ObjectNode requestWithTiming(String timing) {
    ObjectNode request = MAPPER.createObjectNode();
    request.putObject("sessionOverrides").put("fillTiming", timing);
    return request;
  }

  @Test
  void absentBlockReadsAsNull() {
    assertThat(BacktestRunner.sessionFillTimingOverride(MAPPER.createObjectNode())).isNull();
  }

  @Test
  void validTimingReadsBack() {
    assertThat(BacktestRunner.sessionFillTimingOverride(requestWithTiming("at_close")))
        .isEqualTo("at_close");
    assertThat(BacktestRunner.sessionFillTimingOverride(requestWithTiming("next_open")))
        .isEqualTo("next_open");
  }

  @Test
  void mixedCaseNormalizesToCanonical() {
    assertThat(BacktestRunner.sessionFillTimingOverride(requestWithTiming("AT_CLOSE")))
        .isEqualTo("at_close");
  }

  @Test
  void unknownValueThrows() {
    assertThatThrownBy(() -> BacktestRunner.sessionFillTimingOverride(requestWithTiming("mid_bar")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at_close");
  }

  @Test
  void nonTextualReadsAsNull() {
    ObjectNode request = MAPPER.createObjectNode();
    request.putObject("sessionOverrides").put("fillTiming", 5);
    assertThat(BacktestRunner.sessionFillTimingOverride(request)).isNull();
  }
}
