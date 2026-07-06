package in.arthayantra.strategysignal.scalper;

import static in.arthayantra.black76.Black76.OptionType.CE;
import static in.arthayantra.black76.Black76.OptionType.PE;
import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategysignal.scalper.ScalperGateContext.Chart;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Macro;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Oi;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** §0B universal pre-flight gates — boundary coverage (master plan §12.1). */
class ScalperGatesTest {

  private static BigDecimal bd(String s) {
    return new BigDecimal(s);
  }

  @Test
  void confluenceFlippedAgainstOnlyOnAConfirmedOppositeSide() {
    assertThat(ScalperGates.confluenceFlippedAgainst("CE", "PE")).isTrue(); // bullish held, now bearish
    assertThat(ScalperGates.confluenceFlippedAgainst("PE", "CE")).isTrue(); // mirror
    assertThat(ScalperGates.confluenceFlippedAgainst("CE", "CE")).isFalse(); // same side, no flip
    assertThat(ScalperGates.confluenceFlippedAgainst("CE", "NEUTRAL")).isFalse(); // weak, not a flip
    assertThat(ScalperGates.confluenceFlippedAgainst("NEUTRAL", "PE")).isFalse(); // no held side
    assertThat(ScalperGates.confluenceFlippedAgainst("", "CE")).isFalse();
  }

  @Test
  void relativeVolumeFloorScalesTheMedianOfPriorBars() {
    // odd count: median of [10,20,30,40,50] = 30; k=1.5 -> 45. Order-independent (sorted internally).
    assertThat(
            ScalperGates.relativeVolumeFloor(
                List.of(bd("30"), bd("10"), bd("50"), bd("20"), bd("40")), bd("1.5"), 3, bd("125000")))
        .isEqualByComparingTo("45");
    // even count: median of [10,20,30,40] = 25; k=2 -> 50.
    assertThat(
            ScalperGates.relativeVolumeFloor(
                List.of(bd("40"), bd("10"), bd("30"), bd("20")), bd("2"), 3, bd("125000")))
        .isEqualByComparingTo("50");
  }

  @Test
  void relativeVolumeFloorFallsBackToFixedFloorBelowWarmup() {
    // 2 prior bars but minBars=3 -> the fixed §0B fallback, unchanged (never left floor-less at the open).
    assertThat(ScalperGates.relativeVolumeFloor(List.of(bd("10"), bd("20")), bd("1.5"), 3, bd("125000")))
        .isEqualByComparingTo("125000");
    assertThat(ScalperGates.relativeVolumeFloor(List.of(), bd("1.5"), 3, bd("125000")))
        .isEqualByComparingTo("125000");
    // minBars<=0 (armed misconfiguration) must still fall back on an empty window, never IOOBE.
    assertThat(ScalperGates.relativeVolumeFloor(List.of(), bd("1.5"), 0, bd("125000")))
        .isEqualByComparingTo("125000");
  }

  @Test
  void relativeVolumeFloorIgnoresNullBarsWhenCountingWarmup() {
    // 3 non-null of [null,10,null,20,30] meets minBars=3; median 20; k=1 -> 20 (NOT the fallback).
    var withNulls = new ArrayList<BigDecimal>();
    withNulls.add(null);
    withNulls.add(bd("10"));
    withNulls.add(null);
    withNulls.add(bd("20"));
    withNulls.add(bd("30"));
    assertThat(ScalperGates.relativeVolumeFloor(withNulls, bd("1"), 3, bd("125000")))
        .isEqualByComparingTo("20");
    // if nulls drop the real count below minBars, fall back to the fixed floor.
    var mostlyNull = new ArrayList<BigDecimal>();
    mostlyNull.add(null);
    mostlyNull.add(bd("10"));
    mostlyNull.add(null);
    assertThat(ScalperGates.relativeVolumeFloor(mostlyNull, bd("1"), 3, bd("125000")))
        .isEqualByComparingTo("125000");
  }

  @Test
  void timeWindowBlocksOpenNoiseMiddayAndLateEntry() {
    assertThat(ScalperGates.timeWindow(LocalTime.of(9, 44)).pass()).isFalse();
    assertThat(ScalperGates.timeWindow(LocalTime.of(9, 45)).pass()).isTrue();
    assertThat(ScalperGates.timeWindow(LocalTime.of(10, 30)).pass()).isTrue();
    assertThat(ScalperGates.timeWindow(LocalTime.of(11, 0)).pass()).isFalse(); // midday block start
    assertThat(ScalperGates.timeWindow(LocalTime.of(12, 59)).pass()).isFalse();
    assertThat(ScalperGates.timeWindow(LocalTime.of(13, 0)).pass()).isTrue(); // block ends
    assertThat(ScalperGates.timeWindow(LocalTime.of(15, 29)).pass()).isTrue();
    assertThat(ScalperGates.timeWindow(LocalTime.of(15, 30)).pass()).isFalse(); // no fresh entry
  }

  @Test
  void timeWindowRailOverridesHonourBoundsAndTheMiddayToggle() {
    // §5 part 2: an earlier floor (09:30), a later cap (15:25), and the midday block OFF — a 11:30 entry
    // that the default rail blocks now passes.
    assertThat(ScalperGates.timeWindow(LocalTime.of(9, 30), LocalTime.of(9, 30), LocalTime.of(15, 25), false).pass())
        .isTrue();
    assertThat(ScalperGates.timeWindow(LocalTime.of(11, 30), LocalTime.of(9, 30), LocalTime.of(15, 25), false).pass())
        .isTrue(); // midday block disabled
    assertThat(ScalperGates.timeWindow(LocalTime.of(11, 30), LocalTime.of(9, 30), LocalTime.of(15, 25), true).pass())
        .isFalse(); // midday block on → still blocked
    assertThat(ScalperGates.timeWindow(LocalTime.of(9, 29), LocalTime.of(9, 30), LocalTime.of(15, 25), false).pass())
        .isFalse(); // before the custom floor
    assertThat(ScalperGates.timeWindow(LocalTime.of(15, 25), LocalTime.of(9, 30), LocalTime.of(15, 25), false).pass())
        .isFalse(); // at the custom cap
  }

  @Test
  void volumeFloorOverrideReplacesThePerIndexDefault() {
    // null override ⇒ the per-index map (NIFTY 125k); a custom 80k floor admits a 90k bar the default rejects.
    assertThat(ScalperGates.volume("NIFTY 50", new BigDecimal("90000"), null).pass()).isFalse();
    assertThat(ScalperGates.volume("NIFTY 50", new BigDecimal("90000"), new BigDecimal("80000")).pass()).isTrue();
    assertThat(ScalperGates.volume("NIFTY 50", new BigDecimal("70000"), new BigDecimal("80000")).pass()).isFalse();
  }

  @Test
  void timeOfDayPreferencePassesInsideTheWindowAndSkipsOutside() {
    LocalTime from = LocalTime.of(10, 0);
    LocalTime to = LocalTime.of(13, 30);
    assertThat(ScalperGates.timeOfDayPreference(LocalTime.of(10, 0), from, to).pass()).isTrue(); // from inclusive
    assertThat(ScalperGates.timeOfDayPreference(LocalTime.of(11, 30), from, to).pass()).isTrue();
    assertThat(ScalperGates.timeOfDayPreference(LocalTime.of(9, 59), from, to).pass()).isFalse(); // before
    assertThat(ScalperGates.timeOfDayPreference(LocalTime.of(13, 30), from, to).pass()).isFalse(); // to exclusive
    assertThat(ScalperGates.timeOfDayPreference(LocalTime.of(14, 0), from, to).pass()).isFalse();
  }

  @Test
  void openingTickWindowOverloadBoundsOnFromInclusiveToExclusive() {
    // #9 Morning Trade: the opening-tick overload passes from `from` (inclusive) up to `to` (exclusive).
    assertThat(ScalperGates.timeWindow(LocalTime.of(9, 16), LocalTime.of(9, 16), LocalTime.of(9, 30)).pass())
        .isTrue(); // at `from` => pass
    assertThat(ScalperGates.timeWindow(LocalTime.of(9, 15), LocalTime.of(9, 16), LocalTime.of(9, 30)).pass())
        .isFalse(); // before `from` => fail
    assertThat(ScalperGates.timeWindow(LocalTime.of(9, 29), LocalTime.of(9, 16), LocalTime.of(9, 30)).pass())
        .isTrue();
    assertThat(ScalperGates.timeWindow(LocalTime.of(9, 30), LocalTime.of(9, 16), LocalTime.of(9, 30)).pass())
        .isFalse(); // at `to` => fail (exclusive)
    // the default no-arg window still rejects 09:16 (its "after 09:45" floor) — the overload is the
    // only path that admits the opening tick.
    assertThat(ScalperGates.timeWindow(LocalTime.of(9, 16)).pass()).isFalse();
  }

  @Test
  void volumeFloorByUnderlying() {
    assertThat(ScalperGates.volume("NIFTY 50", bd("124999")).pass()).isFalse();
    assertThat(ScalperGates.volume("NIFTY 50", bd("125000")).pass()).isTrue();
    assertThat(ScalperGates.volume("NIFTY BANK", bd("49999")).pass()).isFalse();
    assertThat(ScalperGates.volume("NIFTY BANK", bd("50000")).pass()).isTrue();
    assertThat(ScalperGates.volume("SENSEX", bd("50000")).pass()).isTrue(); // default floor 50k
  }

  @Test
  void rsiNoTradeBandAndDirectionalZones() {
    // 40-60 is no-trade for both sides
    assertThat(ScalperGates.rsiBand(bd("50"), CE).pass()).isFalse();
    assertThat(ScalperGates.rsiBand(bd("50"), PE).pass()).isFalse();
    // CE trades 60-80
    assertThat(ScalperGates.rsiBand(bd("60"), CE).pass()).isFalse();
    assertThat(ScalperGates.rsiBand(bd("61"), CE).pass()).isTrue();
    assertThat(ScalperGates.rsiBand(bd("79"), CE).pass()).isTrue();
    assertThat(ScalperGates.rsiBand(bd("80"), CE).pass()).isFalse(); // exhaustion
    // PE trades 20-40
    assertThat(ScalperGates.rsiBand(bd("39"), PE).pass()).isTrue();
    assertThat(ScalperGates.rsiBand(bd("40"), PE).pass()).isFalse();
    assertThat(ScalperGates.rsiBand(bd("21"), PE).pass()).isTrue();
    assertThat(ScalperGates.rsiBand(bd("20"), PE).pass()).isFalse(); // exhaustion
  }

  @Test
  void rsiS24BandShiftsBuyTo50To75AndSellTo25To40() {
    // S24 ratified band (tag rsi-s24-bands, owner U1/U2/U3): CE buy 50-75, PE sell 25-40, 40-50 no-trade.
    assertThat(ScalperGates.rsiS24Band(bd("50"), CE).pass()).isFalse(); // floor exclusive
    assertThat(ScalperGates.rsiS24Band(bd("51"), CE).pass()).isTrue();
    assertThat(ScalperGates.rsiS24Band(bd("74"), CE).pass()).isTrue();
    assertThat(ScalperGates.rsiS24Band(bd("75"), CE).pass()).isFalse();
    assertThat(ScalperGates.rsiS24Band(bd("44"), CE).pass()).isFalse(); // 40-50 no-trade
    // PE sell 25-40
    assertThat(ScalperGates.rsiS24Band(bd("40"), PE).pass()).isFalse();
    assertThat(ScalperGates.rsiS24Band(bd("39"), PE).pass()).isTrue();
    assertThat(ScalperGates.rsiS24Band(bd("26"), PE).pass()).isTrue();
    assertThat(ScalperGates.rsiS24Band(bd("25"), PE).pass()).isFalse();
    // the S24/legacy divergence: RSI 55 CE trades under S24 but is in the legacy 40-60 no-trade gap.
    assertThat(ScalperGates.rsiS24Band(bd("55"), CE).pass()).isTrue();
    assertThat(ScalperGates.rsiBand(bd("55"), CE).pass()).isFalse();
    assertThat(ScalperGates.rsiS24Band(null, CE).pass()).isFalse(); // null rsi -> fail
  }

  @Test
  void rsiBandCustomHonoursSuppliedBounds() {
    // E5 §3.5 per-strategy band override: bounds come from the YAML (here CE 50-75, PE 25-50).
    assertThat(ScalperGates.rsiBandCustom(bd("50"), CE, bd("50"), bd("75"), bd("25"), bd("50")).pass())
        .isFalse(); // floor exclusive
    assertThat(ScalperGates.rsiBandCustom(bd("55"), CE, bd("50"), bd("75"), bd("25"), bd("50")).pass())
        .isTrue(); // 55 admitted by the custom band but rejected by the shared 60-80
    assertThat(ScalperGates.rsiBand(bd("55"), CE).pass()).isFalse(); // proves the override widens
    assertThat(ScalperGates.rsiBandCustom(bd("78"), CE, bd("50"), bd("75"), bd("25"), bd("50")).pass())
        .isFalse(); // above the custom CE cap
    assertThat(ScalperGates.rsiBandCustom(bd("30"), PE, bd("50"), bd("75"), bd("25"), bd("50")).pass())
        .isTrue();
    assertThat(ScalperGates.rsiBandCustom(bd("24"), PE, bd("50"), bd("75"), bd("25"), bd("50")).pass())
        .isFalse(); // below the custom PE floor
    assertThat(ScalperGates.rsiBandCustom(null, CE, bd("50"), bd("75"), bd("25"), bd("50")).pass())
        .isFalse(); // null rsi -> fail
  }

  @Test
  void rsiRecoveryRequiresReboundToFortyAfterAnOversoldTrough() {
    // CE: a recent oversold trough (18 <= 20) AND current recovered (42 >= 40) -> pass.
    assertThat(ScalperGates.rsiRecovery(bd("18"), bd("42"), CE, bd("20"), bd("40")).pass()).isTrue();
    // trough present but still recovering (35 < 40) -> fail (wait for the rebound).
    assertThat(ScalperGates.rsiRecovery(bd("18"), bd("35"), CE, bd("20"), bd("40")).pass()).isFalse();
    // no recent oversold trough (min 30 > 20) -> inert pass (nothing to sequence).
    assertThat(ScalperGates.rsiRecovery(bd("30"), bd("35"), CE, bd("20"), bd("40")).pass()).isTrue();
    // empty window (null trough) -> inert pass.
    assertThat(ScalperGates.rsiRecovery(null, bd("35"), CE, bd("20"), bd("40")).pass()).isTrue();
    // trough present, current rsi null -> fail (data required once a trough exists).
    assertThat(ScalperGates.rsiRecovery(bd("18"), null, CE, bd("20"), bd("40")).pass()).isFalse();
    // PE side -> inert (the post-vertical-rise mirror is unreachable until a short variant).
    assertThat(ScalperGates.rsiRecovery(bd("18"), bd("10"), PE, bd("20"), bd("40")).pass()).isTrue();
  }

  @Test
  void rsiAboveIsTheRelaxedOpenHighFloor() {
    // #2 (open-high-low) gates on the source's "RSI >50" floor, not the 60-80 band.
    assertThat(ScalperGates.rsiAbove(bd("50"), bd("50")).pass()).isFalse(); // exactly 50 -> not >
    assertThat(ScalperGates.rsiAbove(bd("50.1"), bd("50")).pass()).isTrue();
    assertThat(ScalperGates.rsiAbove(bd("85"), bd("50")).pass()).isTrue(); // no upper cap for #2
    assertThat(ScalperGates.rsiAbove(null, bd("50")).pass()).isFalse(); // null rsi -> fail
  }

  @Test
  void rsiCoolOffWaitsForACooledPullbackAfterAHotBar() {
    // CE: prior bar hot (82) → require a cooled (<=75) RED pullback candle this bar.
    assertThat(ScalperGates.rsiCoolOff(bd("82"), bd("72"), true, CE).pass()).isTrue(); // cooled + red
    assertThat(ScalperGates.rsiCoolOff(bd("82"), bd("72"), false, CE).pass()).isFalse(); // green = no pullback
    assertThat(ScalperGates.rsiCoolOff(bd("82"), bd("78"), true, CE).pass()).isFalse(); // 78 not cooled
    // prior NOT hot (65) → inert PASS regardless of the candle (never blocks a normal entry).
    assertThat(ScalperGates.rsiCoolOff(bd("65"), bd("72"), false, CE).pass()).isTrue();
    // PE mirror: prior oversold (18) → require a warmed (>=25) GREEN pullback.
    assertThat(ScalperGates.rsiCoolOff(bd("18"), bd("28"), true, PE).pass()).isTrue();
    assertThat(ScalperGates.rsiCoolOff(bd("18"), bd("28"), false, PE).pass()).isFalse();
    assertThat(ScalperGates.rsiCoolOff(bd("35"), bd("28"), false, PE).pass()).isTrue(); // prior not oversold
    // null rsi on either bar → fail (data required when armed).
    assertThat(ScalperGates.rsiCoolOff(null, bd("72"), true, CE).pass()).isFalse();
    assertThat(ScalperGates.rsiCoolOff(bd("82"), null, true, CE).pass()).isFalse();
  }

  @Test
  void indicatorAlignmentNeedsAllOnTheCorrectSide() {
    Chart bull = new Chart(bd("100"), bd("99"), bd("98"), bd("97"), 1, bd("65"), bd("60000"));
    assertThat(ScalperGates.indicatorAlignment(bull, CE).pass()).isTrue();
    // one operand on the wrong side (PSAR above price) breaks CE alignment
    Chart psarAbove = new Chart(bd("100"), bd("99"), bd("98"), bd("101"), 1, bd("65"), bd("60000"));
    assertThat(ScalperGates.indicatorAlignment(psarAbove, CE).pass()).isFalse();
    // supertrend pointing the wrong way breaks it too
    Chart stDown = new Chart(bd("100"), bd("99"), bd("98"), bd("97"), -1, bd("65"), bd("60000"));
    assertThat(ScalperGates.indicatorAlignment(stDown, CE).pass()).isFalse();

    Chart bear = new Chart(bd("100"), bd("101"), bd("102"), bd("103"), -1, bd("35"), bd("60000"));
    assertThat(ScalperGates.indicatorAlignment(bear, PE).pass()).isTrue();
  }

  @Test
  void oiQuadrantMatchesSide() {
    assertThat(ScalperGates.oiQuadrant(oi(OiQuadrant.LONG_BUILDUP), CE).pass()).isTrue();
    assertThat(ScalperGates.oiQuadrant(oi(OiQuadrant.SHORT_COVERING), CE).pass()).isTrue();
    assertThat(ScalperGates.oiQuadrant(oi(OiQuadrant.SHORT_BUILDUP), CE).pass()).isFalse();
    assertThat(ScalperGates.oiQuadrant(oi(OiQuadrant.SHORT_BUILDUP), PE).pass()).isTrue();
    assertThat(ScalperGates.oiQuadrant(oi(OiQuadrant.LONG_UNWINDING), PE).pass()).isTrue();
    assertThat(ScalperGates.oiQuadrant(oi(OiQuadrant.LONG_BUILDUP), PE).pass()).isFalse();
  }

  @Test
  void breadthThirtyTwoCutoff() {
    assertThat(ScalperGates.breadth(macro(33, 10, null), CE).pass()).isTrue();
    assertThat(ScalperGates.breadth(macro(32, 10, null), CE).pass()).isFalse();
    assertThat(ScalperGates.breadth(macro(10, 33, null), PE).pass()).isTrue();
    assertThat(ScalperGates.breadth(macro(10, 32, null), PE).pass()).isFalse();
  }

  @Test
  void vixDirectionFavoursSideAndUnknownNeverBlocks() {
    assertThat(ScalperGates.vix(macro(0, 0, Boolean.FALSE), CE).pass()).isTrue(); // falling -> CE
    assertThat(ScalperGates.vix(macro(0, 0, Boolean.TRUE), CE).pass()).isFalse();
    assertThat(ScalperGates.vix(macro(0, 0, Boolean.TRUE), PE).pass()).isTrue(); // rising -> PE
    assertThat(ScalperGates.vix(macro(0, 0, null), CE).pass()).isTrue(); // unknown -> pass
  }

  @Test
  void futuresBasisPremiumBullDiscountBear() {
    assertThat(ScalperGates.futuresBasis(basis(bd("5")), CE).pass()).isTrue();
    assertThat(ScalperGates.futuresBasis(basis(bd("-5")), CE).pass()).isFalse();
    assertThat(ScalperGates.futuresBasis(basis(bd("-5")), PE).pass()).isTrue();
    assertThat(ScalperGates.futuresBasis(basis(null), PE).pass()).isTrue(); // unavailable -> pass
  }

  @Test
  void callPutDeltaFilterPassesAtFloorFailsBelowAndDegradesOnNull() {
    BigDecimal floor = bd("50");
    assertThat(ScalperGates.callPutDeltaFilter(imbalance(bd("50")), floor).pass()).isTrue(); // == floor
    assertThat(ScalperGates.callPutDeltaFilter(imbalance(bd("75")), floor).pass()).isTrue();
    assertThat(ScalperGates.callPutDeltaFilter(imbalance(bd("49.9")), floor).pass()).isFalse();
    // null imbalance (data unavailable / flat-OI caveat) DEGRADES to pass — never blocks
    assertThat(ScalperGates.callPutDeltaFilter(imbalance(null), floor).pass()).isTrue();
  }

  @Test
  void oiCrossRequiredNeedsACompletedCrossFavouringTheSide() {
    // CE: a real cross with peΔ>0 && ceΔ<0 → pass; PE wants the mirror.
    assertThat(ScalperGates.oiCrossRequired(crossDeltas(true, bd("-100"), bd("200")), CE).pass()).isTrue();
    assertThat(ScalperGates.oiCrossRequired(crossDeltas(true, bd("200"), bd("-100")), PE).pass()).isTrue();
    // a stalled cross (crossedThisWindow=false) is rejected even with the favouring deltas (P16).
    assertThat(ScalperGates.oiCrossRequired(crossDeltas(false, bd("-100"), bd("200")), CE).pass()).isFalse();
    // deltas favouring the WRONG side fail.
    assertThat(ScalperGates.oiCrossRequired(crossDeltas(true, bd("200"), bd("-100")), CE).pass()).isFalse();
    // null deltas fail-closed (a missing derivation cannot pass a fresh-cross requirement).
    assertThat(ScalperGates.oiCrossRequired(crossDeltas(true, null, null), CE).pass()).isFalse();
  }

  @Test
  void oiSlopeAgreeRequiresLevelAndSlopeOnTheSameSide() {
    // CE wants both the sentiment level and its slope positive; PE both negative.
    assertThat(ScalperGates.oiSlopeAgree(sentiment(bd("10"), bd("3")), CE).pass()).isTrue();
    assertThat(ScalperGates.oiSlopeAgree(sentiment(bd("-10"), bd("-3")), PE).pass()).isTrue();
    // level and slope disagree → fail.
    assertThat(ScalperGates.oiSlopeAgree(sentiment(bd("10"), bd("-3")), CE).pass()).isFalse();
    // a positive pair does not confirm a PE side.
    assertThat(ScalperGates.oiSlopeAgree(sentiment(bd("10"), bd("3")), PE).pass()).isFalse();
    // null slope/level fail-closed (the conjunction is required).
    assertThat(ScalperGates.oiSlopeAgree(sentiment(bd("10"), null), CE).pass()).isFalse();
    assertThat(ScalperGates.oiSlopeAgree(sentiment(null, bd("3")), CE).pass()).isFalse();
  }

  @Test
  void oiDivergenceMagnitudeNeedsTheGapAndPriceImpulseInTheSideDirection() {
    BigDecimal minDiv = ScalperGates.OI_DIVERGENCE_MIN_PCT; // 20
    BigDecimal minPx = ScalperGates.PRICE_IMPULSE_MIN_PCT; // 50
    // CE: a bullish divergence (+div >= 20 = PE-heavy) + an UP price impulse (+px >= 50) confirms.
    assertThat(ScalperGates.oiDivergenceMagnitude(divergence(bd("25"), bd("60")), minDiv, minPx, CE).pass()).isTrue();
    // PE: the mirror — a bearish divergence (-div <= -20 = CE-heavy) + a DOWN impulse (-px <= -50).
    assertThat(ScalperGates.oiDivergenceMagnitude(divergence(bd("-25"), bd("-60")), minDiv, minPx, PE).pass()).isTrue();
    // a divergence OPPOSING the side is rejected (a bearish -div on a CE side; a bullish +div on a PE side).
    assertThat(ScalperGates.oiDivergenceMagnitude(divergence(bd("-25"), bd("-60")), minDiv, minPx, CE).pass()).isFalse();
    assertThat(ScalperGates.oiDivergenceMagnitude(divergence(bd("25"), bd("60")), minDiv, minPx, PE).pass()).isFalse();
    // a weak divergence (< 20) fails; a weak price impulse (< 50) fails.
    assertThat(ScalperGates.oiDivergenceMagnitude(divergence(bd("10"), bd("60")), minDiv, minPx, CE).pass()).isFalse();
    assertThat(ScalperGates.oiDivergenceMagnitude(divergence(bd("25"), bd("30")), minDiv, minPx, CE).pass()).isFalse();
    // null divergence or null price impulse fail-closed.
    assertThat(ScalperGates.oiDivergenceMagnitude(divergence(null, bd("60")), minDiv, minPx, CE).pass()).isFalse();
    assertThat(ScalperGates.oiDivergenceMagnitude(divergence(bd("25"), null), minDiv, minPx, CE).pass()).isFalse();
  }

  @Test
  void oi60mAgreeRequiresTheBroaderTrendToFavourTheSideAndFailsOpenOnUnknown() {
    // dir > 0 = PE building faster (put-writing = bullish) → confirms a CE, opposes a PE.
    assertThat(ScalperGates.oi60mAgree(1, CE).pass()).isTrue();
    assertThat(ScalperGates.oi60mAgree(1, PE).pass()).isFalse();
    // dir < 0 = CE building faster (bearish) → confirms a PE, opposes a CE.
    assertThat(ScalperGates.oi60mAgree(-1, PE).pass()).isTrue();
    assertThat(ScalperGates.oi60mAgree(-1, CE).pass()).isFalse();
    // dir == 0 (unknown / flat 60m trend) fail-OPENs for either side (a broader-trend CONFIRMATION,
    // not a hard fresh-cross requirement).
    assertThat(ScalperGates.oi60mAgree(0, CE).pass()).isTrue();
    assertThat(ScalperGates.oi60mAgree(0, PE).pass()).isTrue();
  }

  @Test
  void ivBuyerCapBlocksBuyingIntoRichSideIvButDegradesOnNull() {
    BigDecimal cap = ScalperGates.IV_BUYER_CAP; // 0.40 fraction = "IV 40"
    // CE: buyable when the CE 6-strike IV <= cap, blocked above it.
    assertThat(ScalperGates.ivBuyerCap(macroIv(bd("0.30"), bd("0.20")), CE, cap).pass()).isTrue();
    assertThat(ScalperGates.ivBuyerCap(macroIv(bd("0.50"), bd("0.20")), CE, cap).pass()).isFalse();
    // PE reads the PE-side IV.
    assertThat(ScalperGates.ivBuyerCap(macroIv(bd("0.20"), bd("0.50")), PE, cap).pass()).isFalse();
    // a null side IV degrades to pass (a risk veto never blocks on missing data).
    assertThat(ScalperGates.ivBuyerCap(macroIv(null, bd("0.20")), CE, cap).pass()).isTrue();
  }

  @Test
  void pctPriceMoveNeedsAOnePercentSessionMoveInTheSideDirection() {
    BigDecimal floor = bd("1.0");
    // CE: +2.04% (open 98 -> close 100) passes; +0.5% fails; a DOWN move on a CE side fails.
    assertThat(ScalperGates.pctPriceMove(bd("100"), bd("98"), floor, CE).pass()).isTrue();
    assertThat(ScalperGates.pctPriceMove(bd("100"), bd("99.5"), floor, CE).pass()).isFalse();
    assertThat(ScalperGates.pctPriceMove(bd("97"), bd("100"), floor, CE).pass()).isFalse();
    // PE: -2% (100 -> 98) passes; -0.5% fails.
    assertThat(ScalperGates.pctPriceMove(bd("98"), bd("100"), floor, PE).pass()).isTrue();
    assertThat(ScalperGates.pctPriceMove(bd("99.5"), bd("100"), floor, PE).pass()).isFalse();
    // null close / null or zero session-open degrade to pass (never block on missing data).
    assertThat(ScalperGates.pctPriceMove(null, bd("100"), floor, CE).pass()).isTrue();
    assertThat(ScalperGates.pctPriceMove(bd("100"), null, floor, CE).pass()).isTrue();
    assertThat(ScalperGates.pctPriceMove(bd("100"), bd("0"), floor, CE).pass()).isTrue();
  }

  @Test
  void rsiHigherTfCapBlocksAnOverboughtCeAndAnOversoldPe() {
    BigDecimal ceCap = bd("75");
    BigDecimal peFloor = bd("25");
    // CE: below the cap passes, at/above it blocks.
    assertThat(ScalperGates.rsiHigherTfCap(bd("70"), CE, ceCap, peFloor).pass()).isTrue();
    assertThat(ScalperGates.rsiHigherTfCap(bd("78"), CE, ceCap, peFloor).pass()).isFalse();
    assertThat(ScalperGates.rsiHigherTfCap(bd("75"), CE, ceCap, peFloor).pass()).isFalse(); // boundary
    // PE: above the floor passes, at/below it blocks (oversold).
    assertThat(ScalperGates.rsiHigherTfCap(bd("30"), PE, ceCap, peFloor).pass()).isTrue();
    assertThat(ScalperGates.rsiHigherTfCap(bd("22"), PE, ceCap, peFloor).pass()).isFalse();
    // null fails closed (the higher-TF data is required when the strategy opts into the cap).
    assertThat(ScalperGates.rsiHigherTfCap(null, CE, ceCap, peFloor).pass()).isFalse();
  }

  @Test
  void supertrend15mAlignConfirmsTheSideAndFailOpensOnUnknown() {
    assertThat(ScalperGates.supertrend15mAlign(1, CE).pass()).isTrue(); // 15m up confirms CE
    assertThat(ScalperGates.supertrend15mAlign(-1, CE).pass()).isFalse(); // 15m down opposes CE
    assertThat(ScalperGates.supertrend15mAlign(-1, PE).pass()).isTrue(); // 15m down confirms PE
    assertThat(ScalperGates.supertrend15mAlign(1, PE).pass()).isFalse();
    // dir 0 (unwarmed / mid-flip) fail-OPENs on either side — never blocks a confirmed 3m entry.
    assertThat(ScalperGates.supertrend15mAlign(0, CE).pass()).isTrue();
    assertThat(ScalperGates.supertrend15mAlign(0, PE).pass()).isTrue();
  }

  @Test
  void psarDurableNeedsAWideEnoughGapAndFailOpensOnNull() {
    // |close-psar|/close: 1% gap (>= 0.05 floor) is durable; 0.01% is too tight; the abs handles either side.
    assertThat(ScalperGates.psarDurable(bd("100"), bd("99")).pass()).isTrue();
    assertThat(ScalperGates.psarDurable(bd("100"), bd("99.99")).pass()).isFalse();
    assertThat(ScalperGates.psarDurable(bd("100"), bd("101")).pass()).isTrue(); // 1% above is durable too
    // a null close/psar fail-OPENs (durability is a refinement, not a hard data dependency).
    assertThat(ScalperGates.psarDurable(null, bd("99")).pass()).isTrue();
    assertThat(ScalperGates.psarDurable(bd("100"), null).pass()).isTrue();
  }

  @Test
  void risingVolumeNeedsTheDeployBarToExceedThePrior() {
    assertThat(ScalperGates.risingVolume(150_000L, 100_000L).pass()).isTrue();
    assertThat(ScalperGates.risingVolume(100_000L, 100_000L).pass()).isFalse(); // equal is not rising
    assertThat(ScalperGates.risingVolume(80_000L, 100_000L).pass()).isFalse();
  }

  @Test
  void constituentRequiresHeavyweightPushNotToOppose() {
    // CE wants the net constituent push positive; PE negative; 0 (flat) and null degrade to pass.
    assertThat(ScalperGates.constituent(macroConstituent(bd("0.5")), CE).pass()).isTrue();
    assertThat(ScalperGates.constituent(macroConstituent(bd("-0.5")), CE).pass()).isFalse();
    assertThat(ScalperGates.constituent(macroConstituent(bd("-0.5")), PE).pass()).isTrue();
    assertThat(ScalperGates.constituent(macroConstituent(bd("0.5")), PE).pass()).isFalse();
    assertThat(ScalperGates.constituent(macroConstituent(bd("0")), CE).pass()).isTrue(); // flat never blocks
    assertThat(ScalperGates.constituent(macroConstituent(null), CE).pass()).isTrue(); // null -> pass
  }

  @Test
  void fiiBiasRequiresTheFlowNotToOpposeTheSide() {
    // CE wants FII net long (>=50); PE net short (<=50); 50 is neutral (both pass); null degrades to pass.
    assertThat(ScalperGates.fiiBias(macroFii(bd("60")), CE).pass()).isTrue();
    assertThat(ScalperGates.fiiBias(macroFii(bd("40")), CE).pass()).isFalse();
    assertThat(ScalperGates.fiiBias(macroFii(bd("40")), PE).pass()).isTrue();
    assertThat(ScalperGates.fiiBias(macroFii(bd("60")), PE).pass()).isFalse();
    assertThat(ScalperGates.fiiBias(macroFii(bd("50")), CE).pass()).isTrue(); // neutral never blocks
    assertThat(ScalperGates.fiiBias(macroFii(bd("50")), PE).pass()).isTrue();
    assertThat(ScalperGates.fiiBias(macroFii(null), CE).pass()).isTrue(); // null -> pass
  }

  @Test
  void fiiDiiGateRequiresTheParticipantBiasNotToOppose() {
    // E3 §3.3: the combined FII participant bias sign (+1 bull / -1 bear / 0 neutral) must not oppose.
    assertThat(ScalperGates.fii(macroFiiBias(bd("1")), CE).pass()).isTrue(); // bull supports CE
    assertThat(ScalperGates.fii(macroFiiBias(bd("1")), PE).pass()).isFalse(); // bull opposes PE
    assertThat(ScalperGates.fii(macroFiiBias(bd("-1")), PE).pass()).isTrue(); // bear supports PE
    assertThat(ScalperGates.fii(macroFiiBias(bd("-1")), CE).pass()).isFalse(); // bear opposes CE
    assertThat(ScalperGates.fii(macroFiiBias(bd("0")), CE).pass()).isTrue(); // neutral never blocks
    assertThat(ScalperGates.fii(macroFiiBias(null), CE).pass()).isTrue(); // null -> pass
  }

  @Test
  void volumePumpRequiresAFloorClearingDirectionalCandle() {
    // CE: a floor-clearing GREEN candle (close>open) confirms; a RED one (close<open) blocks.
    assertThat(ScalperGates.volumePump(bd("110"), bd("100"), bd("130000"), "NIFTY 50", CE).pass()).isTrue();
    assertThat(ScalperGates.volumePump(bd("100"), bd("110"), bd("130000"), "NIFTY 50", CE).pass()).isFalse();
    // a below-floor candle blocks even when green (the floor rail also catches it separately).
    assertThat(ScalperGates.volumePump(bd("110"), bd("100"), bd("100000"), "NIFTY 50", CE).pass()).isFalse();
    // PE wants a floor-clearing RED candle.
    assertThat(ScalperGates.volumePump(bd("100"), bd("110"), bd("130000"), "NIFTY 50", PE).pass()).isTrue();
    // a null open/close degrades to pass.
    assertThat(ScalperGates.volumePump(null, bd("100"), bd("130000"), "NIFTY 50", CE).pass()).isTrue();
  }

  @Test
  void flatOiStandAsideBlocksANullImbalanceButPassesAPresentOne() {
    // the deliberate inverse of callPutDeltaFilter's fail-open: a present imbalance passes...
    assertThat(ScalperGates.flatOiStandAside(imbalance(bd("10"))).pass()).isTrue();
    // ...and a null/flat imbalance STANDS ASIDE (blocks).
    assertThat(ScalperGates.flatOiStandAside(imbalance(null)).pass()).isFalse();
  }

  @Test
  void oiWallClearBlocksAnEntryIntoTheDominantOiWall() {
    // CE: clear when spot is below the max-CE-OI wall (resistance); blocked at/above it.
    assertThat(ScalperGates.oiWallClear(bd("20100"), bd("19900"), bd("20000"), CE).pass()).isTrue();
    assertThat(ScalperGates.oiWallClear(bd("20000"), bd("19900"), bd("20000"), CE).pass()).isFalse();
    // PE: clear when spot is above the max-PE-OI wall (support); blocked at/below it.
    assertThat(ScalperGates.oiWallClear(bd("20100"), bd("19900"), bd("20000"), PE).pass()).isTrue();
    assertThat(ScalperGates.oiWallClear(bd("20100"), bd("20000"), bd("20000"), PE).pass()).isFalse();
    // a null wall or null spot degrades to pass (fail-open — a missing ladder never blocks).
    assertThat(ScalperGates.oiWallClear(null, null, bd("20000"), CE).pass()).isTrue();
    assertThat(ScalperGates.oiWallClear(bd("20100"), bd("19900"), null, CE).pass()).isTrue();
  }

  @Test
  void indicatorDistanceBlocksWhenPriceRanFarFromTheWholeCluster() {
    BigDecimal max = bd("0.015"); // 1.5%
    // the nearest indicator (vwap, 0.5% away) is within the band -> not overextended -> pass.
    Chart near = new Chart(bd("100"), bd("99.5"), bd("130"), bd("70"), 1, bd("65"), bd("60000"));
    assertThat(ScalperGates.indicatorDistance(near, max).pass()).isTrue();
    // every indicator is > 1.5% away (nearest is vwap at 3%) -> overextended -> block.
    Chart farFromAll = new Chart(bd("100"), bd("97"), bd("130"), bd("70"), 1, bd("65"), bd("60000"));
    assertThat(ScalperGates.indicatorDistance(farFromAll, max).pass()).isFalse();
    // null close degrades to pass (never blocks on missing data).
    Chart noClose = new Chart(null, bd("97"), bd("130"), bd("70"), 1, bd("65"), bd("60000"));
    assertThat(ScalperGates.indicatorDistance(noClose, max).pass()).isTrue();
    // a fully-absent cluster degrades to pass.
    Chart noCluster = new Chart(bd("100"), null, null, null, 1, bd("65"), bd("60000"));
    assertThat(ScalperGates.indicatorDistance(noCluster, max).pass()).isTrue();
  }

  @Test
  void vwapDistancePassesNearVwapAndSkipsWhenExtended() {
    BigDecimal max = ScalperGates.VWAP_DISTANCE_MAX_FRAC; // 0.4%
    BigDecimal minOff = ScalperGates.VWAP_DISTANCE_MIN_FRAC; // 0 -> the min (pin) clause is inert
    // a pullback 0.3% above VWAP is within the band -> pass; an extended 1% chase -> skip.
    assertThat(ScalperGates.vwapDistance(bd("100"), bd("99.7"), minOff, max).pass()).isTrue();
    assertThat(ScalperGates.vwapDistance(bd("100"), bd("99"), minOff, max).pass()).isFalse();
    // symmetric below VWAP (the PE side): 0.3% below passes, 1% below skips.
    assertThat(ScalperGates.vwapDistance(bd("100"), bd("100.3"), minOff, max).pass()).isTrue();
    assertThat(ScalperGates.vwapDistance(bd("100"), bd("101"), minOff, max).pass()).isFalse();
    // null / non-positive operands degrade to pass (fail-open, never block on missing data).
    assertThat(ScalperGates.vwapDistance(null, bd("99"), minOff, max).pass()).isTrue();
    assertThat(ScalperGates.vwapDistance(bd("100"), null, minOff, max).pass()).isTrue();
    assertThat(ScalperGates.vwapDistance(bd("0"), bd("99"), minOff, max).pass()).isTrue();
    // when armed, the min clause skips a too-close (VWAP-pinned) entry; min 0 never fires.
    assertThat(ScalperGates.vwapDistance(bd("100"), bd("99.95"), bd("0.001"), max).pass()).isFalse();
    assertThat(ScalperGates.vwapDistance(bd("100"), bd("99.95"), minOff, max).pass()).isTrue();
  }

  @Test
  void divergenceVolumeNeedsTheHeavyweightBarRegardlessOfIndex() {
    assertThat(ScalperGates.divergenceVolume(bd("125000")).pass()).isTrue();
    assertThat(ScalperGates.divergenceVolume(bd("124999")).pass()).isFalse();
    assertThat(ScalperGates.divergenceVolume(null).pass()).isFalse(); // confirm required -> null fails
  }

  @Test
  void overboughtDeferBlocksTheExhaustionExtremePerSide() {
    // CE defers at >=85; PE defers at <=15; just inside the cap trades.
    assertThat(ScalperGates.overboughtDefer(bd("84"), CE).pass()).isTrue();
    assertThat(ScalperGates.overboughtDefer(bd("85"), CE).pass()).isFalse();
    assertThat(ScalperGates.overboughtDefer(bd("16"), PE).pass()).isTrue();
    assertThat(ScalperGates.overboughtDefer(bd("15"), PE).pass()).isFalse();
    // a null RSI degrades to pass (the veto never blocks on missing data).
    assertThat(ScalperGates.overboughtDefer(null, CE).pass()).isTrue();
  }

  @Test
  void directionalChangeRequiresAnOiTiltCrossThisWindow() {
    assertThat(ScalperGates.directionalChange(crossed(true)).pass()).isTrue();
    assertThat(ScalperGates.directionalChange(crossed(false)).pass()).isFalse();
    assertThat(ScalperGates.directionalChange(null).pass()).isFalse(); // precondition unmet
  }

  @Test
  void gapSizeSideSuppressesThePutSideOnlyOnALargeGapDown() {
    BigDecimal cap = bd("300");
    GapState.Gap bigDown = new GapState.Gap(true, false, bd("350"), bd("20000"), false);
    // a >=300pt gap-down blocks the PE (put-buy) side...
    assertThat(ScalperGates.gapSizeSide(bigDown, PE, cap).pass()).isFalse();
    // ...but never the CE side.
    assertThat(ScalperGates.gapSizeSide(bigDown, CE, cap).pass()).isTrue();
    // a smaller gap-down (250 < 300) does not suppress.
    GapState.Gap smallDown = new GapState.Gap(true, false, bd("250"), bd("20000"), false);
    assertThat(ScalperGates.gapSizeSide(smallDown, PE, cap).pass()).isTrue();
    // a large gap-UP does not suppress the put side (only gap-down -> no-put).
    GapState.Gap bigUp = new GapState.Gap(true, true, bd("350"), bd("20000"), false);
    assertThat(ScalperGates.gapSizeSide(bigUp, PE, cap).pass()).isTrue();
    // a null/absent gap passes.
    assertThat(ScalperGates.gapSizeSide(null, PE, cap).pass()).isTrue();
  }

  @Test
  void s24TradeWindowBoundsAre0945To1430() {
    // the s24-trade-window tag reuses the 3-arg timeWindow overload: 09:45 inclusive, 14:30 exclusive,
    // no 11:00-13:00 midday block (a 12:00 entry that the default window blocks is admitted here).
    assertThat(ScalperGates.timeWindow(LocalTime.of(9, 44), ScalperGates.NO_TRADE_BEFORE, ScalperGates.S24_WINDOW_TO).pass())
        .isFalse();
    assertThat(ScalperGates.timeWindow(LocalTime.of(12, 0), ScalperGates.NO_TRADE_BEFORE, ScalperGates.S24_WINDOW_TO).pass())
        .isTrue();
    assertThat(ScalperGates.timeWindow(LocalTime.of(14, 30), ScalperGates.NO_TRADE_BEFORE, ScalperGates.S24_WINDOW_TO).pass())
        .isFalse();
  }

  private static Oi crossed(boolean crossedThisWindow) {
    return new Oi(
        OiQuadrant.LONG_BUILDUP, OiQuadrant.LONG_BUILDUP, bd("10"), bd("0"), bd("5"), null, null, null,
        crossedThisWindow, false, null, null, null);
  }

  private static Oi imbalance(BigDecimal pct) {
    return new Oi(
        OiQuadrant.LONG_BUILDUP, OiQuadrant.LONG_BUILDUP, bd("10"), bd("0"), bd("5"),
        null, null, pct, false, false, null, null, null);
  }

  private static Oi oi(OiQuadrant futures) {
    return new Oi(futures, futures, bd("10"), bd("0"), bd("5"), null, null, null, false, false, null, null, null);
  }

  private static Oi crossDeltas(boolean crossedThisWindow, BigDecimal ceDelta, BigDecimal peDelta) {
    return new Oi(
        OiQuadrant.LONG_BUILDUP, OiQuadrant.LONG_BUILDUP, bd("10"), bd("0"), bd("5"), ceDelta, peDelta,
        bd("60"), crossedThisWindow, false, null, null, null);
  }

  private static Oi divergence(BigDecimal divergencePct, BigDecimal spurtPricePct) {
    return new Oi(
        OiQuadrant.LONG_BUILDUP, OiQuadrant.LONG_BUILDUP, bd("10"), bd("0"), bd("5"), null, null, null,
        false, false, null, null, spurtPricePct, divergencePct);
  }

  private static Oi sentiment(BigDecimal level, BigDecimal slope) {
    return new Oi(
        OiQuadrant.LONG_BUILDUP, OiQuadrant.LONG_BUILDUP, level, bd("0"), bd("5"), null, null, null,
        false, false, slope, null, null);
  }

  private static Oi basis(BigDecimal b) {
    return new Oi(
        OiQuadrant.LONG_BUILDUP, OiQuadrant.LONG_BUILDUP, bd("10"), bd("0"), b, null, null, null, false, false, null,
        null, null);
  }

  private static Macro macro(int adv, int dec, Boolean vixRising) {
    return new Macro(bd("14"), bd("30"), bd("12.5"), vixRising, adv, dec, bd("50"), null, null);
  }

  private static Macro macroIv(BigDecimal ceIvAvg6, BigDecimal peIvAvg6) {
    return new Macro(bd("14"), bd("30"), bd("12.5"), Boolean.FALSE, 40, 10, bd("50"), ceIvAvg6, peIvAvg6);
  }

  private static Macro macroFii(BigDecimal fiiLongPct) {
    return new Macro(bd("14"), bd("30"), bd("12.5"), Boolean.FALSE, 40, 10, fiiLongPct, null, null);
  }

  private static Macro macroConstituent(BigDecimal constituentBias) {
    return new Macro(
        bd("14"), bd("30"), bd("12.5"), Boolean.FALSE, 40, 10, bd("50"), null, null, constituentBias);
  }

  private static Macro macroFiiBias(BigDecimal fiiBiasSign) {
    return new Macro(
        bd("14"), bd("30"), bd("12.5"), Boolean.FALSE, 40, 10, bd("50"), null, null, null, null, null,
        null, null, fiiBiasSign);
  }

  @Test
  void atrStopPlacesTheStopMultipleAtrsOnTheProtectiveSide() {
    // CE: entry − 2×ATR (support below); PE: entry + 2×ATR (resistance above).
    assertThat(ScalperGates.atrStop(bd("100"), bd("5"), bd("2.0"), CE)).isEqualByComparingTo("90");
    assertThat(ScalperGates.atrStop(bd("100"), bd("5"), bd("2.0"), PE)).isEqualByComparingTo("110");
    // null / non-positive ATR degrades to null → the default structural stop stands.
    assertThat(ScalperGates.atrStop(bd("100"), null, bd("2.0"), CE)).isNull();
    assertThat(ScalperGates.atrStop(bd("100"), bd("0"), bd("2.0"), CE)).isNull();
    assertThat(ScalperGates.atrStop(null, bd("5"), bd("2.0"), CE)).isNull();
  }

  @Test
  void openingFormationRequiresThe2ndCandleToBreakAndHoldThe1st() {
    // first candle H=110 L=90. CE: 2nd must break ABOVE 110 AND close >= 110.
    assertThat(ScalperGates.openingFormation(bd("110"), bd("90"), bd("112"), bd("100"), bd("111"), CE).pass())
        .isTrue(); // broke 110 and held (close 111)
    assertThat(ScalperGates.openingFormation(bd("110"), bd("90"), bd("112"), bd("100"), bd("108"), CE).pass())
        .isFalse(); // poked to 112 but closed 108 back inside (a rejected fakeout)
    assertThat(ScalperGates.openingFormation(bd("110"), bd("90"), bd("109"), bd("100"), bd("109"), CE).pass())
        .isFalse(); // never broke the 110 high
    // PE mirror: 2nd must break BELOW 90 AND close <= 90.
    assertThat(ScalperGates.openingFormation(bd("110"), bd("90"), bd("100"), bd("88"), bd("89"), PE).pass())
        .isTrue();
    assertThat(ScalperGates.openingFormation(bd("110"), bd("90"), bd("100"), bd("88"), bd("92"), PE).pass())
        .isFalse(); // poked below but closed back above 90
    // a null candle operand degrades to pass (the seam guards "the 2nd bar formed").
    assertThat(ScalperGates.openingFormation(null, bd("90"), bd("112"), bd("100"), bd("111"), CE).pass())
        .isTrue();
  }

  @Test
  void eodConvincingCloseRequiresThePriorCloseInTheSideQuartile() {
    BigDecimal q = bd("0.25"); // top/bottom quartile of the prior range
    // prior H=200 L=100 range=100, band=25. CE wants close >= 175 (top quartile).
    assertThat(ScalperGates.eodConvincingClose(bd("200"), bd("100"), bd("180"), CE, q).pass()).isTrue();
    assertThat(ScalperGates.eodConvincingClose(bd("200"), bd("100"), bd("174"), CE, q).pass()).isFalse();
    // PE wants close <= 125 (bottom quartile).
    assertThat(ScalperGates.eodConvincingClose(bd("200"), bd("100"), bd("120"), PE, q).pass()).isTrue();
    assertThat(ScalperGates.eodConvincingClose(bd("200"), bd("100"), bd("126"), PE, q).pass()).isFalse();
    // null operands / a zero-range prior session degrade to pass.
    assertThat(ScalperGates.eodConvincingClose(null, bd("100"), bd("180"), CE, q).pass()).isTrue();
    assertThat(ScalperGates.eodConvincingClose(bd("150"), bd("150"), bd("150"), CE, q).pass()).isTrue();
  }
}
