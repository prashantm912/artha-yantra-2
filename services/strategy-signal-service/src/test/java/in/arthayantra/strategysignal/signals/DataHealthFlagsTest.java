package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.strategysignal.scalper.OiQuadrant;
import in.arthayantra.strategysignal.scalper.ScalperGateContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

/**
 * Per-row data-health flags (F5 U3). The class under test is pure, so every case is a direct call —
 * no Spring, no DB, no clock.
 *
 * <p>The load-bearing case is {@link #s24SuppressionIsKeyedPerOiRootNotPerDate()}: S24's OI skip
 * fires on the ROOT's own monthly expiry (NSE last Tuesday / BSE last Thursday), so a date-keyed
 * exemption would mark a whole normal SENSEX session as by-design-inert and hide a real outage.
 */
class DataHealthFlagsTest {

  /** Every macro input present and plausible — the "nothing is wrong" baseline. */
  private static ScalperGateContext.Macro healthyMacro() {
    return new ScalperGateContext.Macro(
        new BigDecimal("12.5"), // atmIv
        new BigDecimal("43.0"), // ivRank
        new BigDecimal("11.2"), // vixLevel
        Boolean.TRUE, // vixRising
        31, // advances
        18, // declines
        new BigDecimal("54.0"), // fiiLongPct
        new BigDecimal("12.1"), // ceIvAvg6
        new BigDecimal("12.4"), // peIvAvg6
        new BigDecimal("0.3"), // constituentBias
        new BigDecimal("0.1"), // ceIvSlope
        new BigDecimal("-0.1"), // peIvSlope
        new BigDecimal("2.5"), // premiumSkewPct
        Boolean.TRUE, // dowUp
        BigDecimal.ONE); // fiiBiasSign
  }

  /** A live OI read on an ordinary bar: quadrants resolved, magnitudes present. */
  private static ScalperGateContext.Oi liveOi() {
    return new ScalperGateContext.Oi(
        OiQuadrant.LONG_BUILDUP, // underlying
        OiQuadrant.SHORT_COVERING, // futures
        new BigDecimal("58.0"), // sentimentPct
        new BigDecimal("4.2"), // trendingPeMinusCePct
        new BigDecimal("31.5"), // futuresBasis
        new BigDecimal("120000"), // ceOiDelta
        new BigDecimal("240000"), // peOiDelta
        new BigDecimal("12.0"), // callPutDeltaImbalancePct
        true, // crossedThisWindow
        true, // gapWidening
        new BigDecimal("0.8"), // sentimentSlope
        new BigDecimal("9.0"), // spurtOiPct
        new BigDecimal("1.4")); // spurtPricePct
  }

  /**
   * The EXACT shape {@code MarketOiClient.oi()} returns when it skips the OI block (:352-354):
   * NEUTRAL on both quadrants, null on every soft numeric, and the price-derived basis kept. A total
   * OI-read failure leaves the same shape behind, which is why the flag is meaningful off an expiry
   * day too.
   */
  private static ScalperGateContext.Oi inertOi() {
    return new ScalperGateContext.Oi(
        OiQuadrant.NEUTRAL, OiQuadrant.NEUTRAL, null, null, new BigDecimal("31.5"), null, null,
        null, false, false, null, null, null);
  }

  private static ScalperGateContext context(
      String underlying, ScalperGateContext.Oi oi, ScalperGateContext.Macro macro) {
    return new ScalperGateContext(
        underlying,
        "NIFTY 50",
        LocalTime.of(11, 0),
        new ScalperGateContext.Chart(
            new BigDecimal("24000"), new BigDecimal("23990"), new BigDecimal("23985"),
            new BigDecimal("23900"), 1, new BigDecimal("58"), new BigDecimal("120000")),
        oi,
        macro);
  }

  /** A day inside the bundled calendar that is no root's monthly expiry (2026-06-10, a Wednesday). */
  private static final LocalDate ORDINARY_DAY = LocalDate.of(2026, 6, 10);

  @Test
  void healthyInputsAreNotDegraded() {
    DataHealthFlags health =
        DataHealthFlags.of(context("NIFTY 50", liveOi(), healthyMacro()), ORDINARY_DAY);

    assertThat(health.degraded()).isFalse();
    assertThat(health.flags()).isEmpty();
    assertThat(health.contextBearing()).isTrue();
    assertThat(health.oiSuppressed()).isFalse();
  }

  @Test
  void anAbsentMacroInputIsFlaggedSpecificallyAndMarksTheRowDegraded() {
    ScalperGateContext.Macro noIvRank =
        new ScalperGateContext.Macro(
            new BigDecimal("12.5"), null, new BigDecimal("11.2"), Boolean.TRUE, 31, 18,
            new BigDecimal("54.0"), new BigDecimal("12.1"), new BigDecimal("12.4"),
            new BigDecimal("0.3"), new BigDecimal("0.1"), new BigDecimal("-0.1"),
            new BigDecimal("2.5"), Boolean.TRUE, BigDecimal.ONE);

    DataHealthFlags health =
        DataHealthFlags.of(context("NIFTY 50", liveOi(), noIvRank), ORDINARY_DAY);

    assertThat(health.degraded()).isTrue();
    // SPECIFIC, not just "something is wrong": only the absent input is named.
    assertThat(health.flags()).containsExactly(DataHealthFlags.IV_RANK_ABSENT);
  }

  @Test
  void breadthIsAbsentOnlyWhenBothCountsAreZero() {
    ScalperGateContext.Macro deadBreadth =
        new ScalperGateContext.Macro(
            new BigDecimal("12.5"), new BigDecimal("43.0"), new BigDecimal("11.2"), Boolean.TRUE,
            0, 0, new BigDecimal("54.0"), new BigDecimal("12.1"), new BigDecimal("12.4"),
            new BigDecimal("0.3"), new BigDecimal("0.1"), new BigDecimal("-0.1"),
            new BigDecimal("2.5"), Boolean.TRUE, BigDecimal.ONE);

    assertThat(DataHealthFlags.of(context("NIFTY 50", liveOi(), deadBreadth), ORDINARY_DAY).flags())
        .containsExactly(DataHealthFlags.BREADTH_ABSENT);

    // A decisively one-sided tape (every constituent down) is a MARKET STATE, not a dead read.
    ScalperGateContext.Macro oneSided =
        new ScalperGateContext.Macro(
            new BigDecimal("12.5"), new BigDecimal("43.0"), new BigDecimal("11.2"), Boolean.TRUE,
            0, 50, new BigDecimal("54.0"), new BigDecimal("12.1"), new BigDecimal("12.4"),
            new BigDecimal("0.3"), new BigDecimal("0.1"), new BigDecimal("-0.1"),
            new BigDecimal("2.5"), Boolean.TRUE, BigDecimal.ONE);
    assertThat(DataHealthFlags.of(context("NIFTY 50", liveOi(), oneSided), ORDINARY_DAY).degraded())
        .isFalse();
  }

  @Test
  void anInertOiBlockOnAnOrdinaryDayIsDegraded() {
    DataHealthFlags health =
        DataHealthFlags.of(context("NIFTY 50", inertOi(), healthyMacro()), ORDINARY_DAY);

    assertThat(health.oiSuppressed()).isFalse();
    assertThat(health.degraded()).isTrue();
    assertThat(health.flags()).containsExactly(DataHealthFlags.OI_INERT);
  }

  @Test
  void aQuietButLiveOiBarIsNotInert() {
    // The trap a naive port of the canary's window probes falls into: per row, a zero spurt and a
    // NEUTRAL quadrant are ordinary. Measured live 2026-07-20..31, spurtPricePct = 0 on ~43% of
    // context-bearing rows on NON-expiry sessions. Only a WHOLLY empty OI block is inert.
    ScalperGateContext.Oi quiet =
        new ScalperGateContext.Oi(
            OiQuadrant.NEUTRAL, OiQuadrant.NEUTRAL, new BigDecimal("50.0"), BigDecimal.ZERO,
            new BigDecimal("31.5"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false,
            false, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

    DataHealthFlags health =
        DataHealthFlags.of(context("NIFTY 50", quiet, healthyMacro()), ORDINARY_DAY);

    assertThat(health.degraded()).isFalse();
    assertThat(health.flags()).isEmpty();
  }

  @Test
  void aRowBlockedBeforeTheChainFetchIsUninformativeNotDegraded() {
    // ~23% of live rows block at time-window / time-of-day / option-side and carry no context at
    // all. Calling those degraded would drown the real signal (T17's lesson, per row).
    DataHealthFlags health = DataHealthFlags.of(null, ORDINARY_DAY);

    assertThat(health.contextBearing()).isFalse();
    assertThat(health.degraded()).isFalse();
    assertThat(health.flags()).isEmpty();
    assertThat(health.oiSuppressed()).isFalse();
  }

  /**
   * THE LOAD-BEARING CASE. On an NSE-only monthly index expiry the NIFTY-rooted OI read is
   * S24-suppressed by design, but the SENSEX-rooted one is NOT (BSE's monthly is the last Thursday)
   * — so an identically inert SENSEX block that day is a genuine outage and must stay degraded. A
   * date-keyed exemption reads BOTH as by-design and silences a whole session; that misreading cost
   * a live investigation (#1073).
   *
   * <p>Both directions are asserted, on a real expiry date derived from the bundled calendar rather
   * than hardcoded, so a calendar refresh cannot quietly turn the fixture into an ordinary day.
   */
  @Test
  void s24SuppressionIsKeyedPerOiRootNotPerDate() {
    LocalDate nseOnly = nseOnlyMonthlyExpiry2026();
    assertThat(MarketCalendar.nse().isMonthlyIndexExpiryDay(nseOnly))
        .as("fixture must be an NSE monthly index expiry")
        .isTrue();
    assertThat(MarketCalendar.bse().isMonthlyIndexExpiryDay(nseOnly))
        .as("fixture must NOT also be a BSE monthly index expiry")
        .isFalse();

    // (a) the EXPIRING root: inert OI is by design, so the row is NOT degraded.
    DataHealthFlags nifty =
        DataHealthFlags.of(context("NIFTY 50", inertOi(), healthyMacro()), nseOnly);
    assertThat(nifty.oiSuppressed()).as("NIFTY root expires today — suppressed").isTrue();
    assertThat(nifty.flags()).as("the OI flag is withheld, not merely ignored").isEmpty();
    assertThat(nifty.degraded()).isFalse();

    // (b) the OTHER root on the SAME DAY: identical inert OI, but BSE is not expiring — a real
    // outage, and it must still read degraded.
    DataHealthFlags sensex =
        DataHealthFlags.of(context("SENSEX", inertOi(), healthyMacro()), nseOnly);
    assertThat(sensex.oiSuppressed()).as("BSE monthly is the last THURSDAY — not today").isFalse();
    assertThat(sensex.degraded()).isTrue();
    assertThat(sensex.flags()).containsExactly(DataHealthFlags.OI_INERT);

    // (c) the mirror image on a BSE-only monthly expiry — the exemption must not be one-directional.
    LocalDate bseOnly = bseOnlyMonthlyExpiry2026();
    assertThat(DataHealthFlags.of(context("SENSEX", inertOi(), healthyMacro()), bseOnly).degraded())
        .as("SENSEX root expires on the BSE monthly — suppressed")
        .isFalse();
    assertThat(
            DataHealthFlags.of(context("NIFTY 50", inertOi(), healthyMacro()), bseOnly).degraded())
        .as("NIFTY is not expiring on the BSE monthly — a dead OI block is a real outage")
        .isTrue();
  }

  @Test
  void suppressionStandsDownOnlyTheOiFlagNotTheMacroOnes() {
    // The OI block is exempt on the root's expiry; the macro reads are not — MarketOiClient.macro()
    // is untouched by the S24 skip, so a dead breadth that day is still an outage.
    ScalperGateContext.Macro deadBreadth =
        new ScalperGateContext.Macro(
            new BigDecimal("12.5"), new BigDecimal("43.0"), new BigDecimal("11.2"), Boolean.TRUE,
            0, 0, new BigDecimal("54.0"), new BigDecimal("12.1"), new BigDecimal("12.4"),
            new BigDecimal("0.3"), new BigDecimal("0.1"), new BigDecimal("-0.1"),
            new BigDecimal("2.5"), Boolean.TRUE, BigDecimal.ONE);

    DataHealthFlags health =
        DataHealthFlags.of(
            context("NIFTY 50", inertOi(), deadBreadth), nseOnlyMonthlyExpiry2026());

    assertThat(health.oiSuppressed()).isTrue();
    assertThat(health.flags()).containsExactly(DataHealthFlags.BREADTH_ABSENT);
    assertThat(health.degraded()).isTrue();
  }

  @Test
  void anUnknownRootFallsToNseSoADeadOiBlockStillReadsDegraded() {
    // Fail-loud default: a row whose context carries no underlying must not be granted a by-design
    // exemption it cannot prove.
    LocalDate bseOnly = bseOnlyMonthlyExpiry2026();
    DataHealthFlags health = DataHealthFlags.of(context(null, inertOi(), healthyMacro()), bseOnly);

    assertThat(health.oiSuppressed()).isFalse();
    assertThat(health.degraded()).isTrue();
  }

  @Test
  void aYearOutsideTheBundledCalendarNeverClaimsSuppression() {
    // MarketCalendar throws outside its coverage (2024-2026). Guessing "by design" there would hide
    // outages; the calendar cliff has its own canary.
    DataHealthFlags health =
        DataHealthFlags.of(context("NIFTY 50", inertOi(), healthyMacro()), LocalDate.of(2029, 6, 5));

    assertThat(health.oiSuppressed()).isFalse();
    assertThat(health.degraded()).isTrue();
  }

  @Test
  void aNullMacroFlagsEveryMacroInput() {
    DataHealthFlags health = DataHealthFlags.of(context("NIFTY 50", liveOi(), null), ORDINARY_DAY);

    assertThat(health.degraded()).isTrue();
    assertThat(health.flags())
        .containsExactlyInAnyOrder(
            DataHealthFlags.BREADTH_ABSENT,
            DataHealthFlags.ATM_IV_ABSENT,
            DataHealthFlags.IV_RANK_ABSENT,
            DataHealthFlags.VIX_ABSENT,
            DataHealthFlags.FII_ABSENT,
            DataHealthFlags.DOW_ABSENT);
  }

  /** A 2026 day that is an NSE monthly index expiry but NOT a BSE one (NSE Tuesday vs BSE Thursday). */
  private static LocalDate nseOnlyMonthlyExpiry2026() {
    for (LocalDate d = LocalDate.of(2026, 1, 1); d.getYear() == 2026; d = d.plusDays(1)) {
      if (MarketCalendar.nse().isMonthlyIndexExpiryDay(d)
          && !MarketCalendar.bse().isMonthlyIndexExpiryDay(d)) {
        return d;
      }
    }
    throw new IllegalStateException("no NSE-only monthly expiry in 2026 — calendar changed");
  }

  /** The mirror: a BSE monthly index expiry that is not an NSE one. */
  private static LocalDate bseOnlyMonthlyExpiry2026() {
    for (LocalDate d = LocalDate.of(2026, 1, 1); d.getYear() == 2026; d = d.plusDays(1)) {
      if (MarketCalendar.bse().isMonthlyIndexExpiryDay(d)
          && !MarketCalendar.nse().isMonthlyIndexExpiryDay(d)) {
        return d;
      }
    }
    throw new IllegalStateException("no BSE-only monthly expiry in 2026 — calendar changed");
  }
}
