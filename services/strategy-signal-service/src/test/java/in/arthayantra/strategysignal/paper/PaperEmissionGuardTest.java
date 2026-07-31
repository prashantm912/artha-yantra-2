package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;

import in.arthayantra.strategyengine.fills.InstrumentClass;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategysignal.paper.InstrumentMetaClient.InstrumentMeta;
import in.arthayantra.strategysignal.paper.PaperPositionRepository.PositionRow;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

/**
 * §3.7 hero-zero profit-funded sizing (deploy ~10% of realised profit, floored to the ₹2.5k minimum)
 * and the F2 Manas §3.4.3 open-risk read the pyramiding gate consults.
 */
class PaperEmissionGuardTest {

  private static BigDecimal bd(String s) {
    return new BigDecimal(s);
  }

  @Test
  void openRiskInrSumsPerPositionRiskAndTreatsTrailedOrStoplessPositionsAsZero() {
    // Position A: 100 qty, entry 200, stop 190 -> risk 100 × 10 = ₹1,000.
    PositionRow a = position(100, "200", "190");
    // Position B: 50 qty, entry 300, stop 320 (trailed ABOVE entry) -> open risk 0 (§3.5.B).
    PositionRow b = position(50, "300", "320");
    // Position C: 10 qty, entry 100, NO stop -> contributes 0 (no defined risk to sum).
    PositionRow c = position(10, "100", null);
    assertThat(PaperEmissionGuard.openRiskInr(List.of(a, b, c))).isEqualByComparingTo("1000");
    assertThat(PaperEmissionGuard.openRiskInr(List.of())).isEqualByComparingTo("0");
  }

  private static PositionRow position(long qty, String avgEntry, String stop) {
    return new PositionRow(
        1L, "NSE", "TESTCO", "BUY", qty, bd(avgEntry), BigDecimal.ZERO, "OPEN",
        null, null, null, stop == null ? null : bd(stop), null, "manas-arora");
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

  private static final in.arthayantra.strategyengine.config.StrategyDefinition.SizingSpec UNCAPPED =
      new in.arthayantra.strategyengine.config.StrategyDefinition.SizingSpec(
          "premium_budget", java.util.Map.of("budget_inr", bd("2000")));

  private static in.arthayantra.strategyengine.config.StrategyDefinition.SizingSpec cappedAt(int lots) {
    return new in.arthayantra.strategyengine.config.StrategyDefinition.SizingSpec(
        "premium_budget", java.util.Map.of("budget_inr", bd("2000"), "max_lots", lots));
  }

  /**
   * {@code max_lots} MUST bind on the hero-zero path (cross-vendor review, #1075 Critical 1).
   *
   * <p>This quantity OVERRIDES the ordinary {@code suggestedQty}, so a cap enforced only inside
   * {@code PositionSizer} left the hero-zero family completely uncapped: the YAML declared
   * {@code max_lots: 5} and live deployed whatever the profit pot afforded, while the backtest
   * replay capped at 5. The change that was supposed to CLOSE a live-vs-replay sizing divergence
   * opened a new one for exactly the three strategies whose premium is cheapest.
   */
  /**
   * Hero-Zero must honour {@code min_premium_inr} as well as {@code max_lots} (cross-vendor review,
   * #1084 Critical).
   *
   * <p>The SAME defect as the lot-cap bypass, repeated one param later: this quantity OVERRIDES the
   * ordinary sized one, so a floor enforced only inside {@code PositionSizer.size} left the
   * hero-zero family unfloored. The reviewer's own numbers — floor ₹10, premium ₹5, lot 75, cap 5 —
   * produced <b>375 live units against ZERO replay units</b>, because
   * {@code OptionsPremiumReplay} skips a sub-floor entry outright.
   *
   * <p>Returns null, not 0: the caller then keeps its ordinary advisory quantity, which the floor
   * has already zeroed. Hero-Zero must not resurrect a trade the sizer refused.
   */
  @Test
  void heroZeroSuggestedQtyHonoursThePremiumFloor() {
    PaperAccountService account = mock(PaperAccountService.class);
    InstrumentMetaClient instruments = mock(InstrumentMetaClient.class);
    PaperEmissionGuard guard =
        new PaperEmissionGuard(
            mock(RiskService.class), account, instruments, mock(ScalperAccountModel.class),
            mock(PaperPositionRepository.class), mock(PaperOrderRejectionRecorder.class));
    when(instruments.meta(any(), any()))
        .thenReturn(new InstrumentMeta(InstrumentClass.OPTION, bd("0.05"), 75L));
    when(account.realisedProfit("scalper")).thenReturn(bd("150000"));

    var floored =
        new in.arthayantra.strategyengine.config.StrategyDefinition.SizingSpec(
            "premium_budget",
            java.util.Map.of("budget_inr", bd("2000"), "min_premium_inr", bd("10"), "max_lots", 5));

    assertThat(guard.heroZeroSuggestedQty(floored, "NFO", "NIFTY25000CE", bd("5")))
        .as("₹5 premium under a ₹10 floor — replay SKIPS this entry, so live must not size it")
        .isNull();
    assertThat(guard.heroZeroSuggestedQty(floored, "NFO", "NIFTY25000CE", bd("10")))
        .as("EXACTLY at the floor is above it — strict-below, same as the ordinary sizer")
        .isNotNull();
    assertThat(guard.heroZeroSuggestedQty(floored, "NFO", "NIFTY25000CE", bd("20")))
        .as("comfortably above the floor — still capped at 5 lots")
        .isEqualByComparingTo("375");
    assertThat(guard.heroZeroSuggestedQty(UNCAPPED, "NFO", "NIFTY25000CE", bd("5")))
        .as("no floor declared ⇒ unchanged, the strict no-op")
        .isNotNull();
  }

  @Test
  void heroZeroSuggestedQtyHonoursTheDeclaredLotCap() {
    PaperAccountService account = mock(PaperAccountService.class);
    InstrumentMetaClient instruments = mock(InstrumentMetaClient.class);
    PaperEmissionGuard guard =
        new PaperEmissionGuard(
            mock(RiskService.class), account, instruments, mock(ScalperAccountModel.class),
            mock(PaperPositionRepository.class), mock(PaperOrderRejectionRecorder.class));
    when(instruments.meta(any(), any()))
        .thenReturn(new InstrumentMeta(InstrumentClass.OPTION, bd("0.05"), 75L));
    // ₹150k profit -> ₹15k budget; premium 20 × lot 75 = ₹1,500/lot -> 10 affordable lots.
    when(account.realisedProfit("scalper")).thenReturn(bd("150000"));

    assertThat(guard.heroZeroSuggestedQty(UNCAPPED, "NFO", "NIFTY25000CE", bd("20")))
        .as("no cap declared -> unchanged, so existing hero-zero configs do not move")
        .isEqualByComparingTo("750");
    assertThat(guard.heroZeroSuggestedQty(cappedAt(5), "NFO", "NIFTY25000CE", bd("20")))
        .as("10 affordable lots capped to 5 -> 375 units")
        .isEqualByComparingTo("375");
    assertThat(guard.heroZeroSuggestedQty(cappedAt(20), "NFO", "NIFTY25000CE", bd("20")))
        .as("a cap above the affordable count never INFLATES the deploy")
        .isEqualByComparingTo("750");
    // The floor still wins under a cap: a fired entry deploys at least one lot.
    when(account.realisedProfit("scalper")).thenReturn(BigDecimal.ZERO);
    assertThat(guard.heroZeroSuggestedQty(cappedAt(5), "NFO", "NIFTY25000CE", bd("100")))
        .isEqualByComparingTo("75");
  }

  @Test
  void heroZeroSuggestedQtySizesFromProfitsInPremiumTermsLotRounded() {
    PaperAccountService account = mock(PaperAccountService.class);
    InstrumentMetaClient instruments = mock(InstrumentMetaClient.class);
    PaperEmissionGuard guard =
        new PaperEmissionGuard(
            mock(RiskService.class), account, instruments, mock(ScalperAccountModel.class),
            mock(PaperPositionRepository.class), mock(PaperOrderRejectionRecorder.class));
    when(instruments.meta(any(), any()))
        .thenReturn(new InstrumentMeta(InstrumentClass.OPTION, bd("0.05"), 75L));

    // ample profit ₹150k -> budget ₹15k; premium 20 × lot 75 = ₹1,500/lot -> 10 lots -> 750 units.
    when(account.realisedProfit("scalper")).thenReturn(bd("150000"));
    assertThat(guard.heroZeroSuggestedQty(UNCAPPED, "NFO", "NIFTY25000CE", bd("20"))).isEqualByComparingTo("750");
    // thin profit -> the ₹2.5k floor; ₹2,500 / ₹1,500-per-lot = 1 lot -> 75 units.
    when(account.realisedProfit("scalper")).thenReturn(BigDecimal.ZERO);
    assertThat(guard.heroZeroSuggestedQty(UNCAPPED, "NFO", "NIFTY25000CE", bd("20"))).isEqualByComparingTo("75");
    // a premium the floor cannot fund a full lot of (100 × 75 = 7,500 > 2,500) -> still ONE lot (fired entry).
    assertThat(guard.heroZeroSuggestedQty(UNCAPPED, "NFO", "NIFTY25000CE", bd("100"))).isEqualByComparingTo("75");
    // null / non-positive premium -> null (the caller keeps the ordinary advisory qty).
    assertThat(guard.heroZeroSuggestedQty(UNCAPPED, "NFO", "NIFTY25000CE", null)).isNull();
    assertThat(guard.heroZeroSuggestedQty(UNCAPPED, "NFO", "NIFTY25000CE", BigDecimal.ZERO)).isNull();
  }

  @Test
  void unaffordableOptionSizeIsZeroAndRecordsItsDurableRejectionDetails() {
    InstrumentMetaClient instruments = mock(InstrumentMetaClient.class);
    PaperOrderRejectionRecorder rejections = mock(PaperOrderRejectionRecorder.class);
    when(instruments.meta("BFO", "SENSEX26JUL76300CE"))
        .thenReturn(new InstrumentMeta(InstrumentClass.OPTION, bd("0.05"), 20L));
    PaperEmissionGuard guard =
        new PaperEmissionGuard(
            mock(RiskService.class), mock(PaperAccountService.class), instruments,
            mock(ScalperAccountModel.class), mock(PaperPositionRepository.class), rejections);
    StrategyDefinition.SizingSpec sizing =
        new StrategyDefinition.SizingSpec("premium_budget", Map.of("budget_inr", bd("15000")));

    assertThat(
            guard.suggestedQty(
                sizing, "BFO", "SENSEX26JUL76300CE", bd("776"), null, "scalper"))
        .isNull();

    guard.recordZeroSizedEntry(
         89L, "scalp-connect-the-dots-sensex", sizing, "scalper", "BFO",
         "SENSEX26JUL76300CE", bd("776"), null, "BUY");

    ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
    verify(rejections)
        .recordZeroSize(
            eq(89L), eq("scalper"), eq("BFO"), eq("SENSEX26JUL76300CE"), eq("BUY"), detail.capture());
    assertThat(detail.getValue())
        .contains("strategy=scalp-connect-the-dots-sensex")
        .contains("premium=776")
        .contains("lot=20")
        .contains("budget_inr=15000")
        .contains("computed_lots=0");
  }
}
