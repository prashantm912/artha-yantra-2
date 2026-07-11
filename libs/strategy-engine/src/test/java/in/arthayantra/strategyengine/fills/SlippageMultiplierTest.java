package in.arthayantra.strategyengine.fills;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategyengine.fills.FillSimulator.Brokerage;
import in.arthayantra.strategyengine.fills.FillSimulator.Fees;
import in.arthayantra.strategyengine.fills.FillSimulator.Fill;
import in.arthayantra.strategyengine.fills.FillSimulator.FillRequest;
import in.arthayantra.strategyengine.fills.FillSimulator.Slippage;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * The EVO §3.2.5 cost-stress overload {@code simulate(req, slippageMultiplier)}: the effective
 * slippage (explicit ticks/bps OR the per-class fallback) scales by the multiplier, and a multiplier
 * of {@code 1}/{@code null} is byte-identical to the no-arg {@link FillSimulator#simulate}. Pins the
 * exact-to-paisa stressed numbers on the same canonical OPTION-SELL fixture the golden fill vectors
 * use ({@code optionSellFallbackIsExactToThePaisa}) so a stressed fill provably costs more.
 */
class SlippageMultiplierTest {

  private static final LtpSlippageV1 SIM = new LtpSlippageV1();
  private static final BigDecimal TICK = new BigDecimal("0.05");
  private static final Brokerage OPT_BROKERAGE = new Brokerage(new BigDecimal("20.00"), null);

  private static FillRequest optionSell(BigDecimal ref) {
    return new FillRequest(
        Side.SELL, 50, 50, ref, InstrumentClass.OPTION, TICK, null, Slippage.NONE, OPT_BROKERAGE,
        Fees.DEFAULTS);
  }

  @Test
  void multiplierOfOneIsByteIdenticalToTheUnstressedFill() {
    // The parity guarantee: an absent stressOverrides field ⇒ multiplier 1 ⇒ the exact golden fill.
    FillRequest req = optionSell(new BigDecimal("100.00"));
    assertThat(SIM.simulate(req, BigDecimal.ONE)).isEqualTo(SIM.simulate(req));
    assertThat(SIM.simulate(req, null)).isEqualTo(SIM.simulate(req));
  }

  @Test
  void doubledSlippageWidensTheFillExactToThePaisa() {
    // Unstressed fixture (pinned by FillVectorTest): slippage 0.05, fillPrice 99.95, turnover
    // 4997.50, total cost 30.67, netValue 4966.83. At 2× the 1-tick fallback the slippage is 0.10.
    Fill fill = SIM.simulate(optionSell(new BigDecimal("100.00")), new BigDecimal("2"));

    assertThat(fill.slippageApplied().toPlainString()).isEqualTo("0.10"); // 2 × the 0.05 fallback
    assertThat(fill.fillPrice().toPlainString()).isEqualTo("99.90"); // SELL fills lower
    assertThat(fill.turnover().toPlainString()).isEqualTo("4995.00");
    // The statutory legs round identically to the unstressed case (total 30.67), so the whole 2.50
    // slippage delta (0.05 × 50 units) flows straight through to a WORSE sell proceed.
    assertThat(fill.costs().total().toPlainString()).isEqualTo("30.67");
    assertThat(fill.netValue().toPlainString()).isEqualTo("4964.33"); // 4966.83 − 2.50
    assertThat(fill.netValue()).isLessThan(SIM.simulate(optionSell(new BigDecimal("100.00"))).netValue());
  }

  @Test
  void quadrupledSlippageScalesFurther() {
    Fill fill = SIM.simulate(optionSell(new BigDecimal("100.00")), new BigDecimal("4"));

    assertThat(fill.slippageApplied().toPlainString()).isEqualTo("0.20"); // 4 × 0.05
    assertThat(fill.fillPrice().toPlainString()).isEqualTo("99.80");
    assertThat(fill.turnover().toPlainString()).isEqualTo("4990.00");
    // Monotonic: 4× fills strictly worse than 2×.
    Fill doubled = SIM.simulate(optionSell(new BigDecimal("100.00")), new BigDecimal("2"));
    assertThat(fill.netValue()).isLessThan(doubled.netValue());
  }

  @Test
  void multiplierScalesExplicitBpsAndTickSlippageToo() {
    // Explicit bps (equity, 10 bps of 500 = 0.50) → ×2 = 1.00.
    FillRequest bps =
        new FillRequest(
            Side.BUY, 50, 1, new BigDecimal("500.00"), InstrumentClass.EQUITY, TICK, null,
            Slippage.bps(new BigDecimal("10")), new Brokerage(null, new BigDecimal("0.03")),
            Fees.DEFAULTS);
    assertThat(SIM.simulate(bps, new BigDecimal("2")).slippageApplied().toPlainString())
        .isEqualTo("1.00");

    // Explicit ticks (future, 2 ticks = 0.10) → ×3 = 0.30.
    FillRequest ticks =
        new FillRequest(
            Side.SELL, 50, 50, new BigDecimal("20000.00"), InstrumentClass.FUTURE, TICK, null,
            Slippage.ticks(2), new Brokerage(null, new BigDecimal("0.03")), Fees.DEFAULTS);
    assertThat(SIM.simulate(ticks, new BigDecimal("3")).slippageApplied().toPlainString())
        .isEqualTo("0.30");
  }
}
