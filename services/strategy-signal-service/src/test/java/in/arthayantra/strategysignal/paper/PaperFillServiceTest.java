package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategyengine.fills.FillSimulator.Fill;
import in.arthayantra.strategyengine.fills.InstrumentClass;
import in.arthayantra.strategyengine.fills.Side;
import in.arthayantra.strategysignal.paper.InstrumentMetaClient.InstrumentMeta;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * The load-bearing parity check (Phase 43 PASS): a paper fill equals the backtest fill for the same
 * scenario TO THE PAISA, because both link the same {@code ltp_slippage/v1} in the engine JAR. The
 * expected values are the Stage-D {@code FillVectorTest} OPTION-SELL golden vector.
 */
class PaperFillServiceTest {

  private final PaperFillService fills = new PaperFillService();

  @Test
  void paperOptionSellFillEqualsTheBacktestGoldenVectorToThePaisa() {
    InstrumentMeta option = new InstrumentMeta(InstrumentClass.OPTION, new BigDecimal("0.05"), 50);
    Fill fill = fills.fill(Side.SELL, 50, new BigDecimal("100.00"), option);

    assertThat(fill.slippageApplied().toPlainString()).isEqualTo("0.05"); // 1-tick options floor
    assertThat(fill.fillPrice().toPlainString()).isEqualTo("99.95");
    assertThat(fill.turnover().toPlainString()).isEqualTo("4997.50");
    assertThat(fill.costs().brokerage().toPlainString()).isEqualTo("20.00");
    assertThat(fill.costs().stt().toPlainString()).isEqualTo("5.00");
    assertThat(fill.costs().total().toPlainString()).isEqualTo("30.67");
    assertThat(fill.netValue().toPlainString()).isEqualTo("4966.83");
  }

  @Test
  void simulatorIdComesFromTheEngineJar() {
    assertThat(fills.simulatorId()).isEqualTo("ltp_slippage/v1");
  }

  @Test
  void costBasisReappliesNoSlippageSoTurnoverIsTheAvgEntryNotional() {
    InstrumentMeta option = new InstrumentMeta(InstrumentClass.OPTION, new BigDecimal("0.05"), 50);
    // entry BUY at an avg price of 99.95: cost basis re-prices at exactly that, no extra slippage
    Fill basis = fills.costBasis(Side.BUY, 50, new BigDecimal("99.95"), option);
    assertThat(basis.slippageApplied().toPlainString()).isEqualTo("0.00");
    assertThat(basis.fillPrice().toPlainString()).isEqualTo("99.95");
    assertThat(basis.turnover().toPlainString()).isEqualTo("4997.50");
  }
}
