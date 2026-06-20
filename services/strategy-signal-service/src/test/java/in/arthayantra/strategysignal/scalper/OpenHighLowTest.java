package in.arthayantra.strategysignal.scalper;

import static in.arthayantra.black76.Black76.OptionType.CE;
import static in.arthayantra.black76.Black76.OptionType.PE;
import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.black76.Black76.OptionType;
import in.arthayantra.strategysignal.scalper.MarketOiClient.OpenHighStats;
import in.arthayantra.strategysignal.scalper.MarketOiClient.StrikeStat;
import in.arthayantra.strategysignal.scalper.OpenHighLow.Marks;
import in.arthayantra.strategysignal.scalper.OpenHighLow.Tier;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * section 3.2 Open=High / Open=Low source-faithful grading: Table-1 (front-future OH + the per-strike
 * OH/OL footprint counts -> HIGH/MILD), Table-2 (a representative-OH fall-on-volume downgrade to LOW),
 * the &gt;50% prev-close-fall LOW rule, and the both-sides / no-mark STAND_ASIDE.
 */
class OpenHighLowTest {

  private static final ScalperOiProps PROPS = ScalperOiProps.defaults(); // minStrikes 3, floor 50000, fall 50

  private static BigDecimal bd(String s) {
    return new BigDecimal(s);
  }

  private static final Marks FUTURE_OH = new Marks(true, false);
  private static final Marks FUTURE_OL = new Marks(false, true);
  private static final Marks FUTURE_NONE = new Marks(false, false);

  /** A plain OH/OL footprint leg with no price/volume modifier (last==open==high, no decline). */
  private static StrikeStat leg(String strike, OptionType type, boolean oh, boolean ol) {
    return new StrikeStat(
        bd(strike), type, oh, ol, bd("100"), bd("100"), bd("100"), null, null);
  }

  /** A leg that FELL (last below open/high) with an explicit declineVolume + prev-close fall %. */
  private static StrikeStat fellLeg(
      String strike, OptionType type, String last, Long declineVol, String prevCloseFallPct) {
    return new StrikeStat(
        bd(strike),
        type,
        true,
        false,
        bd(last),
        bd("100"),
        bd("100"),
        declineVol,
        prevCloseFallPct == null ? null : bd(prevCloseFallPct));
  }

  private static OpenHighStats stats(String atm, StrikeStat... legs) {
    return new OpenHighStats(bd(atm), List.of(legs));
  }

  // 3 CE OH strikes + 1 PE OL strike around an ATM of 20000.
  private static OpenHighStats bullishHighFootprint() {
    List<StrikeStat> legs = new ArrayList<>();
    legs.add(leg("19900", CE, true, false));
    legs.add(leg("20000", CE, true, false));
    legs.add(leg("20100", CE, true, false));
    legs.add(leg("19900", PE, false, true));
    return new OpenHighStats(bd("20000"), legs);
  }

  // 3 PE OH strikes + 1 CE OL strike (the bearish mirror).
  private static OpenHighStats bearishHighFootprint() {
    List<StrikeStat> legs = new ArrayList<>();
    legs.add(leg("19900", PE, true, false));
    legs.add(leg("20000", PE, true, false));
    legs.add(leg("20100", PE, true, false));
    legs.add(leg("20100", CE, false, true));
    return new OpenHighStats(bd("20000"), legs);
  }

  @Test
  void highTierWhenFutureOhAndThreeCeOhStrikesAndaPeOl() {
    assertThat(OpenHighLow.tier(FUTURE_OH, bullishHighFootprint(), CE, PROPS)).isEqualTo(Tier.HIGH);
  }

  @Test
  void mirrorBearishHighTier() {
    assertThat(OpenHighLow.tier(FUTURE_OL, bearishHighFootprint(), PE, PROPS)).isEqualTo(Tier.HIGH);
  }

  @Test
  void mildTierWhenFewerThanMinStrikes() {
    // only 2 CE OH strikes (< minStrikes 3), with a PE OL present -> few-strikes MILD.
    OpenHighStats few =
        stats(
            "20000",
            leg("19900", CE, true, false),
            leg("20000", CE, true, false),
            leg("19900", PE, false, true));
    assertThat(OpenHighLow.tier(FUTURE_OH, few, CE, PROPS)).isEqualTo(Tier.MILD);
  }

  @Test
  void mildTierWhenFutureOhButNoStrikeFootprintOnTheSide() {
    // a footprint exists (a PE OL leg) but ZERO CE OH strikes -> future-only mark -> MILD.
    OpenHighStats noCeOh = stats("20000", leg("19900", PE, false, true));
    assertThat(OpenHighLow.tier(FUTURE_OH, noCeOh, CE, PROPS)).isEqualTo(Tier.MILD);
  }

  @Test
  void standAsideWhenBothSidesHaveAnOhFootprint() {
    // CE OH AND PE OH together -> two-sided sideways -> STAND_ASIDE (even with the future OH mark).
    OpenHighStats bothSides =
        stats(
            "20000",
            leg("19900", CE, true, false),
            leg("20000", CE, true, false),
            leg("20100", CE, true, false),
            leg("20000", PE, true, false));
    assertThat(OpenHighLow.tier(FUTURE_OH, bothSides, CE, PROPS)).isEqualTo(Tier.STAND_ASIDE);
  }

  @Test
  void standAsideWhenNoFutureMarkOnTheSide() {
    // the CE side wants a Futures-OH; a Futures-OL only -> nothing to trade long -> STAND_ASIDE.
    assertThat(OpenHighLow.tier(FUTURE_OL, bullishHighFootprint(), CE, PROPS))
        .isEqualTo(Tier.STAND_ASIDE);
    // a PE request on a Futures-OH-only session -> STAND_ASIDE.
    assertThat(OpenHighLow.tier(FUTURE_OH, bearishHighFootprint(), PE, PROPS))
        .isEqualTo(Tier.STAND_ASIDE);
  }

  @Test
  void standAsideWhenStatsAreNullOrEmpty() {
    assertThat(OpenHighLow.tier(FUTURE_OH, null, CE, PROPS)).isEqualTo(Tier.STAND_ASIDE);
    assertThat(OpenHighLow.tier(FUTURE_OH, new OpenHighStats(bd("20000"), List.of()), CE, PROPS))
        .isEqualTo(Tier.STAND_ASIDE);
    assertThat(OpenHighLow.tier(null, bullishHighFootprint(), CE, PROPS)).isEqualTo(Tier.STAND_ASIDE);
    assertThat(OpenHighLow.tier(FUTURE_NONE, bullishHighFootprint(), CE, PROPS))
        .isEqualTo(Tier.STAND_ASIDE);
  }

  @Test
  void table2DowngradesToLowWhenTheRepresentativeOhFellOnHeavyVolume() {
    // the ATM CE OH strike fell (last 90 < open 100) on declineVolume 60000 (>= 50000 floor) -> LOW.
    List<StrikeStat> legs = new ArrayList<>();
    legs.add(leg("19900", CE, true, false));
    legs.add(fellLeg("20000", CE, "90", 60_000L, null)); // representative (== ATM)
    legs.add(leg("20100", CE, true, false));
    legs.add(leg("19900", PE, false, true));
    OpenHighStats s = new OpenHighStats(bd("20000"), legs);
    assertThat(OpenHighLow.tier(FUTURE_OH, s, CE, PROPS)).isEqualTo(Tier.LOW);
  }

  @Test
  void table2KeepsHighWhenTheFallVolumeIsBelowTheFloor() {
    // fell, but declineVolume 49999 (< 50000 floor) -> not a real opposite player -> keep HIGH.
    List<StrikeStat> legs = new ArrayList<>();
    legs.add(leg("19900", CE, true, false));
    legs.add(fellLeg("20000", CE, "90", 49_999L, null));
    legs.add(leg("20100", CE, true, false));
    legs.add(leg("19900", PE, false, true));
    OpenHighStats s = new OpenHighStats(bd("20000"), legs);
    assertThat(OpenHighLow.tier(FUTURE_OH, s, CE, PROPS)).isEqualTo(Tier.HIGH);
  }

  @Test
  void prevCloseFallExceedingFiftyPercentDowngradesToLow() {
    // the representative OH strike has a 60% prev-close fall (> 50) -> a bigger player -> LOW.
    List<StrikeStat> legs = new ArrayList<>();
    legs.add(leg("19900", CE, true, false));
    legs.add(fellLeg("20000", CE, "100", null, "-60")); // |-60| > 50, declineVolume null (Table-2 inert)
    legs.add(leg("20100", CE, true, false));
    legs.add(leg("19900", PE, false, true));
    OpenHighStats s = new OpenHighStats(bd("20000"), legs);
    assertThat(OpenHighLow.tier(FUTURE_OH, s, CE, PROPS)).isEqualTo(Tier.LOW);
  }
}
