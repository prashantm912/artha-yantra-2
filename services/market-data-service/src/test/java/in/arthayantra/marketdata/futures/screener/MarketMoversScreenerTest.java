package in.arthayantra.marketdata.futures.screener;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.futures.screener.MarketMoversScreener.DailyBar;
import in.arthayantra.marketdata.futures.screener.MarketMoversScreener.ScreenerRow;
import in.arthayantra.marketdata.options.OiInterpretation;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** §3.3 Market-Movers screener core: 8/9-day breakout + OH/OL + OI-quadrant + daily-RSI grading. */
class MarketMoversScreenerTest {

  private static BigDecimal bd(String s) {
    return new BigDecimal(s);
  }

  private static DailyBar bar(String o, String h, String l, String c, long vol, long oi) {
    return new DailyBar(bd(o), bd(h), bd(l), bd(c), vol, oi);
  }

  // 15 prior 100/101 zigzag bars (highs 101/102) + today: an 8-day high (104), Open=Low (102), OI up.
  private static List<DailyBar> longSetup() {
    List<DailyBar> bars = new ArrayList<>();
    for (int i = 0; i < 15; i++) {
      String c = i % 2 == 0 ? "100" : "101";
      bars.add(bar(c, new BigDecimal(c).add(bd("1")).toPlainString(),
          new BigDecimal(c).subtract(bd("1")).toPlainString(), c, 1_000_000, 1000));
    }
    bars.add(bar("102", "104", "102", "103.5", 5_000_000, 1100)); // today: high>all prior, open==low, OI up
    return bars;
  }

  @Test
  void gradesAHighProbabilityLongWhenAllThreeAspectsAlign() {
    ScreenerRow r = MarketMoversScreener.classify("RELIANCE", longSetup());
    assertThat(r).isNotNull();
    assertThat(r.breakoutDays()).isEqualTo(15); // today's high exceeds every prior session's high
    assertThat(r.openLow()).isTrue();
    assertThat(r.interpretation()).isEqualTo(OiInterpretation.LONG_BUILDUP); // price up + OI up
    assertThat(r.dailyRsi()).isBetween(bd("55"), bd("70")); // elevated but below the 75 no-long cap
    assertThat(r.longCandidate()).isTrue();
    assertThat(r.shortCandidate()).isFalse();
  }

  @Test
  void rejectsTheLongWhenTheBreakoutIsTooShallow() {
    // same setup but today's high (101.5) clears only the immediately-prior bar — far short of 8 days.
    List<DailyBar> bars = longSetup();
    bars.set(bars.size() - 1, bar("100.5", "101.5", "100.5", "101.2", 5_000_000, 1100));
    ScreenerRow r = MarketMoversScreener.classify("RELIANCE", bars);
    assertThat(r.breakoutDays()).isLessThan(MarketMoversScreener.MIN_BREAKOUT_DAYS);
    assertThat(r.longCandidate()).isFalse();
  }

  @Test
  void rejectsTheLongOnAShortSideOiQuadrantEvenAtAnEightDayHigh() {
    // 8-day high + Open=Low but OI FELL (short covering on a small up-move is still not a long build-up
    // here: price up + OI down = SHORT_COVERING qualifies; flip to price-up + OI-up needed). Use OI DOWN
    // with the long quadrant excluded by making it price-down is wrong; instead drop OI hard to force SC
    // -> still a long-side quadrant. To get a non-long quadrant on the LONG geometry, make price flat/down.
    List<DailyBar> bars = longSetup();
    // today closes BELOW prev (price down) with OI up -> SHORT_BUILDUP (a short-side quadrant) -> no long.
    bars.set(bars.size() - 1, bar("104", "104", "99", "99.5", 5_000_000, 1100));
    ScreenerRow r = MarketMoversScreener.classify("RELIANCE", bars);
    assertThat(r.interpretation()).isEqualTo(OiInterpretation.SHORT_BUILDUP);
    assertThat(r.longCandidate()).isFalse();
  }

  @Test
  void nDayExtremesCountsTheBreakoutStreak() {
    assertThat(NDayExtremes.breakoutDaysHigh(List.of(bd("10"), bd("13"), bd("9"), bd("12"))))
        .isEqualTo(1); // 12 clears the 9, then the 13 (>= 12) stops the streak
    assertThat(NDayExtremes.breakoutDaysHigh(List.of(bd("10"), bd("9"), bd("8"), bd("12"))))
        .isEqualTo(3); // 12 clears all three priors
    assertThat(NDayExtremes.breakoutDaysLow(List.of(bd("10"), bd("11"), bd("12"), bd("7"))))
        .isEqualTo(3); // 7 undercuts all three priors
  }

  @Test
  void screenRanksCandidatesByMoveTimesLiquidity() {
    List<DailyBar> big = longSetup(); // 5,000,000 vol, ~3.5% move
    List<DailyBar> small = longSetup();
    small.set(small.size() - 1, bar("102", "104", "102", "100.5", 500_000, 1100)); // tiny move, thin vol
    MarketMoversScreener.Screen s =
        MarketMoversScreener.screen(Map.of("BIG", big, "SMALL", small));
    assertThat(s.longCandidates()).isNotEmpty();
    assertThat(s.longCandidates().get(0).symbol()).isEqualTo("BIG"); // higher move × liquidity ranks first
  }
}
