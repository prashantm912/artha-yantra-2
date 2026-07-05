package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.arthayantra.strategyengine.fills.InstrumentClass;
import in.arthayantra.strategysignal.paper.InstrumentMetaClient.InstrumentMeta;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** §3.7 hero-zero profit-funded sizing: deploy ~10% of realised profit, floored to the ₹2.5k minimum. */
class PaperEmissionGuardTest {

  private static BigDecimal bd(String s) {
    return new BigDecimal(s);
  }

  @Test
  void heroZeroDeployBudgetUsesTenPctOfProfitsWhenAmpleElseTheFloor() {
    // mode a: 10% of ample profit (₹100k -> ₹10k) dominates the ₹2.5k floor.
    assertThat(PaperEmissionGuard.heroZeroDeployBudget(bd("100000"))).isEqualByComparingTo("10000");
    // at the crossover (10% of 25k = 2500) the floor wins on the tie (> not >=).
    assertThat(PaperEmissionGuard.heroZeroDeployBudget(bd("25000"))).isEqualByComparingTo("2500");
    // mode b: thin profit (10% of 10k = 1000 < 2500) -> the ₹2.5k floor.
    assertThat(PaperEmissionGuard.heroZeroDeployBudget(bd("10000"))).isEqualByComparingTo("2500");
    // negative / zero / null realised P&L -> the floor (never below ₹2.5k, never funds off capital loss).
    assertThat(PaperEmissionGuard.heroZeroDeployBudget(bd("-5000"))).isEqualByComparingTo("2500");
    assertThat(PaperEmissionGuard.heroZeroDeployBudget(BigDecimal.ZERO)).isEqualByComparingTo("2500");
    assertThat(PaperEmissionGuard.heroZeroDeployBudget(null)).isEqualByComparingTo("2500");
  }

  @Test
  void heroZeroSuggestedQtySizesFromProfitsInPremiumTermsLotRounded() {
    PaperAccountService account = mock(PaperAccountService.class);
    InstrumentMetaClient instruments = mock(InstrumentMetaClient.class);
    PaperEmissionGuard guard =
        new PaperEmissionGuard(
            mock(RiskService.class), account, instruments, mock(ScalperAccountModel.class));
    when(instruments.meta(any(), any()))
        .thenReturn(new InstrumentMeta(InstrumentClass.OPTION, bd("0.05"), 75L));

    // ample profit ₹150k -> budget ₹15k; premium 20 × lot 75 = ₹1,500/lot -> 10 lots -> 750 units.
    when(account.realisedProfit("scalper")).thenReturn(bd("150000"));
    assertThat(guard.heroZeroSuggestedQty("NFO", "NIFTY25000CE", bd("20"))).isEqualByComparingTo("750");
    // thin profit -> the ₹2.5k floor; ₹2,500 / ₹1,500-per-lot = 1 lot -> 75 units.
    when(account.realisedProfit("scalper")).thenReturn(BigDecimal.ZERO);
    assertThat(guard.heroZeroSuggestedQty("NFO", "NIFTY25000CE", bd("20"))).isEqualByComparingTo("75");
    // a premium the floor cannot fund a full lot of (100 × 75 = 7,500 > 2,500) -> still ONE lot (fired entry).
    assertThat(guard.heroZeroSuggestedQty("NFO", "NIFTY25000CE", bd("100"))).isEqualByComparingTo("75");
    // null / non-positive premium -> null (the caller keeps the ordinary advisory qty).
    assertThat(guard.heroZeroSuggestedQty("NFO", "NIFTY25000CE", null)).isNull();
    assertThat(guard.heroZeroSuggestedQty("NFO", "NIFTY25000CE", BigDecimal.ZERO)).isNull();
  }
}
