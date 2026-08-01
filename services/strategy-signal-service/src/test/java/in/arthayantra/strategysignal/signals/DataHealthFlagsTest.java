package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.strategysignal.scalper.OiQuadrant;
import in.arthayantra.strategysignal.scalper.ScalperGateContext;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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

  /** Positional alias for {@code Macro}'s canonical constructor, so the fixtures read once. */
  private static ScalperGateContext.Macro macro(
      BigDecimal atmIv, BigDecimal ivRank, BigDecimal vixLevel, Boolean vixRising, int advances,
      int declines, BigDecimal fiiLongPct, BigDecimal ceIvAvg6, BigDecimal peIvAvg6,
      BigDecimal constituentBias, BigDecimal ceIvSlope, BigDecimal peIvSlope,
      BigDecimal premiumSkewPct, Boolean dowUp, BigDecimal fiiBiasSign) {
    return new ScalperGateContext.Macro(
        atmIv, ivRank, vixLevel, vixRising, advances, declines, fiiLongPct, ceIvAvg6, peIvAvg6,
        constituentBias, ceIvSlope, peIvSlope, premiumSkewPct, dowUp, fiiBiasSign);
  }

  /** Every macro input present and plausible — the "nothing is wrong" baseline. */
  private static ScalperGateContext.Macro healthyMacro() {
    return macroWithout(null);
  }

  /**
   * The healthy macro with exactly ONE field nulled — the partial-source-failure fixture. Nulling by
   * name (rather than 13 hand-written literals) keeps each case honest: every other input is
   * provably still present, so a flag that appears can only have come from the named absence.
   */
  private static ScalperGateContext.Macro macroWithout(String absent) {
    BigDecimal atmIv = new BigDecimal("12.5");
    BigDecimal ivRank = new BigDecimal("43.0");
    BigDecimal vixLevel = new BigDecimal("11.2");
    Boolean vixRising = Boolean.TRUE;
    BigDecimal fiiLongPct = new BigDecimal("54.0");
    BigDecimal ceIvAvg6 = new BigDecimal("12.1");
    BigDecimal peIvAvg6 = new BigDecimal("12.4");
    BigDecimal constituentBias = new BigDecimal("0.3");
    BigDecimal ceIvSlope = new BigDecimal("0.1");
    BigDecimal peIvSlope = new BigDecimal("-0.1");
    BigDecimal premiumSkewPct = new BigDecimal("2.5");
    Boolean dowUp = Boolean.TRUE;
    BigDecimal fiiBiasSign = BigDecimal.ONE;
    if (absent != null) {
      switch (absent) {
        case "atmIv" -> atmIv = null;
        case "ivRank" -> ivRank = null;
        case "vixLevel" -> vixLevel = null;
        case "vixRising" -> vixRising = null;
        case "fiiLongPct" -> fiiLongPct = null;
        case "ceIvAvg6" -> ceIvAvg6 = null;
        case "peIvAvg6" -> peIvAvg6 = null;
        case "constituentBias" -> constituentBias = null;
        case "ceIvSlope" -> ceIvSlope = null;
        case "peIvSlope" -> peIvSlope = null;
        case "premiumSkewPct" -> premiumSkewPct = null;
        case "dowUp" -> dowUp = null;
        case "fiiBiasSign" -> fiiBiasSign = null;
        default -> throw new IllegalArgumentException("unknown Macro field: " + absent);
      }
    }
    return macro(atmIv, ivRank, vixLevel, vixRising, 31, 18, fiiLongPct, ceIvAvg6, peIvAvg6,
        constituentBias, ceIvSlope, peIvSlope, premiumSkewPct, dowUp, fiiBiasSign);
  }

  /**
   * The live OI block with exactly ONE field made ABSENT. Note the quadrants: absence there is
   * {@link OiQuadrant#NEUTRAL}, not null — NEUTRAL is the sentinel the live assembler uses to say
   * "snapshot unavailable" without a null that would NPE the gates, so the fixture must model the
   * real shape.
   */
  private static ScalperGateContext.Oi oiWithout(String absent) {
    OiQuadrant underlying = OiQuadrant.LONG_BUILDUP;
    OiQuadrant futures = OiQuadrant.SHORT_COVERING;
    BigDecimal sentimentPct = new BigDecimal("58.0");
    BigDecimal trendingPeMinusCePct = new BigDecimal("4.2");
    BigDecimal futuresBasis = new BigDecimal("31.5");
    BigDecimal ceOiDelta = new BigDecimal("120000");
    BigDecimal peOiDelta = new BigDecimal("240000");
    BigDecimal callPutDeltaImbalancePct = new BigDecimal("12.0");
    BigDecimal sentimentSlope = new BigDecimal("0.8");
    BigDecimal spurtOiPct = new BigDecimal("9.0");
    BigDecimal spurtPricePct = new BigDecimal("1.4");
    BigDecimal oiDivergencePct = new BigDecimal("22.0");
    if (absent != null) {
      switch (absent) {
        case "underlying" -> underlying = OiQuadrant.NEUTRAL;
        case "futures" -> futures = OiQuadrant.NEUTRAL;
        case "sentimentPct" -> sentimentPct = null;
        case "trendingPeMinusCePct" -> trendingPeMinusCePct = null;
        case "futuresBasis" -> futuresBasis = null;
        case "ceOiDelta" -> ceOiDelta = null;
        case "peOiDelta" -> peOiDelta = null;
        case "callPutDeltaImbalancePct" -> callPutDeltaImbalancePct = null;
        case "sentimentSlope" -> sentimentSlope = null;
        case "spurtOiPct" -> spurtOiPct = null;
        case "spurtPricePct" -> spurtPricePct = null;
        case "oiDivergencePct" -> oiDivergencePct = null;
        default -> throw new IllegalArgumentException("unknown Oi field: " + absent);
      }
    }
    return new ScalperGateContext.Oi(
        underlying, futures, sentimentPct, trendingPeMinusCePct, futuresBasis, ceOiDelta, peOiDelta,
        callPutDeltaImbalancePct, true, true, sentimentSlope, spurtOiPct, spurtPricePct,
        oiDivergencePct);
  }

  /** A live OI read on an ordinary bar: quadrants resolved, magnitudes present. */
  private static ScalperGateContext.Oi liveOi() {
    return oiWithout(null);
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
    DataHealthFlags health =
        DataHealthFlags.of(context("NIFTY 50", liveOi(), macroWithout("ivRank")), ORDINARY_DAY);

    assertThat(health.degraded()).isTrue();
    // SPECIFIC, not just "something is wrong": only the absent input is named.
    assertThat(health.flags()).containsExactly(DataHealthFlags.IV_RANK_ABSENT);
  }

  /** Breadth is the one macro input whose absence is a ZERO PAIR, not a null. */
  private static ScalperGateContext.Macro breadth(int advances, int declines) {
    return macro(
        new BigDecimal("12.5"), new BigDecimal("43.0"), new BigDecimal("11.2"), Boolean.TRUE,
        advances, declines, new BigDecimal("54.0"), new BigDecimal("12.1"), new BigDecimal("12.4"),
        new BigDecimal("0.3"), new BigDecimal("0.1"), new BigDecimal("-0.1"), new BigDecimal("2.5"),
        Boolean.TRUE, BigDecimal.ONE);
  }

  @Test
  void breadthIsAbsentOnlyWhenBothCountsAreZero() {
    assertThat(DataHealthFlags.of(context("NIFTY 50", liveOi(), breadth(0, 0)), ORDINARY_DAY).flags())
        .containsExactly(DataHealthFlags.BREADTH_ABSENT);

    // A decisively one-sided tape (every constituent down) is a MARKET STATE, not a dead read.
    assertThat(
            DataHealthFlags.of(context("NIFTY 50", liveOi(), breadth(0, 50)), ORDINARY_DAY)
                .degraded())
        .isFalse();
  }

  /**
   * The CHAIN-OI flag group — what the S24-shaped inert block reports off an expiry day. Excludes
   * {@code futures-basis-absent}: the inert shape KEEPS the price-derived basis, which is exactly
   * why that flag sits outside this group.
   */
  private static final String[] ALL_CHAIN_OI_FLAGS = {
    DataHealthFlags.OI_INERT,
    DataHealthFlags.FUTURES_QUADRANT_ABSENT,
    DataHealthFlags.UNDERLYING_QUADRANT_ABSENT,
    DataHealthFlags.SENTIMENT_ABSENT,
    DataHealthFlags.SENTIMENT_SLOPE_ABSENT,
    DataHealthFlags.OI_DELTA_ABSENT,
    DataHealthFlags.OI_DIVERGENCE_ABSENT,
    DataHealthFlags.OI_SPURT_ABSENT,
  };

  @Test
  void anInertOiBlockOnAnOrdinaryDayIsDegraded() {
    DataHealthFlags health =
        DataHealthFlags.of(context("NIFTY 50", inertOi(), healthyMacro()), ORDINARY_DAY);

    assertThat(health.oiSuppressed()).isFalse();
    assertThat(health.degraded()).isTrue();
    // The whole-block summary AND every field it emptied — the inert shape nulls them all.
    assertThat(health.flags()).containsExactlyInAnyOrder(ALL_CHAIN_OI_FLAGS);
  }

  @Test
  void aQuietButLiveOiBarIsNotInert() {
    // The trap a naive port of the canary's window probes falls into: per row, a zero spurt and a
    // NEUTRAL quadrant are ordinary. Measured live 2026-07-20..31, spurtPricePct = 0 on ~43% of
    // context-bearing rows on NON-expiry sessions. Only a WHOLLY empty OI block is inert.
    // ⚠️ The quadrants here are REAL states, not NEUTRAL. This fixture used to pass NEUTRAL to mean
    // "flat market", which review round 2 showed is wrong: OiQuadrant.NEUTRAL's javadoc is explicit
    // that it is the "snapshot unavailable" sentinel, NOT one of the four source states. A genuinely
    // quiet-but-live bar resolves its quadrants and merely reports zero magnitudes.
    ScalperGateContext.Oi quiet =
        new ScalperGateContext.Oi(
            OiQuadrant.LONG_BUILDUP, OiQuadrant.SHORT_COVERING, new BigDecimal("50.0"),
            BigDecimal.ZERO, new BigDecimal("31.5"), BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, false, false, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO);

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
    assertThat(sensex.flags()).containsExactlyInAnyOrder(ALL_CHAIN_OI_FLAGS);

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
  void suppressionStandsDownOnlyTheOiFlagsNotTheMacroOnes() {
    // The OI block is exempt on the root's expiry; the macro reads are not — MarketOiClient.macro()
    // is untouched by the S24 skip, so a dead breadth that day is still an outage.
    DataHealthFlags health =
        DataHealthFlags.of(
            context("NIFTY 50", inertOi(), breadth(0, 0)), nseOnlyMonthlyExpiry2026());

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
            DataHealthFlags.IV_PAIR_ABSENT,
            DataHealthFlags.IV_SLOPE_ABSENT,
            DataHealthFlags.PREMIUM_SKEW_ABSENT,
            DataHealthFlags.VIX_DIRECTION_ABSENT,
            DataHealthFlags.VIX_LEVEL_ABSENT,
            DataHealthFlags.FII_ABSENT,
            DataHealthFlags.FII_BIAS_ABSENT,
            DataHealthFlags.CONSTITUENT_BIAS_ABSENT,
            DataHealthFlags.DOW_ABSENT);
  }

  // =============================================================================================
  // THE COVERAGE RATCHET (cross-vendor review round 2)
  // =============================================================================================
  // A javadoc rule saying "add a flag when you add an input" is not enough — it already decayed
  // once: the first pass at "cover every absence-bearing input" still missed the futures QUADRANT
  // (OiQuadrant.NEUTRAL is the "snapshot unavailable" sentinel, not a market state) and the futures
  // BASIS. Both were false cleans, found in review rather than by the suite.
  //
  // So every record component of Macro and Oi must be EXPLICITLY classified below: either mapped to
  // a flag — and then proved to actually produce that flag when it alone is absent, and to sit in
  // the right S24 group — or exempted with a written reason. Reflection makes the list exhaustive,
  // so ADDING a component without deciding fails the build, and REMOVING one leaves a stale entry
  // that also fails. Deliberately NOT a source-scanner over ScalperGates: that would be brittle and
  // would fail for reasons unrelated to data health.

  /** How one gate-context record component is accounted for by {@link DataHealthFlags}. */
  private record Coverage(String flag, boolean s24Suppressed, String exemptReason) {

    /** Absence produces {@code flag}, judged on every row (macro, or the S24-surviving basis). */
    static Coverage flagged(String flag) {
      return new Coverage(flag, false, null);
    }

    /** Absence produces {@code flag}, but the whole chain-OI group is withheld under S24. */
    static Coverage suppressedFlag(String flag) {
      return new Coverage(flag, true, null);
    }

    /** Absence is deliberately NOT flagged; the reason is the documentation. */
    static Coverage exempt(String reason) {
      return new Coverage(null, false, reason);
    }
  }

  private static final Map<String, Coverage> MACRO_COVERAGE =
      Map.ofEntries(
          Map.entry("atmIv", Coverage.flagged(DataHealthFlags.ATM_IV_ABSENT)),
          Map.entry("ivRank", Coverage.flagged(DataHealthFlags.IV_RANK_ABSENT)),
          Map.entry("vixLevel", Coverage.flagged(DataHealthFlags.VIX_LEVEL_ABSENT)),
          Map.entry("vixRising", Coverage.flagged(DataHealthFlags.VIX_DIRECTION_ABSENT)),
          Map.entry("fiiLongPct", Coverage.flagged(DataHealthFlags.FII_ABSENT)),
          Map.entry("ceIvAvg6", Coverage.flagged(DataHealthFlags.IV_PAIR_ABSENT)),
          Map.entry("peIvAvg6", Coverage.flagged(DataHealthFlags.IV_PAIR_ABSENT)),
          Map.entry("constituentBias", Coverage.flagged(DataHealthFlags.CONSTITUENT_BIAS_ABSENT)),
          Map.entry("ceIvSlope", Coverage.flagged(DataHealthFlags.IV_SLOPE_ABSENT)),
          Map.entry("peIvSlope", Coverage.flagged(DataHealthFlags.IV_SLOPE_ABSENT)),
          Map.entry("premiumSkewPct", Coverage.flagged(DataHealthFlags.PREMIUM_SKEW_ABSENT)),
          Map.entry("dowUp", Coverage.flagged(DataHealthFlags.DOW_ABSENT)),
          Map.entry("fiiBiasSign", Coverage.flagged(DataHealthFlags.FII_BIAS_ABSENT)),
          // Primitive ints — individually un-nullable. Their ABSENCE is the {0,0} PAIR, which
          // breadth-absent covers and breadthIsAbsentOnlyWhenBothCountsAreZero pins.
          Map.entry("advances", Coverage.exempt("breadth pair -> " + DataHealthFlags.BREADTH_ABSENT)),
          Map.entry("declines", Coverage.exempt("breadth pair -> " + DataHealthFlags.BREADTH_ABSENT)));

  private static final Map<String, Coverage> OI_COVERAGE =
      Map.ofEntries(
          Map.entry("futures", Coverage.suppressedFlag(DataHealthFlags.FUTURES_QUADRANT_ABSENT)),
          Map.entry(
              "underlying", Coverage.suppressedFlag(DataHealthFlags.UNDERLYING_QUADRANT_ABSENT)),
          Map.entry("sentimentPct", Coverage.suppressedFlag(DataHealthFlags.SENTIMENT_ABSENT)),
          Map.entry(
              "sentimentSlope", Coverage.suppressedFlag(DataHealthFlags.SENTIMENT_SLOPE_ABSENT)),
          Map.entry("ceOiDelta", Coverage.suppressedFlag(DataHealthFlags.OI_DELTA_ABSENT)),
          Map.entry("peOiDelta", Coverage.suppressedFlag(DataHealthFlags.OI_DELTA_ABSENT)),
          Map.entry(
              "oiDivergencePct", Coverage.suppressedFlag(DataHealthFlags.OI_DIVERGENCE_ABSENT)),
          Map.entry("spurtOiPct", Coverage.suppressedFlag(DataHealthFlags.OI_SPURT_ABSENT)),
          Map.entry("spurtPricePct", Coverage.suppressedFlag(DataHealthFlags.OI_SPURT_ABSENT)),
          // NOT suppressed: price-derived, and MarketOiClient KEEPS it through the S24 skip, so a
          // null basis is a real failure even on the root's own expiry day.
          Map.entry("futuresBasis", Coverage.flagged(DataHealthFlags.FUTURES_BASIS_ABSENT)),
          // Review round 2 Major C: imbalancePct returns null IFF both deltas are present and
          // exactly zero (MarketOiClient:735-741) — an ordinary flat chain that flatOiStandAside
          // reads as its meaningful sentinel. That is a VALUE, not an absence; the only genuine
          // absence behind it is missing deltas, which oi-delta-absent already covers.
          Map.entry(
              "callPutDeltaImbalancePct",
              Coverage.exempt("null is the flat-chain sentinel; absence -> oi-delta-absent")),
          Map.entry(
              "trendingPeMinusCePct",
              Coverage.exempt("diagnostic-only; no dot or gate reads it")),
          // Primitive booleans — cannot be absent.
          Map.entry("crossedThisWindow", Coverage.exempt("primitive boolean, cannot be absent")),
          Map.entry("gapWidening", Coverage.exempt("primitive boolean, cannot be absent")));

  private static List<String> componentNames(Class<?> record) {
    return Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName).toList();
  }

  @Test
  void everyMacroComponentIsExplicitlyClassified() {
    assertThat(componentNames(ScalperGateContext.Macro.class))
        .as(
            "every Macro component needs a flag or a written exemption — a new input that nobody"
                + " classified is how a false clean gets shipped")
        .containsExactlyInAnyOrderElementsOf(MACRO_COVERAGE.keySet());
  }

  @Test
  void everyOiComponentIsExplicitlyClassified() {
    assertThat(componentNames(ScalperGateContext.Oi.class))
        .as("every Oi component needs a flag or a written exemption")
        .containsExactlyInAnyOrderElementsOf(OI_COVERAGE.keySet());
  }

  /**
   * The classification is not merely declared — each flagged macro input, absent ALONE with
   * everything else (including the whole OI block) healthy, must name exactly itself.
   */
  @Test
  void everyFlaggedMacroComponentProducesItsFlagWhenAbsentAlone() {
    MACRO_COVERAGE.forEach(
        (component, coverage) -> {
          if (coverage.flag() == null) {
            return;
          }
          DataHealthFlags health =
              DataHealthFlags.of(
                  context("NIFTY 50", liveOi(), macroWithout(component)), ORDINARY_DAY);
          assertThat(health.flags())
              .as("absent macro input '%s' names exactly %s", component, coverage.flag())
              .containsExactly(coverage.flag());
          assertThat(health.degraded()).as("'%s' marks the row degraded", component).isTrue();
        });
  }

  /** The same for each flagged OI input, with every MACRO input healthy. */
  @Test
  void everyFlaggedOiComponentProducesItsFlagWhenAbsentAlone() {
    OI_COVERAGE.forEach(
        (component, coverage) -> {
          if (coverage.flag() == null) {
            return;
          }
          DataHealthFlags health =
              DataHealthFlags.of(
                  context("NIFTY 50", oiWithout(component), healthyMacro()), ORDINARY_DAY);
          assertThat(health.flags())
              .as("absent OI input '%s' names exactly %s", component, coverage.flag())
              .containsExactly(coverage.flag());
          assertThat(health.degraded()).as("'%s' marks the row degraded", component).isTrue();
          assertThat(health.oiSuppressed()).isFalse();
        });
  }

  /**
   * S24 group membership, pinned per component. On the root's own monthly expiry the chain-OI flags
   * must vanish and the NON-suppressed ones must survive — the exact distinction Major B was about
   * ({@code futuresBasis} is price-derived and the skip keeps it, so withholding it would blind the
   * one Oi field the skip does not explain).
   */
  @Test
  void eachOiComponentIsWithheldUnderS24OnlyIfItsGroupSaysSo() {
    LocalDate expiry = nseOnlyMonthlyExpiry2026();
    OI_COVERAGE.forEach(
        (component, coverage) -> {
          if (coverage.flag() == null) {
            return;
          }
          DataHealthFlags health =
              DataHealthFlags.of(
                  context("NIFTY 50", oiWithout(component), healthyMacro()), expiry);
          assertThat(health.oiSuppressed()).isTrue();
          if (coverage.s24Suppressed()) {
            assertThat(health.flags())
                .as("chain-OI input '%s' is withheld on the root's expiry (S24)", component)
                .isEmpty();
          } else {
            assertThat(health.flags())
                .as(
                    "'%s' SURVIVES the S24 skip, so its absence is still a real failure on an"
                        + " expiry day",
                    component)
                .containsExactly(coverage.flag());
          }
        });
  }

  /** A null imbalance with both deltas PRESENT is the flat-chain sentinel — a value, not absence. */
  @Test
  void aFlatChainImbalanceSentinelIsNotAnAbsence() {
    DataHealthFlags health =
        DataHealthFlags.of(
            context("NIFTY 50", oiWithout("callPutDeltaImbalancePct"), healthyMacro()),
            ORDINARY_DAY);

    assertThat(health.flags())
        .as("a flat chain (both deltas present, imbalance null) is an ordinary market state")
        .isEmpty();
    assertThat(health.degraded()).isFalse();
  }

  /** Every OI flag is withheld together under S24 — never a subset (review Major 1). */
  @Test
  void s24WithholdsTheWholeOiGroupNotJustTheInertSummary() {
    LocalDate nseOnly = nseOnlyMonthlyExpiry2026();
    // A partially-degraded OI block (not the whole-block inert shape) on the expiring root: every
    // one of its flags must still be withheld, because the S24 skip is what emptied it.
    DataHealthFlags health =
        DataHealthFlags.of(
            context("NIFTY 50", oiWithout("sentimentPct"), healthyMacro()), nseOnly);

    assertThat(health.oiSuppressed()).isTrue();
    assertThat(health.flags()).isEmpty();
    assertThat(health.degraded()).isFalse();

    // …and a fully-null OI block on the expiring root contributes nothing FROM THE CHAIN GROUP —
    // but the basis is NOT in that group (it survives the S24 skip), so its absence still stands.
    // This is Major B stated as an assertion: the skip explains the chain read, never the basis.
    DataHealthFlags nullOi =
        DataHealthFlags.of(context("NIFTY 50", null, healthyMacro()), nseOnly);
    assertThat(nullOi.flags()).containsExactly(DataHealthFlags.FUTURES_BASIS_ABSENT);
    assertThat(nullOi.degraded()).isTrue();
  }

  @Test
  void aNullOiBlockFlagsEveryOiInputIncludingTheUnsuppressedBasis() {
    DataHealthFlags health =
        DataHealthFlags.of(context("NIFTY 50", null, healthyMacro()), ORDINARY_DAY);

    List<String> expected = new ArrayList<>(List.of(ALL_CHAIN_OI_FLAGS));
    expected.add(DataHealthFlags.FUTURES_BASIS_ABSENT);
    assertThat(health.flags()).containsExactlyInAnyOrderElementsOf(expected);
  }

  /**
   * A zero is a VALUE, not an absence — a conflicting FII read and a flat constituent push both
   * degrade their gate to pass, but neither is missing data. Flagging them would break this class's
   * one rule and re-introduce the noise the per-row design exists to avoid.
   */
  @Test
  void aZeroSignedMacroReadIsAValueNotAnAbsence() {
    ScalperGateContext.Macro zeroed =
        macro(
            new BigDecimal("12.5"), new BigDecimal("43.0"), new BigDecimal("11.2"), Boolean.TRUE,
            31, 18, new BigDecimal("54.0"), new BigDecimal("12.1"), new BigDecimal("12.4"),
            BigDecimal.ZERO, new BigDecimal("0.1"), new BigDecimal("-0.1"), new BigDecimal("2.5"),
            Boolean.TRUE, BigDecimal.ZERO);

    DataHealthFlags health = DataHealthFlags.of(context("NIFTY 50", liveOi(), zeroed), ORDINARY_DAY);

    assertThat(health.degraded()).isFalse();
    assertThat(health.flags()).isEmpty();
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
