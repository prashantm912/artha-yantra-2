package in.arthayantra.marketdata.options.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class OiPremiumServiceTest {

  private static OptionsSnapshotReader.StrikePoint pt(String strike, String type, String ltp) {
    OffsetDateTime b = OffsetDateTime.of(2026, 6, 20, 9, 15, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    return ptAt(b, strike, type, ltp);
  }

  private static OptionsSnapshotReader.StrikePoint ptAt(
      OffsetDateTime bucket, String strike, String type, String ltp) {
    return new OptionsSnapshotReader.StrikePoint(
        bucket, new BigDecimal(strike), type, new BigDecimal(ltp), 0L, 0L, null,
        new BigDecimal("22480"));
  }

  @Test
  void foldsStraddlePerStrikeAndPicksAtm() {
    // spot 22480 -> ATM strike is 22500; its straddle = 80 + 70 = 150
    List<OptionsSnapshotReader.StrikePoint> latest =
        List.of(
            pt("22400", "CE", "120"),
            pt("22400", "PE", "30"),
            pt("22500", "CE", "80"),
            pt("22500", "PE", "70"),
            pt("22600", "CE", "40"),
            pt("22600", "PE", "130"));

    OiPremiumService.PremiumChain c = new OiPremiumService().premium(latest);

    assertThat(c.items()).hasSize(3);
    assertThat(c.items().get(0).straddle()).isEqualByComparingTo("150"); // 22400: 120+30
    assertThat(c.atmStrike()).isEqualByComparingTo("22500");
    assertThat(c.atmStraddle()).isEqualByComparingTo("150"); // 80+70
    assertThat(c.spot()).isEqualByComparingTo("22480");
  }

  @Test
  void skipsStrikesMissingALeg() {
    OiPremiumService.PremiumChain c =
        new OiPremiumService().premium(List.of(pt("22500", "CE", "80"))); // no PE
    assertThat(c.items()).isEmpty();
  }

  @Test
  void premiumSeriesTracksAtmStraddlePerBucket() {
    OffsetDateTime b0 = OffsetDateTime.of(2026, 6, 20, 9, 15, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OffsetDateTime b1 = b0.plusMinutes(5);
    // one ATM strike per bucket; straddle 150 then 170 (intraday decay/move curve)
    List<OptionsSnapshotReader.StrikePoint> series =
        List.of(
            ptAt(b0, "22500", "CE", "80"),
            ptAt(b0, "22500", "PE", "70"),
            ptAt(b1, "22500", "CE", "100"),
            ptAt(b1, "22500", "PE", "70"));

    OiPremiumService.PremiumSeries s = new OiPremiumService().premiumSeries(series);

    assertThat(s.items()).hasSize(2);
    assertThat(s.items().get(0).bucket()).isEqualTo(b0);
    assertThat(s.items().get(0).atmStraddle()).isEqualByComparingTo("150");
    assertThat(s.items().get(1).atmStraddle()).isEqualByComparingTo("170");
    assertThat(s.asOf()).isEqualTo(b1);
  }
}
