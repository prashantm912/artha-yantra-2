package in.arthayantra.marketdata.options;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.options.IvAnalyticsService.RankStat;
import in.arthayantra.marketdata.options.IvDailySummaryRepository.RollupRow;
import in.arthayantra.marketdata.options.IvDailySummaryRepository.Summary;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pure rollup/rank math (F-42B) — exact, no Spring context. */
class IvAnalyticsServiceTest {

  private static final LocalDate DATE = LocalDate.parse("2026-06-10");

  @Test
  void atmIvIsTheMeanOfTheCeAndPeIvAtTheStrikeClosestToSpot() {
    List<RollupRow> rows =
        List.of(
            new RollupRow(DATE.plusDays(20), bd("18000"), "CE", bd("0.160000"), bd("18000")),
            new RollupRow(DATE.plusDays(20), bd("18000"), "PE", bd("0.140000"), bd("18000")),
            new RollupRow(DATE.plusDays(20), bd("17900"), "CE", bd("0.300000"), bd("18000")));
    Summary summary = IvAnalyticsService.compute("U", DATE, rows, 2);
    assertThat(summary.atmIv()).isEqualByComparingTo("0.150000");
    assertThat(summary.spotPrice()).isEqualByComparingTo("18000");
    assertThat(summary.snapshotCount()).isEqualTo(2);
    // single expiry → cannot bracket 30 days
    assertThat(summary.iv30d()).isNull();
  }

  @Test
  void constantMaturity30dInterpolatesBetweenBracketingExpiries() {
    List<RollupRow> rows =
        List.of(
            new RollupRow(DATE.plusDays(20), bd("18000"), "CE", bd("0.200000"), bd("18000")),
            new RollupRow(DATE.plusDays(20), bd("18000"), "PE", bd("0.200000"), bd("18000")),
            new RollupRow(DATE.plusDays(45), bd("18000"), "CE", bd("0.160000"), bd("18000")),
            new RollupRow(DATE.plusDays(45), bd("18000"), "PE", bd("0.160000"), bd("18000")));
    Summary summary = IvAnalyticsService.compute("U", DATE, rows, 1);
    assertThat(summary.atmIv()).isEqualByComparingTo("0.200000"); // nearest expiry = 20d
    assertThat(summary.iv30d()).isNotNull();
    // 30d sits between the 20d (0.20) and 45d (0.16) IVs
    assertThat(summary.iv30d().doubleValue()).isBetween(0.16, 0.20);
  }

  @Test
  void rankStatComputesRankAndPercentileAboveTheFloor() {
    RankStat stat =
        IvAnalyticsService.rankStat(
            List.of(bd("0.10"), bd("0.20"), bd("0.30"), bd("0.40"), bd("0.25")), 3);
    assertThat(stat.insufficient()).isFalse();
    assertThat(stat.rank()).isEqualByComparingTo("0.5000"); // (0.25-0.10)/(0.40-0.10)
    assertThat(stat.percentile()).isEqualTo(40); // 2 of 5 strictly below
    assertThat(stat.windowDays()).isEqualTo(5);
  }

  @Test
  void rankStatSuppressesRankBelowTheFloor() {
    RankStat stat = IvAnalyticsService.rankStat(List.of(bd("0.10"), bd("0.20")), 60);
    assertThat(stat.insufficient()).isTrue();
    assertThat(stat.rank()).isNull();
    assertThat(stat.percentile()).isNull();
    assertThat(stat.current()).isEqualByComparingTo("0.20");
    assertThat(stat.windowDays()).isEqualTo(2);
  }

  private static BigDecimal bd(String v) {
    return new BigDecimal(v);
  }
}
