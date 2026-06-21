package in.arthayantra.marketdata.futures.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.options.OiInterpretation;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class FuturesBuzzServiceTest {

  private static FuturesSnapshotReader.FutPoint p(OffsetDateTime b, String sym, String ltp, long oi) {
    return new FuturesSnapshotReader.FutPoint(
        b, null, sym, new BigDecimal(ltp), oi, 0L, null, null, null, null, null, null);
  }

  @Test
  void buildsTimeByContractMatrixOf4State() {
    OffsetDateTime b0 = OffsetDateTime.of(2026, 6, 20, 9, 15, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OffsetDateTime b1 = b0.plusMinutes(5);
    // contract X: ltp 100->110 (up), oi 1000->1200 (up) => LONG_BUILDUP at b1; b0 has no prior
    List<FuturesSnapshotReader.FutPoint> series =
        List.of(p(b0, "X", "100", 1000), p(b1, "X", "110", 1200));

    FuturesBuzzService.BuzzMatrix m = new FuturesBuzzService().buzz(series);

    assertThat(m.contracts()).containsExactly("X");
    assertThat(m.buckets()).containsExactly(b0, b1);
    assertThat(m.cells().get(0).get(0)).isNull(); // first bucket: no prior
    assertThat(m.cells().get(1).get(0)).isEqualTo(OiInterpretation.LONG_BUILDUP);
  }

  @Test
  void emptySeriesGivesEmptyMatrix() {
    FuturesBuzzService.BuzzMatrix m = new FuturesBuzzService().buzz(List.of());
    assertThat(m.buckets()).isEmpty();
    assertThat(m.cells()).isEmpty();
    assertThat(m.asOf()).isNull();
  }
}
