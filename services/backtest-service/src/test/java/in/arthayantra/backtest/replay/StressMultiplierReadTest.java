package in.arthayantra.backtest.replay;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * {@link BacktestRunner#stressSlippageMultiplier} read-back guard (EVO §3.2.5 defense-in-depth):
 * submission 422s a sub-1 multiplier, but the job JSONB is hand-editable — and a sub-1 value read
 * back would FLIP slippage direction so every fill IMPROVES, the one failure class this feature
 * exists to preclude. The runner clamps anything below 1 to 1 (unstressed) on read.
 */
class StressMultiplierReadTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static ObjectNode requestWithMultiplier(String multiplier) {
    ObjectNode request = MAPPER.createObjectNode();
    request.putObject("stressOverrides").put("slippageMultiplier", new BigDecimal(multiplier));
    return request;
  }

  @Test
  void absentBlockReadsAsOne() {
    assertThat(BacktestRunner.stressSlippageMultiplier(MAPPER.createObjectNode()))
        .isEqualByComparingTo("1");
  }

  @Test
  void validMultiplierReadsBack() {
    assertThat(BacktestRunner.stressSlippageMultiplier(requestWithMultiplier("2")))
        .isEqualByComparingTo("2");
    assertThat(BacktestRunner.stressSlippageMultiplier(requestWithMultiplier("4")))
        .isEqualByComparingTo("4");
  }

  @Test
  void subOneMultiplierClampsToOne() {
    // 0.5 would HALVE slippage; -3 would flip its sign — both must read as unstressed.
    assertThat(BacktestRunner.stressSlippageMultiplier(requestWithMultiplier("0.5")))
        .isEqualByComparingTo("1");
    assertThat(BacktestRunner.stressSlippageMultiplier(requestWithMultiplier("-3")))
        .isEqualByComparingTo("1");
  }

  @Test
  void nonNumericValueReadsAsOne() {
    ObjectNode request = MAPPER.createObjectNode();
    request.putObject("stressOverrides").put("slippageMultiplier", "2");
    assertThat(BacktestRunner.stressSlippageMultiplier(request)).isEqualByComparingTo("1");
  }
}
