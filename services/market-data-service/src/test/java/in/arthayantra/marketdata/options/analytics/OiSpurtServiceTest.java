package in.arthayantra.marketdata.options.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.options.OiInterpretation;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class OiSpurtServiceTest {

  @Test
  void classifiesAndComputesSpurtPct() {
    OiSpurtService svc = new OiSpurtService();
    // ltp up (+5), oi up (+200 from prior 1000) → LONG_BUILDUP, spurt 20.00%
    OiSpurtService.SpurtRow r = svc.classify(new BigDecimal("5"), 200, 1000);
    assertThat(r.interpretation()).isEqualTo(OiInterpretation.LONG_BUILDUP);
    assertThat(r.spurtPct()).isEqualByComparingTo("20.00");
  }

  @Test
  void spurtPctNullWhenNoPriorOi() {
    assertThat(new OiSpurtService().classify(BigDecimal.ONE, 50, 0).spurtPct()).isNull();
  }

  private static OptionsSnapshotReader.StrikePoint pt(
      java.time.OffsetDateTime b, String strike, String type, String ltp, long oi, String spot) {
    return new OptionsSnapshotReader.StrikePoint(
        b, new BigDecimal(strike), type, new BigDecimal(ltp), oi, 0L, null, new BigDecimal(spot));
  }

  @Test
  void spurtsDiffsLatestTwoBucketsPerStrikeAndRollsUpSummary() {
    java.time.OffsetDateTime b0 =
        java.time.OffsetDateTime.of(
            2026, 6, 20, 9, 15, 0, 0, java.time.ZoneOffset.ofHoursMinutes(5, 30));
    java.time.OffsetDateTime b1 = b0.plusMinutes(5);
    // CE: ltp 100->110 (up), oi 1000->1200 (up) => LONG_BUILDUP, spurt +20.00%
    // PE: ltp 90->80 (down), oi 800->1000 (up) => SHORT_BUILDUP, spurt +25.00%
    // spot 22480 -> 22500 (up); total OI-delta = +200 +200 = +400 (up) => summary LONG_BUILDUP
    java.util.List<OptionsSnapshotReader.StrikePoint> pair =
        java.util.List.of(
            pt(b0, "22500", "CE", "100", 1000, "22480"),
            pt(b1, "22500", "CE", "110", 1200, "22500"),
            pt(b0, "22500", "PE", "90", 800, "22480"),
            pt(b1, "22500", "PE", "80", 1000, "22500"));

    OiSpurtService.SpurtChain chain = new OiSpurtService().spurts(pair);

    assertThat(chain.items()).hasSize(2);
    OiSpurtService.StrikeSpurt ce =
        chain.items().stream().filter(r -> r.optionType().equals("CE")).findFirst().orElseThrow();
    assertThat(ce.interpretation()).isEqualTo(OiInterpretation.LONG_BUILDUP);
    assertThat(ce.oiChange()).isEqualTo(200L);
    assertThat(ce.spurtPct()).isEqualByComparingTo("20.00");
    // CE ltp 100 -> 110: ltpChangePct = (110 - 100) / 100 * 100 = 10.00
    assertThat(ce.ltpChangePct()).isEqualByComparingTo("10.00");
    OiSpurtService.StrikeSpurt pe =
        chain.items().stream().filter(r -> r.optionType().equals("PE")).findFirst().orElseThrow();
    // PE ltp 90 -> 80: ltpChangePct = (80 - 90) / 90 * 100 = -11.11
    assertThat(pe.ltpChangePct()).isEqualByComparingTo("-11.11");
    assertThat(chain.summary().interpretation()).isEqualTo(OiInterpretation.LONG_BUILDUP);
    assertThat(chain.summary().spotDelta()).isEqualByComparingTo("20");
    assertThat(chain.summary().oiChange()).isEqualTo(400L);
    // representative (largest |spurtPct|) is PE: oiChangePct = 25.00, priceChangePct = -11.11
    assertThat(chain.summary().oiChangePct()).isEqualByComparingTo("25.00");
    assertThat(chain.summary().priceChangePct()).isEqualByComparingTo("-11.11");
    assertThat(chain.asOf()).isEqualTo(b1);
  }

  @Test
  void ltpChangePctNullWhenPriorLtpZero() {
    java.time.OffsetDateTime b0 =
        java.time.OffsetDateTime.of(
            2026, 6, 20, 9, 15, 0, 0, java.time.ZoneOffset.ofHoursMinutes(5, 30));
    java.time.OffsetDateTime b1 = b0.plusMinutes(5);
    // prior LTP 0 -> no percent base; oi 1000->1200 still gives a spurtPct of 20.00
    java.util.List<OptionsSnapshotReader.StrikePoint> pair =
        java.util.List.of(
            pt(b0, "22500", "CE", "0", 1000, "22480"),
            pt(b1, "22500", "CE", "110", 1200, "22500"));
    OiSpurtService.SpurtChain chain = new OiSpurtService().spurts(pair);
    OiSpurtService.StrikeSpurt ce = chain.items().get(0);
    assertThat(ce.ltpChangePct()).isNull();
    assertThat(ce.spurtPct()).isEqualByComparingTo("20.00");
    // representative row is the only spurt row; priceChangePct rides its (null) ltpChangePct
    assertThat(chain.summary().oiChangePct()).isEqualByComparingTo("20.00");
    assertThat(chain.summary().priceChangePct()).isNull();
  }

  @Test
  void spurtsEmptyWhenOnlyOneBucket() {
    java.time.OffsetDateTime b0 =
        java.time.OffsetDateTime.of(
            2026, 6, 20, 9, 15, 0, 0, java.time.ZoneOffset.ofHoursMinutes(5, 30));
    OiSpurtService.SpurtChain chain =
        new OiSpurtService().spurts(java.util.List.of(pt(b0, "22500", "CE", "100", 1000, "22480")));
    assertThat(chain.items()).isEmpty();
    assertThat(chain.summary()).isNull();
    assertThat(chain.asOf()).isEqualTo(b0);
  }
}
