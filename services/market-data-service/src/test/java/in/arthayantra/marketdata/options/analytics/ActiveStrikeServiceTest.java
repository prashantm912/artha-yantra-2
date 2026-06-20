package in.arthayantra.marketdata.options.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ActiveStrikeServiceTest {

  private static ActiveStrikeService.StrikeOiSnap snap(
      String strike, long ceOi, long ceChg, long peOi, long peChg) {
    return new ActiveStrikeService.StrikeOiSnap(new BigDecimal(strike), ceOi, ceChg, peOi, peChg);
  }

  @Test
  void selectsTopNByTotalOi() {
    List<ActiveStrikeService.StrikeOiSnap> chain =
        List.of(
            snap("22400", 100, 0, 100, 0),
            snap("22500", 900, 0, 900, 0),
            snap("22600", 50, 0, 50, 0));
    ActiveStrikeService svc = new ActiveStrikeService(1);
    assertThat(svc.activeStrikes(chain))
        .extracting(s -> s.strike().toPlainString())
        .containsExactly("22500");
  }

  @Test
  void sentimentBullishWhenPutsBuildAndCallsUnwind() {
    // active strike 22500: PE OI building (+300), CE OI unwinding (-200); base = 1000+1000
    List<ActiveStrikeService.StrikeOiSnap> chain = List.of(snap("22500", 1000, -200, 1000, 300));
    ActiveStrikeService svc = new ActiveStrikeService(1);
    // bullishFlow = 300 - (-200) = 500; base = 2000; sentiment = 100*500/2000 = 25.00
    assertThat(svc.sentimentPct(chain)).isEqualByComparingTo("25.00");
  }

  @Test
  void sentimentNullWhenNoBaseOi() {
    assertThat(new ActiveStrikeService(5).sentimentPct(List.of())).isNull();
  }

  private static OptionsSnapshotReader.StrikePoint pt(
      java.time.OffsetDateTime b, String strike, String type, long oi, long oiChange) {
    return new OptionsSnapshotReader.StrikePoint(
        b, new BigDecimal(strike), type, null, oi, oiChange, null, null, null);
  }

  @Test
  void sentimentSeriesComputesPerBucketNewestLast() {
    java.time.OffsetDateTime b0 =
        java.time.OffsetDateTime.of(
            2026, 6, 20, 9, 15, 0, 0, java.time.ZoneOffset.ofHoursMinutes(5, 30));
    java.time.OffsetDateTime b1 = b0.plusMinutes(5);
    // bucket b0, strike 22500: PE OI +300, CE OI -200; base 1000+1000=2000 => 100*500/2000 = 25.00
    // bucket b1, strike 22500: PE OI -100, CE OI +400; base 1100+900=2000 => 100*(-500)/2000 = -25.00
    List<OptionsSnapshotReader.StrikePoint> series =
        List.of(
            pt(b0, "22500", "CE", 1000, -200),
            pt(b0, "22500", "PE", 1000, 300),
            pt(b1, "22500", "CE", 900, 400),
            pt(b1, "22500", "PE", 1100, -100));
    ActiveStrikeService svc = new ActiveStrikeService(5);
    List<ActiveStrikeService.SentimentPoint> out = svc.sentimentSeries(series);
    assertThat(out).hasSize(2);
    assertThat(out.get(0).bucket()).isEqualTo(b0);
    assertThat(out.get(0).sentimentPct()).isEqualByComparingTo("25.00");
    assertThat(out.get(1).bucket()).isEqualTo(b1);
    assertThat(out.get(1).sentimentPct()).isEqualByComparingTo("-25.00");
  }

  @Test
  void sentimentSeriesEmptyWhenNoPoints() {
    assertThat(new ActiveStrikeService(5).sentimentSeries(List.of())).isEmpty();
  }
}
