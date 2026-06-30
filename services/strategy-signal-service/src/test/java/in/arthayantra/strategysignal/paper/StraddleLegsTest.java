package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategysignal.paper.StraddleLegs.Pair;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StraddleLegsTest {

  private static final ObjectMapper M = new ObjectMapper();

  private static JsonNode node(String json) {
    try {
      return M.readTree(json);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static final String NEUTRAL_STRADDLE =
      """
      {"side":"NEUTRAL","underlying":"NIFTY 50","expiry":"2026-07-07",
       "legs":[
         {"exchange":"NFO","tradingsymbol":"NIFTY2570723900CE","side":"BUY","option_type":"CE","strike":23900,"option_ltp":100.0},
         {"exchange":"NFO","tradingsymbol":"NIFTY2570723900PE","side":"BUY","option_type":"PE","strike":23900,"option_ltp":100.0}]}
      """;

  @Test
  void parsesTheCePeLegsOfaNeutralStraddle() {
    Optional<Pair> p = StraddleLegs.parse(node(NEUTRAL_STRADDLE));
    assertThat(p).isPresent();
    assertThat(p.get().ce().tradingsymbol()).isEqualTo("NIFTY2570723900CE");
    assertThat(p.get().ce().optionType()).isEqualTo("CE");
    assertThat(p.get().pe().tradingsymbol()).isEqualTo("NIFTY2570723900PE");
    assertThat(p.get().pe().ltp()).isEqualByComparingTo("100.0");
  }

  @Test
  void directionalDetailIsNotaStraddle() {
    assertThat(StraddleLegs.parse(node("{\"side\":\"CE\",\"tradeable\":\"NIFTY...CE\"}"))).isEmpty();
  }

  @Test
  void neutralWithoutExactlyTwoLegsIsEmpty() {
    assertThat(StraddleLegs.parse(node("{\"side\":\"NEUTRAL\",\"legs\":[{\"option_type\":\"CE\"}]}"))).isEmpty();
  }

  @Test
  void absentOrEmptyDetailIsEmpty() {
    assertThat(StraddleLegs.parse(null)).isEmpty();
    assertThat(StraddleLegs.parse(M.nullNode())).isEmpty();
    assertThat(StraddleLegs.parse(M.createObjectNode())).isEmpty();
  }

  @Test
  void combinedQtySplitsTheBudgetAcrossBothLegs() {
    // suggestedQty sized against the CE alone (budget / (100 × lot)); combined spends the same budget:
    // 20 × 100 / (100+100) = 10 lots each → total (100+100)×10 = 2000 = 20×100 (= the budget).
    assertThat(StraddleLegs.combinedQty(20, new BigDecimal("100"), new BigDecimal("100"))).isEqualTo(10);
  }

  @Test
  void combinedQtyHonoursAsymmetricPremiums() {
    // 20 × 120 / (120+80) = 12 → cost (120+80)×12 = 2400 = 20×120 (budget). Stays budget-faithful.
    assertThat(StraddleLegs.combinedQty(20, new BigDecimal("120"), new BigDecimal("80"))).isEqualTo(12);
  }

  @Test
  void combinedQtyIsZeroWhenAPremiumIsMissingOrNonPositive() {
    assertThat(StraddleLegs.combinedQty(20, null, new BigDecimal("100"))).isZero();
    assertThat(StraddleLegs.combinedQty(20, BigDecimal.ZERO, new BigDecimal("100"))).isZero();
    assertThat(StraddleLegs.combinedQty(0, new BigDecimal("100"), new BigDecimal("100"))).isZero();
  }

  @Test
  void combinedQtyFloorsButNeverBelowOne() {
    // 1 × 100 / 200 = 0.5 → floor 0 → min 1 (a viable single-lot straddle).
    assertThat(StraddleLegs.combinedQty(1, new BigDecimal("100"), new BigDecimal("100"))).isEqualTo(1);
  }
}
