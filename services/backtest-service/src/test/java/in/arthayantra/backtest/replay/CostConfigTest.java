package in.arthayantra.backtest.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategyengine.fills.FillSimulator.Fill;
import in.arthayantra.strategyengine.fills.FillSimulator.FillRequest;
import in.arthayantra.strategyengine.fills.InstrumentClass;
import in.arthayantra.strategyengine.fills.LtpSlippageV1;
import in.arthayantra.strategyengine.fills.Side;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * P1-2 / audit B4: the candle path now derives the statutory instrument class from the run's signal
 * series and honours the request-level {@code costs} block, instead of pinning {@code
 * CostConfig.defaults()} (EQUITY DELIVERY) for every run. This pins two things:
 *
 * <ol>
 *   <li><b>Per-class costing demonstration</b> — the SAME sell trade priced through {@link
 *       CostConfig#forClass}(EQUITY) / (FUTURE) / {@link CostConfig#optionDefaults} yields three
 *       DIFFERENT nets, each using its own {@code FeeConstants} statutory stack (the fill math itself
 *       is already exact-to-paisa in the engine's {@code FillVectorTest}; here we prove the
 *       candle-path <em>wiring</em> selects the right stack). The pricing mirrors {@code
 *       ReplayEngine#fill} exactly (FillRequest built from the CostConfig fields).
 *   <li><b>The {@code costs} knob</b> — {@link CostConfig#withOverrides} re-bases the class and applies
 *       brokerage/slippage overrides, and an absent block is byte-identical to the derived default.
 * </ol>
 */
class CostConfigTest {

  // Concrete type (not the FillSimulator port) so the fill call can pass the cost-stress multiplier —
  // exactly as ReplayEngine holds its `fills` field.
  private static final LtpSlippageV1 SIM = new LtpSlippageV1();
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * The keystone: one sell of 50 units @ ₹20000 reference, priced through each class's candle-path
   * cost stack, produces three distinct nets — driven by the per-class {@code FeeConstants} rates.
   */
  @Test
  void sameTradePricedPerClassGivesDifferentNet() {
    Fill equity = price(CostConfig.forClass(InstrumentClass.EQUITY));
    Fill future = price(CostConfig.forClass(InstrumentClass.FUTURE));
    Fill option = price(CostConfig.optionDefaults(50)); // OPTION analog (premium path's own factory)

    // --- STT diverges by class (the headline cost mis-specification B4 flags) ---
    // EQUITY: FeeConstants.STT_EQUITY (line 33) 0.10% on turnover, BOTH sides -> 999500 * 0.001.
    assertThat(equity.costs().stt().toPlainString()).isEqualTo("999.50");
    // FUTURE: FeeConstants.STT_FUTURE_SELL (line 30) 0.02% sell-side only -> 999997.50 * 0.0002.
    assertThat(future.costs().stt().toPlainString()).isEqualTo("200.00");
    // OPTION: FeeConstants.STT_OPTION_SELL (line 27) 0.10% sell-side premium -> 999997.50 * 0.001.
    assertThat(option.costs().stt().toPlainString()).isEqualTo("1000.00");

    // --- brokerage form diverges: equity uncapped %, futures %-min-₹20-cap, option flat ₹20/lot ---
    // FUTURE: 0.03% of ~₹1,000,000 = ~₹300, capped at FeeConstants.FLAT_BROKERAGE_INR (line 23) ₹20.
    assertThat(future.costs().brokerage().toPlainString()).isEqualTo("20.00");
    // EQUITY: the SAME 0.03% is UNCAPPED on the equity path -> materially larger than the ₹20 cap.
    assertThat(equity.costs().brokerage()).isGreaterThan(new BigDecimal("20.00"));
    // OPTION: flat ₹20 per lot (50 units / lot 50 = 1 lot).
    assertThat(option.costs().brokerage().toPlainString()).isEqualTo("20.00");

    // --- exchange txn charge diverges: TXN_EQUITY (50) < ... , TXN_FUTURE (47) lowest, TXN_OPTION (44) highest ---
    assertThat(future.costs().exchangeTxn()).isLessThan(equity.costs().exchangeTxn());
    assertThat(option.costs().exchangeTxn()).isGreaterThan(equity.costs().exchangeTxn());

    // --- three DISTINCT nets: the whole point of P1-2 (futures no longer mis-costed as equity) ---
    assertThat(equity.netValue()).isNotEqualByComparingTo(future.netValue());
    assertThat(equity.netValue()).isNotEqualByComparingTo(option.netValue());
    assertThat(future.netValue()).isNotEqualByComparingTo(option.netValue());
    // A futures run keeps materially MORE of the sale than the old blanket-equity costing would have
    // (0.02% CTT + ₹20-cap brokerage << 0.10% STT + uncapped brokerage).
    assertThat(future.netValue()).isGreaterThan(equity.netValue());
  }

  /** The EQUITY class reproduces {@code defaults()} byte-for-byte — an existing equity run is unchanged. */
  @Test
  void forClassEquityEqualsDefaults() {
    assertThat(CostConfig.forClass(InstrumentClass.EQUITY)).isEqualTo(CostConfig.defaults());
  }

  /** An absent / empty / non-object costs block leaves the derived config byte-identical (parity). */
  @Test
  void absentCostsBlockIsUnchanged() {
    CostConfig base = CostConfig.forClass(InstrumentClass.FUTURE);
    assertThat(base.withOverrides(null)).isEqualTo(base);
    assertThat(base.withOverrides(MAPPER.createObjectNode())).isEqualTo(base); // empty object
    assertThat(base.withOverrides(MAPPER.getNodeFactory().textNode("x"))).isEqualTo(base); // non-object
    assertThat(base.withOverrides(MAPPER.missingNode())).isEqualTo(base); // request.path("costs") miss
  }

  /** A costs.instrumentClass re-bases the whole statutory stack (not a relabelled equity stack). */
  @Test
  void instrumentClassOverrideRebasesTheStack() {
    CostConfig equityBase = CostConfig.forClass(InstrumentClass.EQUITY);
    CostConfig forced = equityBase.withOverrides(node("{\"instrumentClass\":\"FUTURE\"}"));
    assertThat(forced.instrumentClass()).isEqualTo(InstrumentClass.FUTURE);
    // rebased to futureDefaults() (percentage brokerage retained; the FUTURE fill branch caps it),
    // NOT the equity brokerage carried under a FUTURE label.
    assertThat(forced.brokerage()).isEqualTo(CostConfig.futureDefaults().brokerage());
    assertThat(forced.slippageMultiplier()).isEqualByComparingTo(equityBase.slippageMultiplier());
  }

  /** The costs block overrides slippage (ticks XOR bps) and brokerage; the multiplier is untouched. */
  @Test
  void slippageAndBrokerageOverridesApply() {
    CostConfig base = CostConfig.forClass(InstrumentClass.EQUITY);
    CostConfig overridden =
        base.withOverrides(
            node(
                "{\"slippage\":{\"ticks\":3},"
                    + "\"brokerage\":{\"perLotInr\":15,\"pctPerSide\":0.02}}"));
    assertThat(overridden.slippage().ticks()).isEqualTo(3);
    assertThat(overridden.slippage().bps()).isNull();
    assertThat(overridden.brokerage().perLotInr()).isEqualByComparingTo("15");
    assertThat(overridden.brokerage().pctPerSide()).isEqualByComparingTo("0.02");
    // class / tick / lot / multiplier unchanged from the base
    assertThat(overridden.instrumentClass()).isEqualTo(InstrumentClass.EQUITY);
    assertThat(overridden.slippageMultiplier()).isEqualByComparingTo(BigDecimal.ONE);
  }

  @Test
  void bpsSlippageOverrideApplies() {
    CostConfig overridden =
        CostConfig.forClass(InstrumentClass.FUTURE).withOverrides(node("{\"slippage\":{\"bps\":8}}"));
    assertThat(overridden.slippage().bps()).isEqualByComparingTo("8");
    assertThat(overridden.slippage().ticks()).isNull();
  }

  @Test
  void malformedCostsBlockFailsLoud() {
    CostConfig base = CostConfig.defaults();
    assertThatThrownBy(() -> base.withOverrides(node("{\"instrumentClass\":\"BOND\"}")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("EQUITY");
    assertThatThrownBy(
            () -> base.withOverrides(node("{\"slippage\":{\"ticks\":1,\"bps\":2}}")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at most one");
  }

  // ---- helpers ----

  /** Mirrors ReplayEngine#fill exactly: build a FillRequest from the CostConfig, price a SELL. */
  private static Fill price(CostConfig c) {
    return SIM.simulate(
        new FillRequest(
            Side.SELL,
            50,
            c.lotSize(),
            new BigDecimal("20000.00"),
            c.instrumentClass(),
            c.tickSize(),
            null,
            c.slippage(),
            c.brokerage(),
            c.fees()),
        c.slippageMultiplier());
  }

  private static JsonNode node(String json) {
    try {
      return MAPPER.readTree(json);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
