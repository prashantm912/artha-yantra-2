package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.arthayantra.strategysignal.notifier.NotifierClient;
import in.arthayantra.strategysignal.paper.PaperPositionRepository.PositionRow;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link RiskService#manasAggregateRiskWouldCross} — the M40 cross-vendor review Critical 1+2 fix
 * (2026-08-02): the AUTHORITATIVE aggregate open-risk check at the paper-position WRITE, projected
 * against the actual fill/stop, closing two gaps {@code ManasPyramidPolicy#wouldBreachRiskCap}'s
 * emission-time (candle-close) estimate could not see. Both worked examples below are the reviewer's
 * OWN numbers, reproduced exactly. Also covers Critical 3, round 3 (owner ruling, 2026-08-02): the
 * IN-MEMORY {@link ManasGoverningStopCache} — never {@code stop_loss} itself, never persisted — takes
 * precedence in the risk math once a position's Chandelier trail has armed.
 */
class RiskServiceManasAggregateRiskTest {

  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-02T08:00:00Z"), ZoneOffset.UTC);
  private static final String BOOK = "manas-arora";

  private static BigDecimal bd(String s) {
    return new BigDecimal(s);
  }

  private static RiskService riskWithCap(String capPct) {
    RiskSettingsRepository settings = mock(RiskSettingsRepository.class);
    PaperPositionRepository positions = mock(PaperPositionRepository.class);
    PaperAccountService account = mock(PaperAccountService.class);
    PaperMarginClient margin = mock(PaperMarginClient.class);
    NotifierClient notifier = mock(NotifierClient.class);
    return new RiskService(
        settings, positions, account, margin, notifier, CLOCK, false, bd(capPct),
        new ManasGoverningStopCache());
  }

  private static PositionRow position(String symbol, long qty, String avgEntry, String stop) {
    return new PositionRow(
        1L, "NSE", symbol, "BUY", qty, bd(avgEntry), BigDecimal.ZERO, "OPEN", null, null, null,
        stop == null ? null : bd(stop), null, BOOK);
  }

  /**
   * Critical 1's worked example: existing risk ₹50,000, equity ₹10L, reference ₹100, stop ₹90, qty
   * 1,000. The emission-time estimate (bar close, no slippage) lands EXACTLY at 6% — not a breach.
   * The real fill adds the equity BUY slippage (5 bps fallback, {@code ltp_slippage/v1}), landing at
   * ₹100.05 — 60,050 / 1,000,000 = 6.005%, a genuine breach the candle-close estimate could not see.
   */
  @Test
  void aFillPriceFractionallyAboveTheReferenceCanCrossTheCapWhereTheEstimateDidNot() {
    PaperPositionRepository positions = mock(PaperPositionRepository.class);
    PaperAccountService account = mock(PaperAccountService.class);
    RiskSettingsRepository settings = mock(RiskSettingsRepository.class);
    PaperMarginClient margin = mock(PaperMarginClient.class);
    NotifierClient notifier = mock(NotifierClient.class);
    RiskService risk =
        new RiskService(
            settings, positions, account, margin, notifier, CLOCK, false, bd("6.0"),
            new ManasGoverningStopCache());
    when(positions.listOpen(BOOK))
        .thenReturn(List.of(position("EXISTING", 5_000, "100", "90"))); // 5000*(100-90)=50,000
    when(account.equity(BOOK)).thenReturn(bd("1000000"));

    assertThat(
            risk.manasAggregateRiskWouldCross(
                BOOK, "NSE", "NEWCO", "BUY", 1_000, bd("100"), bd("90")))
        .as("the emission-time candle-close estimate: exactly AT the 6% cap, not over it")
        .isFalse();
    assertThat(
            risk.manasAggregateRiskWouldCross(
                BOOK, "NSE", "NEWCO", "BUY", 1_000, bd("100.05"), bd("90")))
        .as("the REAL slippage-adjusted fill (100 + 5bps): 60,050 / 1,000,000 = 6.005% breaches")
        .isTrue();
  }

  /**
   * Critical 2's worked example: an existing 100@₹100/stop ₹90 (risk ₹1,000) plus a nominal
   * 100@₹120 fill on the SAME (exchange,tradingsymbol,side) key. {@code PaperService#upsertPosition}
   * averages this into ONE row — 200@₹110 — and KEEPS the ORIGINAL ₹90 stop (an add never re-brackets
   * the existing lot). The TRUE projected risk is 200×(110−90) = ₹4,000, not the ₹2,000 a naive
   * "existing + this fill's own qty×stopDistance" sum would give (existing 1,000 + a nominal
   * 100×(120−110)=1,000 "new-lot" risk using a stop the position will never actually carry) — a 2×
   * understatement. equity is set so 4,000 breaches a 3.5% cap while 2,000 would not, so the two
   * calculations are DISCRIMINATED, not just both "some risk detected".
   */
  @Test
  void averagingOntoAnExistingRowProjectsAgainstTheRetainedStopNotANaiveSum() {
    PaperPositionRepository positions = mock(PaperPositionRepository.class);
    PaperAccountService account = mock(PaperAccountService.class);
    RiskSettingsRepository settings = mock(RiskSettingsRepository.class);
    PaperMarginClient margin = mock(PaperMarginClient.class);
    NotifierClient notifier = mock(NotifierClient.class);
    RiskService risk =
        new RiskService(
            settings, positions, account, margin, notifier, CLOCK, false, bd("3.5"),
            new ManasGoverningStopCache());
    when(positions.listOpen(BOOK)).thenReturn(List.of(position("TESTCO", 100, "100", "90")));
    when(account.equity(BOOK)).thenReturn(bd("100000"));

    // A request-supplied stop of 110 is what a NAIVE per-leg sum would use for "the new lot" (giving
    // the wrong 2,000 total, 2.0% — under a 3.5% cap); the method must ignore it once an existing row
    // is found and use the RETAINED stop (90) against the AVERAGED price/qty instead.
    assertThat(
            risk.manasAggregateRiskWouldCross(
                BOOK, "NSE", "TESTCO", "BUY", 100, bd("120"), bd("110")))
        .as("true projected risk 200×(110−90)=4,000 → 4.0% breaches the 3.5% cap"
            + " (a naive sum would compute 2,000 → 2.0%, wrongly passing)")
        .isTrue();
  }

  /**
   * M40 Critical 3 fix, round 3 (owner ruling, 2026-08-02): when the existing row's Chandelier trail
   * has ARMED (cached in {@link ManasGoverningStopCache}), the averaging projection uses THAT
   * tighter figure — never the stale {@code stop_loss} — even though {@code stop_loss} itself is
   * never touched by this fix. Existing 100@100 with {@code stop_loss} 50 (would give a wide
   * retained-bracket projection) but a CACHED governing stop of 95 (much tighter): a same-key fill
   * at 120 averages to 200@110, projected against 95 → 200×(110−95)=3,000 = 3.0% of 100,000,
   * comfortably under a 6% cap. Using the stale stop_loss (50) instead would have projected
   * 200×(110−50)=12,000 = 12% — wrongly breaching. The two numbers are discriminated, not just both
   * "some risk detected".
   */
  @Test
  void averagingUsesTheCachedGoverningStopNotTheStaleStopLoss() {
    PaperPositionRepository positions = mock(PaperPositionRepository.class);
    PaperAccountService account = mock(PaperAccountService.class);
    RiskSettingsRepository settings = mock(RiskSettingsRepository.class);
    PaperMarginClient margin = mock(PaperMarginClient.class);
    NotifierClient notifier = mock(NotifierClient.class);
    ManasGoverningStopCache cache = new ManasGoverningStopCache();
    cache.put(BOOK, "NSE", "TESTCO", "BUY", bd("95"));
    RiskService risk =
        new RiskService(
            settings, positions, account, margin, notifier, CLOCK, false, bd("6.0"), cache);
    when(positions.listOpen(BOOK)).thenReturn(List.of(position("TESTCO", 100, "100", "50")));
    when(account.equity(BOOK)).thenReturn(bd("100000"));

    assertThat(
            risk.manasAggregateRiskWouldCross(
                BOOK, "NSE", "TESTCO", "BUY", 100, bd("120"), bd("999")))
        .as("200×(110−95)=3,000 → 3.0% under the 6% cap; the stale stop_loss(50) would wrongly give"
            + " 12,000 → 12% and breach")
        .isFalse();
  }

  @Test
  void aGenuinelyFreshEntryWithNoExistingRowUsesItsOwnRequestedStop() {
    PaperPositionRepository positions = mock(PaperPositionRepository.class);
    PaperAccountService account = mock(PaperAccountService.class);
    RiskSettingsRepository settings = mock(RiskSettingsRepository.class);
    PaperMarginClient margin = mock(PaperMarginClient.class);
    NotifierClient notifier = mock(NotifierClient.class);
    RiskService risk =
        new RiskService(
            settings, positions, account, margin, notifier, CLOCK, false, bd("6.0"),
            new ManasGoverningStopCache());
    when(positions.listOpen(BOOK)).thenReturn(List.of());
    when(account.equity(BOOK)).thenReturn(bd("1000000"));

    assertThat(
            risk.manasAggregateRiskWouldCross(
                BOOK, "NSE", "NEWCO", "BUY", 100, bd("100"), bd("40")))
        .as("100×(100−40)=6,000 = 0.6% of 1,000,000 — comfortably under the 6% cap")
        .isFalse();
    assertThat(
            risk.manasAggregateRiskWouldCross(
                BOOK, "NSE", "NEWCO", "BUY", 7_000, bd("100"), bd("40")))
        .as("7,000×60=420,000 = 42% of 1,000,000 — breaches")
        .isTrue();
  }

  @Test
  void nonManasBooksAndDegenerateInputsAreAlwaysFalse() {
    PaperPositionRepository positions = mock(PaperPositionRepository.class);
    PaperAccountService account = mock(PaperAccountService.class);
    RiskSettingsRepository settings = mock(RiskSettingsRepository.class);
    PaperMarginClient margin = mock(PaperMarginClient.class);
    NotifierClient notifier = mock(NotifierClient.class);
    RiskService risk =
        new RiskService(
            settings, positions, account, margin, notifier, CLOCK, false, bd("6.0"),
            new ManasGoverningStopCache());
    when(positions.listOpen(any())).thenReturn(List.of(position("HUGE", 1_000_000, "1000", "1")));
    when(account.equity(any())).thenReturn(bd("1"));

    assertThat(
            risk.manasAggregateRiskWouldCross(
                "scalper", "NSE", "NEWCO", "BUY", 1_000, bd("100"), bd("90")))
        .as("scoped to Books.MANAS_ARORA only — a no-op for every other book")
        .isFalse();
    assertThat(risk.manasAggregateRiskWouldCross(BOOK, "NSE", "NEWCO", "BUY", 0, bd("100"), bd("90")))
        .as("non-positive qty never breaches")
        .isFalse();
    assertThat(risk.manasAggregateRiskWouldCross(BOOK, "NSE", "NEWCO", "BUY", 100, null, bd("90")))
        .as("no fill price means nothing to project")
        .isFalse();
  }

  @Test
  void manasAggregateRiskCapPctReturnsTheInjectedKnob() {
    assertThat(riskWithCap("6.0").manasAggregateRiskCapPct()).isEqualByComparingTo("6.0");
  }
}
