package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    ManasGoverningStopCache cache = new ManasGoverningStopCache();
    // Position A: 100 qty, entry 200, stop 190 -> risk 100 × 10 = ₹1,000.
    PositionRow a = position(1L, 100, "200", "190");
    // Position B: 50 qty, entry 300, stop 320 (trailed ABOVE entry) -> open risk 0 (§3.5.B).
    PositionRow b = position(2L, 50, "300", "320");
    // Position C: 10 qty, entry 100, NO stop -> contributes 0 (no defined risk to sum).
    PositionRow c = position(3L, 10, "100", null);
    assertThat(PaperEmissionGuard.openRiskInr(List.of(a, b, c), cache)).isEqualByComparingTo("1000");
    assertThat(PaperEmissionGuard.openRiskInr(List.of(), cache)).isEqualByComparingTo("0");
  }

  /**
   * M40 Critical 3 fix, round 3 (owner ruling, 2026-08-02): the IN-MEMORY {@link
   * ManasGoverningStopCache} entry — never {@code stop_loss} itself (which also serves as the
   * intraday disaster-stop, never touched by this fix) — takes precedence once armed. 100 qty, entry
   * 200, {@code stop_loss} 190 (would give risk 1,000) but a tighter CACHED governing stop of 195 ->
   * effective risk 100×(200−195)=500. Before the trail arms (nothing cached), {@code stop_loss}
   * alone governs, unchanged from before this fix. Keyed by position id (round 4).
   */
  @Test
  void openRiskInrPrefersTheCachedGoverningStopOverStopLossOnceArmed() {
    ManasGoverningStopCache cache = new ManasGoverningStopCache();
    PositionRow armed = position(1L, 100, "200", "190");
    cache.put(armed.id(), "BUY", bd("195"));
    assertThat(PaperEmissionGuard.openRiskInr(List.of(armed), cache)).isEqualByComparingTo("500");
    assertThat(PaperEmissionGuard.effectiveStop(armed, cache)).isEqualByComparingTo("195");

    PositionRow unarmed = position(2L, 100, "200", "190");
    ManasGoverningStopCache emptyCache = new ManasGoverningStopCache();
    assertThat(PaperEmissionGuard.effectiveStop(unarmed, emptyCache)).isEqualByComparingTo("190");
  }

  /**
   * Round 4, cross-vendor review Critical 1: the cache is keyed by the position's OWN id, so two
   * DIFFERENT positions on the SAME symbol/side never share a cache entry — the exact "stale trail
   * attaches to a new position" failure the review found when the cache was keyed by the
   * (book,exchange,symbol,side) tuple instead (a dead-anchor row treated as fresh, or a manual
   * close racing the daily batch's exit pass, could otherwise resurrect a stale tuple-keyed entry
   * for a genuinely different position).
   */
  @Test
  void openRiskInrNeverSharesACachedStopAcrossDifferentPositionIdsOnTheSameSymbol() {
    ManasGoverningStopCache cache = new ManasGoverningStopCache();
    cache.put(99L, "BUY", bd("195")); // a DIFFERENT (e.g. since-closed) position's cached trail
    PositionRow different = position(7L, 100, "200", "190"); // same symbol/side, id 7, own stop 190
    assertThat(PaperEmissionGuard.effectiveStop(different, cache))
        .as("id 7 falls back to its OWN stopLoss(190) — id 99's cached 195 is never read for id 7")
        .isEqualByComparingTo("190");
  }

  private static PositionRow position(long id, long qty, String avgEntry, String stop) {
    return new PositionRow(
        id, "NSE", "TESTCO", "BUY", qty, bd(avgEntry), BigDecimal.ZERO, "OPEN",
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
            mock(PaperPositionRepository.class), mock(PaperOrderRejectionRecorder.class),
            new ManasGoverningStopCache(),
            new EquityMarkCache(java.time.Clock.systemUTC(), 5));
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
            mock(PaperPositionRepository.class), mock(PaperOrderRejectionRecorder.class),
            new ManasGoverningStopCache(),
            new EquityMarkCache(java.time.Clock.systemUTC(), 5));
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
            mock(PaperPositionRepository.class), mock(PaperOrderRejectionRecorder.class),
            new ManasGoverningStopCache(),
            new EquityMarkCache(java.time.Clock.systemUTC(), 5));
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

  /**
   * An option the master has no lot size for is NOT sizable. {@code RestInstrumentMetaClient} reports
   * {@code lotSize == 0} there instead of fabricating {@code 1}; before this, the class check alone
   * could not see the case — a {@code tools/historical-import} placeholder row carries a POPULATED
   * {@code instrument_type} with a NULL {@code lot_size}, so it classifies as a genuine OPTION and
   * sailed straight through, sizing 15000/776 = 19 units of a 20-lot contract.
   *
   * <p>The second half is the control that makes the first half mean something: the IDENTICAL call
   * with a KNOWN lot still sizes. A refusal that also fired on present metadata would pass the first
   * assertion and be a worse defect than the one it replaces.
   */
  @Test
  void optionWithUnknownLotIsNotSizableWhileKnownLotStillSizes() {
    PaperAccountService account = mock(PaperAccountService.class);
    InstrumentMetaClient instruments = mock(InstrumentMetaClient.class);
    PaperEmissionGuard guard =
        new PaperEmissionGuard(
            mock(RiskService.class), account, instruments, mock(ScalperAccountModel.class),
            mock(PaperPositionRepository.class), mock(PaperOrderRejectionRecorder.class),
            new ManasGoverningStopCache(),
            new EquityMarkCache(java.time.Clock.systemUTC(), 5));
    StrategyDefinition.SizingSpec sizing =
        new StrategyDefinition.SizingSpec("premium_budget", Map.of("budget_inr", bd("15000")));

    // lot UNKNOWN -> refuse. Both entry-sizing doors, not just the ordinary one.
    when(instruments.meta(any(), any()))
        .thenReturn(new InstrumentMeta(InstrumentClass.OPTION, bd("0.05"), 0L));
    assertThat(guard.suggestedQty(sizing, "NFO", "NIFTY26MAY24000CE", bd("100"), null, "scalper"))
        .isNull();
    when(account.realisedProfit("scalper")).thenReturn(bd("150000"));
    assertThat(guard.heroZeroSuggestedQty(sizing, "NFO", "NIFTY26MAY24000CE", bd("100"))).isNull();

    // lot KNOWN -> sizes exactly as before: 15000 / (100 x 75) = 2 lots = 150 units.
    when(instruments.meta(any(), any()))
        .thenReturn(new InstrumentMeta(InstrumentClass.OPTION, bd("0.05"), 75L));
    assertThat(guard.suggestedQty(sizing, "NFO", "NIFTY26MAY24000CE", bd("100"), null, "scalper"))
        .isEqualByComparingTo("150");

    // and an EQUITY is untouched by the derivatives rule: NSE lot 1 sizes 15000/100 = 150 units.
    when(instruments.meta(any(), any()))
        .thenReturn(new InstrumentMeta(InstrumentClass.EQUITY, bd("0.05"), 1L));
    assertThat(guard.suggestedQty(sizing, "NSE", "KANORICHEM", bd("100"), null, "scalper"))
        .isEqualByComparingTo("150");
  }

  /**
   * An unknown lot is recorded as its OWN fact, not as a ZERO_SIZE with a re-fabricated lot of 1
   * (cross-vendor review Major 4). ZERO_SIZE means "priced against a known lot, could not afford
   * one"; this means "we never knew what a lot was". One value meaning two things makes the
   * rejections ledger — the one surface built to answer "why did this entry not happen?" — answer it
   * wrongly, and the old {@code Math.max(1, lot)} persisted a {@code computed_lots} derived from a
   * lot that does not exist.
   */
  @Test
  void anUnknownLotIsRecordedAsADataGapNotAsAnUnaffordableZeroSize() {
    PaperAccountService account = mock(PaperAccountService.class);
    InstrumentMetaClient instruments = mock(InstrumentMetaClient.class);
    PaperOrderRejectionRecorder rejections = mock(PaperOrderRejectionRecorder.class);
    when(instruments.meta("NFO", "NIFTY26MAY24000CE"))
        .thenReturn(new InstrumentMeta(InstrumentClass.OPTION, bd("0.05"), 0L));
    when(account.equity(any())).thenReturn(bd("150000"));
    PaperEmissionGuard guard =
        new PaperEmissionGuard(
            mock(RiskService.class), account, instruments, mock(ScalperAccountModel.class),
            mock(PaperPositionRepository.class), rejections,
            new ManasGoverningStopCache(),
            new EquityMarkCache(java.time.Clock.systemUTC(), 5));
    StrategyDefinition.SizingSpec sizing =
        new StrategyDefinition.SizingSpec("premium_budget", Map.of("budget_inr", bd("15000")));

    guard.recordZeroSizedEntry(
        91L, "scalp-connect-the-dots-nifty", sizing, "scalper", "NFO",
        "NIFTY26MAY24000CE", bd("100"), null, "BUY");

    ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
    verify(rejections)
        .recordUnknownLot(
            eq(91L), eq("scalper"), eq("NFO"), eq("NIFTY26MAY24000CE"), eq("BUY"), detail.capture());
    assertThat(detail.getValue()).contains("lot=unknown");
    // and it must NOT be filed as the unaffordable-at-a-known-lot case.
    verify(rejections, never())
        .recordZeroSize(any(), any(), any(), any(), any(), any());
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
            mock(ScalperAccountModel.class), mock(PaperPositionRepository.class), rejections,
            new ManasGoverningStopCache(),
            new EquityMarkCache(java.time.Clock.systemUTC(), 5));
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
