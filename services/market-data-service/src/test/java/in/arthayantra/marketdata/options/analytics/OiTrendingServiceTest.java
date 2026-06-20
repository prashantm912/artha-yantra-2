package in.arthayantra.marketdata.options.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class OiTrendingServiceTest {

  private static OptionsSnapshotReader.StrikePoint pt(OffsetDateTime b, String type, long oi) {
    return new OptionsSnapshotReader.StrikePoint(
        b, new BigDecimal("22500"), type, new BigDecimal("100.00"), oi, 0L, null,
        new BigDecimal("22480"), null);
  }

  @Test
  void marksUpDownFlatVsPriorBucketTotalOi() {
    OffsetDateTime b0 = OffsetDateTime.of(2026, 6, 20, 9, 15, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OffsetDateTime b1 = b0.plusMinutes(5);
    OffsetDateTime b2 = b0.plusMinutes(10);
    // totals: b0=1000, b1=1500 (UP), b2=1500 (FLAT)
    List<OptionsSnapshotReader.StrikePoint> series =
        List.of(pt(b0, "CE", 1000), pt(b1, "CE", 1500), pt(b2, "CE", 1500));

    OiTrendingService.TrendSeries s = new OiTrendingService().trending(series);

    assertThat(s.items()).hasSize(3);
    assertThat(s.items().get(0).trend()).isEqualTo(OiTrendingService.Trend.FLAT); // no prior
    assertThat(s.items().get(1).trend()).isEqualTo(OiTrendingService.Trend.UP);
    assertThat(s.items().get(2).trend()).isEqualTo(OiTrendingService.Trend.FLAT);
    assertThat(s.asOf()).isEqualTo(b2);
  }
}
