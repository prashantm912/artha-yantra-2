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
    return new OptionsSnapshotReader.StrikePoint(
        b, new BigDecimal(strike), type, new BigDecimal(ltp), 0L, 0L, null, new BigDecimal("22480"));
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
}
