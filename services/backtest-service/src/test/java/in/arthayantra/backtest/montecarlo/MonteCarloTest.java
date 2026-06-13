package in.arthayantra.backtest.montecarlo;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * §D.16 Monte Carlo determinism: same {@code (trades, mcSeed, n)} ⇒ byte-identical summary; a
 * different seed produces a different summary (the recompute-and-replace path); the
 * "insufficient sample" flag tracks {@code min_trades}. Statistical correctness of the bands is not
 * hand-asserted (it is a bootstrap estimate) — determinism and reproducibility are the contract.
 */
class MonteCarloTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final MonteCarlo mc = new MonteCarlo(mapper);

  private static final List<BigDecimal> PNLS =
      List.of(
          new BigDecimal("100.00"),
          new BigDecimal("-50.00"),
          new BigDecimal("200.00"),
          new BigDecimal("-30.00"),
          new BigDecimal("80.00"));

  @Test
  void sameSeedProducesByteIdenticalSummary() throws Exception {
    ObjectNode a = mc.run(PNLS, new BigDecimal("10000.00"), 1.0, 200, 42L, 30);
    ObjectNode b = mc.run(PNLS, new BigDecimal("10000.00"), 1.0, 200, 42L, 30);
    assertThat(mapper.writeValueAsString(a)).isEqualTo(mapper.writeValueAsString(b));
  }

  @Test
  void differentSeedProducesDifferentSummary() throws Exception {
    ObjectNode a = mc.run(PNLS, new BigDecimal("10000.00"), 1.0, 200, 42L, 30);
    ObjectNode b = mc.run(PNLS, new BigDecimal("10000.00"), 1.0, 200, 43L, 30);
    assertThat(mapper.writeValueAsString(a)).isNotEqualTo(mapper.writeValueAsString(b));
    assertThat(a.get("mcSeed").asLong()).isEqualTo(42L);
    assertThat(b.get("mcSeed").asLong()).isEqualTo(43L);
  }

  @Test
  void summaryCarriesTheExpectedShape() {
    ObjectNode s = mc.run(PNLS, new BigDecimal("10000.00"), 1.0, 100, 7L, 30);
    assertThat(s.path("n").asInt()).isEqualTo(100);
    assertThat(s.path("trades").asInt()).isEqualTo(5);
    assertThat(s.path("insufficientSample").asBoolean()).isTrue(); // 5 < 30
    assertThat(s.path("equityBands").path("p50").isArray()).isTrue();
    assertThat(s.path("drawdownDistribution").has("p95")).isTrue();
    assertThat(s.path("riskOfRuin").isTextual()).isTrue();
    assertThat(s.path("ci").path("cagr").has("p5")).isTrue();
    assertThat(s.path("ci").path("sharpe").has("p95")).isTrue();
    // equity band always anchors at the starting equity (step 0).
    assertThat(s.path("equityBands").path("p50").get(0).asText()).isEqualTo("10000.00");
  }

  @Test
  void aboveMinTradesClearsTheInsufficientFlag() {
    List<BigDecimal> many =
        IntStream.range(0, 40).mapToObj(i -> new BigDecimal("10.00")).toList();
    ObjectNode s = mc.run(many, new BigDecimal("10000.00"), 1.0, 50, 1L, 30);
    assertThat(s.path("insufficientSample").asBoolean()).isFalse();
  }
}
