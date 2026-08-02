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
 * precedence in the risk math once a position's Chandelier trail has armed, keyed by position id
 * (round 4). And Critical 2, round 4 (cross-vendor review, 2026-08-02): the gate FAILS CLOSED — never
 * open — when it cannot compute a risk figure (non-positive equity, or an undefined governing stop).
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
        new ManasGoverningStopCache(), new PyramidRiskCapAuditor(settings, notifier));
  }

  private static PositionRow position(String symbol, long qty, String avgEntry, String stop) {
    return position(1L, symbol, "BUY", qty, avgEntry, stop);
  }

  private static PositionRow position(
      long id, String symbol, String side, long qty, String avgEntry, String stop) {
    return new PositionRow(
        id, "NSE", symbol, side, qty, bd(avgEntry), BigDecimal.ZERO, "OPEN", null, null, null,
        stop == null ? null : bd(stop), null, BOOK);
  }

  /** A fully-wired {@link RiskService} over fresh mocks, cap 6.0% unless overridden by the caller. */
  private static RiskService risk(
      PaperPositionRepository positions, PaperAccountService account, ManasGoverningStopCache cache,
      String capPct) {
    RiskSettingsRepository settings = mock(RiskSettingsRepository.class);
    PaperMarginClient margin = mock(PaperMarginClient.class);
    NotifierClient notifier = mock(NotifierClient.class);
    return new RiskService(
        settings, positions, account, margin, notifier, CLOCK, false, bd(capPct), cache,
        new PyramidRiskCapAuditor(settings, notifier));
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
    RiskService risk = risk(positions, account, new ManasGoverningStopCache(), "6.0");
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
    RiskService risk = risk(positions, account, new ManasGoverningStopCache(), "3.5");
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
   * has ARMED (cached in {@link ManasGoverningStopCache}, keyed by its id — round 4), the averaging
   * projection uses THAT tighter figure — never the stale {@code stop_loss} — even though {@code
   * stop_loss} itself is never touched by this fix. Existing 100@100 (id 1) with {@code stop_loss}
   * 50 (would give a wide retained-bracket projection) but a CACHED governing stop of 95 (much
   * tighter): a same-key fill at 120 averages to 200@110, projected against 95 →
   * 200×(110−95)=3,000 = 3.0% of 100,000, comfortably under a 6% cap. Using the stale stop_loss (50)
   * instead would have projected 200×(110−50)=12,000 = 12% — wrongly breaching. The two numbers are
   * discriminated, not just both "some risk detected".
   */
  @Test
  void averagingUsesTheCachedGoverningStopNotTheStaleStopLoss() {
    PaperPositionRepository positions = mock(PaperPositionRepository.class);
    PaperAccountService account = mock(PaperAccountService.class);
    ManasGoverningStopCache cache = new ManasGoverningStopCache();
    long existingId = 1L; // matches the id position("TESTCO", ...) below constructs.
    cache.put(existingId, "BUY", bd("95"));
    RiskService risk = risk(positions, account, cache, "6.0");
    when(positions.listOpen(BOOK)).thenReturn(List.of(position("TESTCO", 100, "100", "50")));
    when(account.equity(BOOK)).thenReturn(bd("100000"));

    assertThat(
            risk.manasAggregateRiskWouldCross(
                BOOK, "NSE", "TESTCO", "BUY", 100, bd("120"), bd("999")))
        .as("200×(110−95)=3,000 → 3.0% under the 6% cap; the stale stop_loss(50) would wrongly give"
            + " 12,000 → 12% and breach")
        .isFalse();
  }

  /**
   * Round 4, cross-vendor review Critical 1: the cache is keyed by the position's OWN id, so a
   * cached trail computed for a DIFFERENT position (a different id) on the SAME symbol/side is never
   * read — the exact "stale trail attaches to a new position" failure the review found when the
   * cache was keyed by the (book,exchange,symbol,side) tuple instead. Position id 7 has NO cache
   * entry of its own even though id 99 (same symbol/side, e.g. a since-closed prior position) is
   * cached tight at 95 — id 7's risk must fall back to ITS OWN persisted stopLoss (50), not id 99's
   * cached value.
   */
  @Test
  void aCachedStopForADifferentPositionIdOnTheSameSymbolIsNeverInherited() {
    PaperPositionRepository positions = mock(PaperPositionRepository.class);
    PaperAccountService account = mock(PaperAccountService.class);
    ManasGoverningStopCache cache = new ManasGoverningStopCache();
    cache.put(99L, "BUY", bd("95")); // a DIFFERENT (e.g. since-closed) position's cached trail
    RiskService risk = risk(positions, account, cache, "6.0");
    PositionRow current = position(7L, "TESTCO", "BUY", 100, "100", "50"); // id 7, own stop 50
    when(positions.listOpen(BOOK)).thenReturn(List.of(current));
    when(account.equity(BOOK)).thenReturn(bd("100000"));

    assertThat(
            risk.manasAggregateRiskWouldCross(
                BOOK, "NSE", "TESTCO", "BUY", 100, bd("120"), bd("999")))
        .as("id 7 falls back to ITS OWN stopLoss(50): 200×(110−50)=12,000 → 12% breaches the 6% cap"
            + " — id 99's cached 95 (which would wrongly admit at 3.0%) is never read for id 7")
        .isTrue();
  }

  @Test
  void aGenuinelyFreshEntryWithNoExistingRowUsesItsOwnRequestedStop() {
    PaperPositionRepository positions = mock(PaperPositionRepository.class);
    PaperAccountService account = mock(PaperAccountService.class);
    RiskService risk = risk(positions, account, new ManasGoverningStopCache(), "6.0");
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

  /**
   * Round 4, cross-vendor review Critical 2: a safety gate must fail CLOSED, not open, when it
   * cannot compute — a book with zero or negative equity cannot have a percentage-of-equity risk
   * figure at all, so the gate now REFUSES (returns true) rather than the round-3 behaviour of
   * silently admitting (returning false, "does not cross").
   */
  @Test
  void nonPositiveEquityRefusesRatherThanSilentlyAdmitting() {
    PaperPositionRepository positions = mock(PaperPositionRepository.class);
    PaperAccountService account = mock(PaperAccountService.class);
    RiskService risk = risk(positions, account, new ManasGoverningStopCache(), "6.0");
    when(positions.listOpen(BOOK)).thenReturn(List.of());
    when(account.equity(BOOK)).thenReturn(BigDecimal.ZERO);

    assertThat(
            risk.manasAggregateRiskWouldCross(BOOK, "NSE", "NEWCO", "BUY", 100, bd("100"), bd("90")))
        .as("zero equity: a % of it is undefined — refuse, never silently admit")
        .isTrue();

    when(account.equity(BOOK)).thenReturn(bd("-500"));
    assertThat(
            risk.manasAggregateRiskWouldCross(BOOK, "NSE", "NEWCO", "BUY", 100, bd("100"), bd("90")))
        .as("negative equity: same refusal")
        .isTrue();
  }

  /**
   * Round 4, cross-vendor review Critical 2: an existing lot with NEITHER a cached governing stop
   * NOR a persisted {@code stopLoss} has a genuinely UNDEFINED risk contribution — round 3 silently
   * treated that as zero (admitting an averaging add that could carry unlimited real risk); round 4
   * refuses instead.
   */
  @Test
  void anExistingLotWithNoGoverningStopAtAllRefusesAnAveragingAdd() {
    PaperPositionRepository positions = mock(PaperPositionRepository.class);
    PaperAccountService account = mock(PaperAccountService.class);
    RiskService risk = risk(positions, account, new ManasGoverningStopCache(), "6.0");
    when(positions.listOpen(BOOK)).thenReturn(List.of(position("TESTCO", 100, "100", null)));
    when(account.equity(BOOK)).thenReturn(bd("100000"));

    assertThat(
            risk.manasAggregateRiskWouldCross(
                BOOK, "NSE", "TESTCO", "BUY", 100, bd("120"), bd("999")))
        .as("the existing lot's risk is undefined (no stop at all) — refuse, never treat as zero")
        .isTrue();
  }

  /**
   * Round 4, cross-vendor review Critical 2: a genuinely fresh entry with NO requested stop at all
   * cannot have its risk computed — round 3 silently treated that as zero risk (admitting an
   * unbounded-risk fill); round 4 refuses instead.
   */
  @Test
  void aFreshEntryWithNoRequestedStopAtAllRefuses() {
    PaperPositionRepository positions = mock(PaperPositionRepository.class);
    PaperAccountService account = mock(PaperAccountService.class);
    RiskService risk = risk(positions, account, new ManasGoverningStopCache(), "6.0");
    when(positions.listOpen(BOOK)).thenReturn(List.of());
    when(account.equity(BOOK)).thenReturn(bd("1000000"));

    assertThat(
            risk.manasAggregateRiskWouldCross(BOOK, "NSE", "NEWCO", "BUY", 100, bd("100"), null))
        .as("no requested stop means the risk is undefined — refuse, never admit at zero risk")
        .isTrue();
  }

  @Test
  void nonManasBooksAndDegenerateInputsAreAlwaysFalse() {
    PaperPositionRepository positions = mock(PaperPositionRepository.class);
    PaperAccountService account = mock(PaperAccountService.class);
    RiskService risk = risk(positions, account, new ManasGoverningStopCache(), "6.0");
    when(positions.listOpen(any())).thenReturn(List.of(position("HUGE", 1_000_000, "1000", "1")));
    when(account.equity(any())).thenReturn(bd("1"));

    assertThat(
            risk.manasAggregateRiskWouldCross(
                "scalper", "NSE", "NEWCO", "BUY", 1_000, bd("100"), bd("90")))
        .as("scoped to Books.MANAS_ARORA only — a no-op for every other book")
        .isFalse();
    assertThat(risk.manasAggregateRiskWouldCross(BOOK, "NSE", "NEWCO", "BUY", 0, bd("100"), bd("90")))
        .as("non-positive qty never breaches (validated upstream by PaperService#openOrder already)")
        .isFalse();
    assertThat(risk.manasAggregateRiskWouldCross(BOOK, "NSE", "NEWCO", "BUY", 100, null, bd("90")))
        .as("no fill price means nothing to project (never reached on the real write path either)")
        .isFalse();
  }

  @Test
  void manasAggregateRiskCapPctReturnsTheInjectedKnob() {
    assertThat(riskWithCap("6.0").manasAggregateRiskCapPct()).isEqualByComparingTo("6.0");
  }
}
