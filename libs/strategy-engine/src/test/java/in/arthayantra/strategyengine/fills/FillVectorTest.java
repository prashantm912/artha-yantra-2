package in.arthayantra.strategyengine.fills;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.arthayantra.strategyengine.fills.FillSimulator.Brokerage;
import in.arthayantra.strategyengine.fills.FillSimulator.Fees;
import in.arthayantra.strategyengine.fills.FillSimulator.Fill;
import in.arthayantra.strategyengine.fills.FillSimulator.FillRequest;
import in.arthayantra.strategyengine.fills.FillSimulator.Slippage;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * The {@code ltp_slippage/v1} fill golden vectors (§D.11). NUMERIC string-compare, fee math exact to
 * the paisa, identical inputs ⇒ identical fills. Covers asset class × slippage form × fee
 * combination, the A9 futures legs, and the decisions-log one-tick-vs-flat-bps options case.
 */
class FillVectorTest {

  private static final FillSimulator SIM = new LtpSlippageV1();
  private static final BigDecimal TICK = new BigDecimal("0.05");
  private static final Brokerage OPT_BROKERAGE = new Brokerage(new BigDecimal("20.00"), null);
  private static final Brokerage FUT_BROKERAGE = new Brokerage(null, new BigDecimal("0.03"));

  // ---- canonical OPTION SELL, fallback slippage — fully pinned exact-to-paisa ----
  @Test
  void optionSellFallbackIsExactToThePaisa() {
    Fill fill =
        SIM.simulate(
            new FillRequest(
                Side.SELL, 50, 50, new BigDecimal("100.00"), InstrumentClass.OPTION,
                TICK, null, Slippage.NONE, OPT_BROKERAGE, Fees.DEFAULTS));

    assertThat(fill.slippageApplied().toPlainString()).isEqualTo("0.05"); // 1-tick options floor
    assertThat(fill.fillPrice().toPlainString()).isEqualTo("99.95");
    assertThat(fill.turnover().toPlainString()).isEqualTo("4997.50");
    assertThat(fill.costs().brokerage().toPlainString()).isEqualTo("20.00");
    assertThat(fill.costs().stt().toPlainString()).isEqualTo("5.00"); // sell-side premium
    assertThat(fill.costs().exchangeTxn().toPlainString()).isEqualTo("1.75");
    assertThat(fill.costs().gst().toPlainString()).isEqualTo("3.92");
    assertThat(fill.costs().stamp().toPlainString()).isEqualTo("0.00"); // stamp is buy-side only
    assertThat(fill.costs().sebi().toPlainString()).isEqualTo("0.00");
    assertThat(fill.costs().total().toPlainString()).isEqualTo("30.67");
    assertThat(fill.netValue().toPlainString()).isEqualTo("4966.83"); // +turnover - costs
  }

  // ---- the decisions-log case: ₹0.05 tick on a ₹10 premium IS 50 bps; flat-bps would be wrong ----
  @Test
  void oneTickOnTenRupeePremiumIsFiftyBps() {
    Fill fill =
        SIM.simulate(
            new FillRequest(
                Side.SELL, 50, 50, new BigDecimal("10.00"), InstrumentClass.OPTION,
                TICK, null, Slippage.NONE, OPT_BROKERAGE, Fees.DEFAULTS));

    assertThat(fill.slippageApplied().toPlainString()).isEqualTo("0.05");
    BigDecimal bps =
        fill.slippageApplied()
            .divide(new BigDecimal("10.00"))
            .multiply(new BigDecimal("10000"));
    assertThat(bps.stripTrailingZeros()).isEqualByComparingTo("50");
    // a flat 10 bps would be 0.01 — sub-tick, quantitatively wrong
    BigDecimal flatTenBps = new BigDecimal("10.00").multiply(new BigDecimal("0.001"));
    assertThat(fill.slippageApplied()).isGreaterThan(flatTenBps);
  }

  // ---- canonical FUTURE SELL, 2-tick slippage — A9 [FP-7] legs pinned exact-to-paisa ----
  @Test
  void futureSellIsExactToThePaisaWithSellSideStt() {
    Fill fill =
        SIM.simulate(
            new FillRequest(
                Side.SELL, 50, 50, new BigDecimal("20000.00"), InstrumentClass.FUTURE,
                TICK, null, Slippage.ticks(2), FUT_BROKERAGE, Fees.DEFAULTS));

    assertThat(fill.slippageApplied().toPlainString()).isEqualTo("0.10");
    assertThat(fill.fillPrice().toPlainString()).isEqualTo("19999.90");
    assertThat(fill.turnover().toPlainString()).isEqualTo("999995.00");
    assertThat(fill.costs().brokerage().toPlainString()).isEqualTo("20.00"); // min-of pct/flat
    assertThat(fill.costs().stt().toPlainString()).isEqualTo("200.00"); // sell-side futures STT
    assertThat(fill.costs().exchangeTxn().toPlainString()).isEqualTo("17.30");
    assertThat(fill.costs().sebi().toPlainString()).isEqualTo("1.00");
    assertThat(fill.costs().stamp().toPlainString()).isEqualTo("0.00");
    assertThat(fill.costs().gst().toPlainString()).isEqualTo("6.89");
    assertThat(fill.costs().total().toPlainString()).isEqualTo("245.19");
    assertThat(fill.netValue().toPlainString()).isEqualTo("999749.81");
  }

  @Test
  void futureBuyHasNoSttButPaysBuySideStamp() {
    Fill fill =
        SIM.simulate(
            new FillRequest(
                Side.BUY, 50, 50, new BigDecimal("20000.00"), InstrumentClass.FUTURE,
                TICK, null, Slippage.ticks(2), FUT_BROKERAGE, Fees.DEFAULTS));

    assertThat(fill.costs().stt().toPlainString()).isEqualTo("0.00");
    assertThat(fill.costs().stamp()).isGreaterThan(BigDecimal.ZERO); // buy-side stamp
    assertThat(fill.costs().brokerage().toPlainString()).isEqualTo("20.00");
  }

  @Test
  void optionBuyHasNoSttButPaysBuySideStamp() {
    Fill fill =
        SIM.simulate(
            new FillRequest(
                Side.BUY, 50, 50, new BigDecimal("100.00"), InstrumentClass.OPTION,
                TICK, null, Slippage.NONE, OPT_BROKERAGE, Fees.DEFAULTS));

    assertThat(fill.fillPrice().toPlainString()).isEqualTo("100.05");
    assertThat(fill.costs().stt().toPlainString()).isEqualTo("0.00");
    assertThat(fill.costs().stamp()).isGreaterThan(BigDecimal.ZERO);
  }

  @Test
  void equitySttAppliesOnBothSides() {
    FillRequest buy =
        new FillRequest(
            Side.BUY, 100, 1, new BigDecimal("500.00"), InstrumentClass.EQUITY,
            TICK, null, Slippage.NONE, new Brokerage(null, new BigDecimal("0.03")), Fees.DEFAULTS);
    FillRequest sell =
        new FillRequest(
            Side.SELL, 100, 1, new BigDecimal("500.00"), InstrumentClass.EQUITY,
            TICK, null, Slippage.NONE, new Brokerage(null, new BigDecimal("0.03")), Fees.DEFAULTS);

    assertThat(SIM.simulate(buy).costs().stt()).isGreaterThan(BigDecimal.ZERO);
    assertThat(SIM.simulate(sell).costs().stt()).isGreaterThan(BigDecimal.ZERO);
    assertThat(SIM.simulate(buy).costs().stamp()).isGreaterThan(BigDecimal.ZERO); // buy stamp
    assertThat(SIM.simulate(sell).costs().stamp().toPlainString()).isEqualTo("0.00"); // no sell stamp
  }

  // ---- slippage forms ----
  @Test
  void slippageFormsResolveCorrectly() {
    assertThat(slip(Slippage.ticks(3), new BigDecimal("500.00"), InstrumentClass.EQUITY, null))
        .isEqualTo("0.15"); // 3 ticks
    assertThat(slip(Slippage.bps(new BigDecimal("10")), new BigDecimal("500.00"), InstrumentClass.EQUITY, null))
        .isEqualTo("0.50"); // 10 bps of 500
    assertThat(slip(Slippage.NONE, new BigDecimal("500.00"), InstrumentClass.EQUITY, null))
        .isEqualTo("0.25"); // equity 5 bps fallback
    assertThat(slip(Slippage.NONE, new BigDecimal("10.00"), InstrumentClass.OPTION, new BigDecimal("0.20")))
        .isEqualTo("0.10"); // max(1 tick 0.05, half-spread 0.10)
    assertThat(slip(Slippage.NONE, new BigDecimal("10.00"), InstrumentClass.OPTION, null))
        .isEqualTo("0.05"); // degrades to 1 tick without a quote
    assertThat(slip(Slippage.NONE, new BigDecimal("20000.00"), InstrumentClass.FUTURE, null))
        .isEqualTo("0.05"); // futures 1-tick fallback
  }

  @Test
  void identicalInputsProduceIdenticalFills() {
    FillRequest req =
        new FillRequest(
            Side.SELL, 50, 50, new BigDecimal("123.45"), InstrumentClass.OPTION,
            TICK, null, Slippage.bps(new BigDecimal("7")), OPT_BROKERAGE, Fees.DEFAULTS);
    assertThat(SIM.simulate(req)).isEqualTo(SIM.simulate(req));
  }

  @Test
  void slippageRejectsBothTicksAndBps() {
    assertThatThrownBy(() -> new Slippage(1, BigDecimal.ONE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static String slip(
      Slippage slippage, BigDecimal ref, InstrumentClass cls, BigDecimal spread) {
    Brokerage brokerage =
        cls == InstrumentClass.OPTION ? OPT_BROKERAGE : new Brokerage(null, new BigDecimal("0.03"));
    Fill fill =
        SIM.simulate(
            new FillRequest(Side.BUY, 50, 50, ref, cls, TICK, spread, slippage, brokerage, Fees.DEFAULTS));
    return fill.slippageApplied().toPlainString();
  }
}
