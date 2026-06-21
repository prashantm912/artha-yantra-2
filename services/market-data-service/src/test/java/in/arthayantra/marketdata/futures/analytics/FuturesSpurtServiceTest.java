package in.arthayantra.marketdata.futures.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.options.OiInterpretation;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class FuturesSpurtServiceTest {

  private static FuturesSnapshotReader.FutPoint p(OffsetDateTime b, String sym, String ltp, long oi) {
    return new FuturesSnapshotReader.FutPoint(
        b, null, sym, new BigDecimal(ltp), oi, 0L, null, null, null, null, null, null);
  }

  @Test
  void diffsLatestTwoBucketsPerContract() {
    OffsetDateTime b0 = OffsetDateTime.of(2026, 6, 20, 9, 15, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OffsetDateTime b1 = b0.plusMinutes(5);
    // ltp 100->110 (up), oi 1000->1200 (up) => LONG_BUILDUP, spurt +20.00%
    List<FuturesSnapshotReader.FutPoint> pair =
        List.of(p(b0, "NIFTY26JUNFUT", "100", 1000), p(b1, "NIFTY26JUNFUT", "110", 1200));

    FuturesSpurtService.FutSpurtChain c = new FuturesSpurtService().spurts(pair);

    assertThat(c.items()).hasSize(1);
    assertThat(c.items().get(0).oiChange()).isEqualTo(200L);
    assertThat(c.items().get(0).spurtPct()).isEqualByComparingTo("20.00");
    assertThat(c.items().get(0).interpretation()).isEqualTo(OiInterpretation.LONG_BUILDUP);
    assertThat(c.asOf()).isEqualTo(b1);
  }

  @Test
  void emptyWhenOnlyOneBucket() {
    OffsetDateTime b0 = OffsetDateTime.of(2026, 6, 20, 9, 15, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    FuturesSpurtService.FutSpurtChain c =
        new FuturesSpurtService().spurts(List.of(p(b0, "NIFTY26JUNFUT", "100", 1000)));
    assertThat(c.items()).isEmpty();
    assertThat(c.asOf()).isEqualTo(b0);
  }
}
