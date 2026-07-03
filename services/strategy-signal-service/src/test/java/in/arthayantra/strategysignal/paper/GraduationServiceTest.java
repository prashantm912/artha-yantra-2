package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategysignal.paper.GraduationService.Metrics;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure-unit coverage of the graduation math (no DB): profit-factor, expectancy, win-rate and the
 * peak-to-trough max-drawdown walk over a CLOSED-trade realized-P&amp;L series.
 */
class GraduationServiceTest {

  private static final BigDecimal BASE = new BigDecimal("100000");

  private static BigDecimal bd(String v) {
    return new BigDecimal(v);
  }

  @Test
  void aggregatesWinsLossesNetAndWinRate() {
    Metrics m = GraduationService.metrics(List.of(bd("100"), bd("-40"), bd("60"), bd("-20")), BASE);
    assertThat(m.trades()).isEqualTo(4);
    assertThat(m.wins()).isEqualTo(2);
    assertThat(m.losses()).isEqualTo(2);
    assertThat(m.netRealized()).isEqualByComparingTo("100"); // 100-40+60-20
    assertThat(m.winRate()).isEqualByComparingTo("0.5");
    // profit factor = (100+60) / |(-40-20)| = 160/60 = 2.6667
    assertThat(m.profitFactor()).isEqualByComparingTo("2.6667");
    // expectancy = 100 / 4 = 25
    assertThat(m.expectancy()).isEqualByComparingTo("25");
  }

  @Test
  void profitFactorIsNullWhenNoLosses() {
    Metrics m = GraduationService.metrics(List.of(bd("10"), bd("20"), bd("0")), BASE);
    assertThat(m.losses()).isZero();
    assertThat(m.profitFactor()).isNull();
    assertThat(m.maxDrawdownPct()).isEqualByComparingTo("0"); // curve only rises (a flat 0 too)
  }

  @Test
  void maxDrawdownIsThePeakToTroughDropAsPctOfPeakEquity() {
    // equity from 100000: +2000=102000 (peak), -5000=97000, -1000=96000 (trough), +3000=99000.
    // peak-to-trough drop = 102000-96000 = 6000, as % of peak 102000 = 5.8824%.
    Metrics m = GraduationService.metrics(List.of(bd("2000"), bd("-5000"), bd("-1000"), bd("3000")), BASE);
    assertThat(m.maxDrawdownPct()).isEqualByComparingTo("5.8824");
  }

  @Test
  void emptySeriesIsZeroTradesNullProfitFactor() {
    Metrics m = GraduationService.metrics(List.of(), BASE);
    assertThat(m.trades()).isZero();
    assertThat(m.netRealized()).isEqualByComparingTo("0");
    assertThat(m.winRate()).isNull();
    assertThat(m.profitFactor()).isNull();
    assertThat(m.expectancy()).isEqualByComparingTo("0");
    assertThat(m.maxDrawdownPct()).isEqualByComparingTo("0");
  }
}
