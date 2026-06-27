package in.arthayantra.strategysignal.scalper;

import static in.arthayantra.black76.Black76.OptionType.CE;
import static in.arthayantra.black76.Black76.OptionType.PE;
import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.black76.Black76.OptionType;
import in.arthayantra.strategysignal.scalper.MarketOiClient.OpenHighStats;
import in.arthayantra.strategysignal.scalper.MarketOiClient.StrikeStat;
import in.arthayantra.strategysignal.scalper.OpenHighLow.Marks;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Oi;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * section 3.2 Open=High / Open=Low pre-gate: the source-faithful Table-1/Table-2 tier == HIGH for the
 * side + the &lt;=50% spurt reject rules + the 1st-half (~12:00) cutoff; the front-future VWAP anchors
 * the SL. Any failing leg BLOCKS (never a false fire); null reject magnitudes do NOT block; null/empty
 * stats or a null OI snapshot degrade to a block.
 */
class OpenHighLowGateTest {

  private static final LocalTime MORNING = LocalTime.of(9, 50);
  private static final BigDecimal VWAP = new BigDecimal("97");
  private static final ScalperOiProps PROPS = ScalperOiProps.defaults();
  private static final Marks FUTURE_OH = new Marks(true, false);
  private static final Marks FUTURE_OL = new Marks(false, true);

  private static BigDecimal bd(String s) {
    return new BigDecimal(s);
  }

  // The Oi snapshot the gate reads for the spurt reject (only the spurt magnitudes matter here).
  private static Oi oi(String spurtOiPct, String spurtPricePct) {
    return new Oi(
        OiQuadrant.NEUTRAL, OiQuadrant.NEUTRAL, bd("10"), bd("5"), bd("5"), null, null, null, false,
        false, null, spurtOiPct == null ? null : bd(spurtOiPct),
        spurtPricePct == null ? null : bd(spurtPricePct));
  }

  private static StrikeStat leg(String strike, OptionType type, boolean oh, boolean ol) {
    return new StrikeStat(bd(strike), type, oh, ol, bd("100"), bd("100"), bd("100"), null, null, null);
  }

  // An OH leg carrying a specific per-strike change-in-OI % (the W3 PR-6 per-strike AVOID veto operand).
  private static StrikeStat legOi(String strike, OptionType type, String oiChangePct) {
    return new StrikeStat(
        bd(strike), type, true, false, bd("100"), bd("100"), bd("100"), null, null, bd(oiChangePct));
  }

  // A bullish HIGH footprint: 3 CE OH strikes + 1 PE OL, ATM 20000.
  private static OpenHighStats bullishHigh() {
    List<StrikeStat> legs = new ArrayList<>();
    legs.add(leg("19900", CE, true, false));
    legs.add(leg("20000", CE, true, false));
    legs.add(leg("20100", CE, true, false));
    legs.add(leg("19900", PE, false, true));
    return new OpenHighStats(bd("20000"), legs);
  }

  private static OpenHighStats bearishHigh() {
    List<StrikeStat> legs = new ArrayList<>();
    legs.add(leg("19900", PE, true, false));
    legs.add(leg("20000", PE, true, false));
    legs.add(leg("20100", PE, true, false));
    legs.add(leg("20100", CE, false, true));
    return new OpenHighStats(bd("20000"), legs);
  }

  @Test
  void passesABullishHighTierAndAnchorsTheVwapStop() {
    OpenHighLowGate.Verdict v =
        OpenHighLowGate.evaluate(FUTURE_OH, bullishHigh(), CE, PROPS, oi("10", "20"), VWAP, MORNING, false);
    assertThat(v.pass()).isTrue();
    assertThat(v.tier()).isEqualTo(OpenHighLow.Tier.HIGH);
    assertThat(v.stopLevel()).isEqualByComparingTo("97");
  }

  @Test
  void passesABearishHighTier() {
    OpenHighLowGate.Verdict v =
        OpenHighLowGate.evaluate(FUTURE_OL, bearishHigh(), PE, PROPS, oi("-10", "-20"), VWAP, MORNING, false);
    assertThat(v.pass()).isTrue();
  }

  @Test
  void blocksAtTheMildTierWhenFewerThanMinStrikes() {
    OpenHighStats few =
        new OpenHighStats(
            bd("20000"),
            List.of(leg("19900", CE, true, false), leg("19900", PE, false, true)));
    assertThat(
            OpenHighLowGate.evaluate(FUTURE_OH, few, CE, PROPS, oi("10", "20"), VWAP, MORNING, false).pass())
        .isFalse();
  }

  @Test
  void blocksAtStandAsideWhenThereIsNoMarkOnTheSide() {
    assertThat(
            OpenHighLowGate.evaluate(FUTURE_OH, bearishHigh(), PE, PROPS, oi("10", "20"), VWAP, MORNING, false)
                .pass())
        .isFalse();
  }

  @Test
  void blocksAtTheLowTierFromAFallOnHeavyVolume() {
    List<StrikeStat> legs = new ArrayList<>();
    legs.add(leg("19900", CE, true, false));
    legs.add(new StrikeStat(bd("20000"), CE, true, false, bd("90"), bd("100"), bd("100"), 60_000L, null, null));
    legs.add(leg("20100", CE, true, false));
    legs.add(leg("19900", PE, false, true));
    OpenHighStats s = new OpenHighStats(bd("20000"), legs);
    OpenHighLowGate.Verdict v =
        OpenHighLowGate.evaluate(FUTURE_OH, s, CE, PROPS, oi("10", "20"), VWAP, MORNING, false);
    assertThat(v.pass()).isFalse();
    assertThat(v.tier()).isEqualTo(OpenHighLow.Tier.LOW);
  }

  @Test
  void blocksWhenThePremiumSpurtExceedsFiftyPercent() {
    assertThat(
            OpenHighLowGate.evaluate(FUTURE_OH, bullishHigh(), CE, PROPS, oi("10", "50.1"), VWAP, MORNING, false)
                .pass())
        .isFalse();
  }

  @Test
  void blocksWhenTheOiSpurtExceedsFiftyPercent() {
    assertThat(
            OpenHighLowGate.evaluate(FUTURE_OH, bullishHigh(), CE, PROPS, oi("-60", "20"), VWAP, MORNING, false)
                .pass())
        .isFalse();
  }

  @Test
  void passesAtExactlyFiftyPercentSpurt() {
    assertThat(
            OpenHighLowGate.evaluate(FUTURE_OH, bullishHigh(), CE, PROPS, oi("50", "50"), VWAP, MORNING, false)
                .pass())
        .isTrue();
  }

  @Test
  void doesNotBlockOnNullSpurtMagnitudes() {
    assertThat(
            OpenHighLowGate.evaluate(FUTURE_OH, bullishHigh(), CE, PROPS, oi(null, null), VWAP, MORNING, false)
                .pass())
        .isTrue();
  }

  @Test
  void blocksAFreshEntryInTheSecondHalf() {
    assertThat(
            OpenHighLowGate.evaluate(
                    FUTURE_OH, bullishHigh(), CE, PROPS, oi("10", "20"), VWAP, LocalTime.of(12, 0), false)
                .pass())
        .isFalse();
    assertThat(
            OpenHighLowGate.evaluate(
                    FUTURE_OH, bullishHigh(), CE, PROPS, oi("10", "20"), VWAP, LocalTime.of(11, 59), false)
                .pass())
        .isTrue();
  }

  @Test
  void degradesToABlockWhenTheOiSnapshotIsNull() {
    assertThat(
            OpenHighLowGate.evaluate(FUTURE_OH, bullishHigh(), CE, PROPS, null, VWAP, MORNING, false).pass())
        .isFalse();
  }

  @Test
  void degradesToABlockWhenTheStatsAreNullOrEmpty() {
    assertThat(
            OpenHighLowGate.evaluate(FUTURE_OH, null, CE, PROPS, oi("10", "20"), VWAP, MORNING, false).pass())
        .isFalse();
    assertThat(
            OpenHighLowGate.evaluate(
                    FUTURE_OH, new OpenHighStats(bd("20000"), List.of()), CE, PROPS, oi("10", "20"),
                    VWAP, MORNING, false)
                .pass())
        .isFalse();
  }

  @Test
  void passesWithANullVwapButYieldsANullStop() {
    OpenHighLowGate.Verdict v =
        OpenHighLowGate.evaluate(FUTURE_OH, bullishHigh(), CE, PROPS, oi("10", "20"), null, MORNING, false);
    assertThat(v.pass()).isTrue();
    assertThat(v.stopLevel()).isNull();
  }

  @Test
  void perStrikeOiVetoBlocksAnArmedHighWhenTheStrikeOiChangeExceedsFiftyPercent() {
    // W3 PR-6: a HIGH footprint whose representative ATM CE strike has its OWN change-in-OI > 50%.
    // Chain-wide spurts are within 50% (the existing reject does not fire), so only the per-strike
    // veto can catch it — and only when armed.
    List<StrikeStat> legs = new ArrayList<>();
    legs.add(legOi("19900", CE, "10"));
    legs.add(legOi("20000", CE, "60")); // the ATM CE strike: ΔOI 60% > 50
    legs.add(legOi("20100", CE, "10"));
    legs.add(leg("19900", PE, false, true));
    OpenHighStats s = new OpenHighStats(bd("20000"), legs);
    // unarmed: the per-strike veto is OFF -> the HIGH footprint passes (chain-wide spurts within 50%).
    assertThat(
            OpenHighLowGate.evaluate(FUTURE_OH, s, CE, PROPS, oi("10", "20"), VWAP, MORNING, false).pass())
        .isTrue();
    // armed: the ATM strike's ΔOI > 50% vetoes to AVOID.
    OpenHighLowGate.Verdict v =
        OpenHighLowGate.evaluate(FUTURE_OH, s, CE, PROPS, oi("10", "20"), VWAP, MORNING, true);
    assertThat(v.pass()).isFalse();
    assertThat(v.tier()).isEqualTo(OpenHighLow.Tier.AVOID);
  }
}
