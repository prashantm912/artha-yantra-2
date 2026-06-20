package in.arthayantra.marketdata.options.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class OiBigOiServiceTest {

  private static OptionsSnapshotReader.StrikePoint pt(
      String strike, String type, long oi, long oiChange) {
    OffsetDateTime b = OffsetDateTime.of(2026, 6, 20, 9, 15, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    return new OptionsSnapshotReader.StrikePoint(
        b, new BigDecimal(strike), type, new BigDecimal("100.00"), oi, oiChange, null,
        new BigDecimal("22480"), null);
  }

  @Test
  void sortsByAbsOiChangeDescAndCapsTopN() {
    List<OptionsSnapshotReader.StrikePoint> latest =
        List.of(
            pt("22400", "CE", 1000, 50),
            pt("22500", "PE", 2000, -900), // biggest |delta|
            pt("22600", "CE", 1500, 300),
            pt("22700", "PE", 800, -120));

    OiBigOiService.BigOi out = new OiBigOiService().bigOi(latest, 2);

    assertThat(out.items()).hasSize(2);
    assertThat(out.items().get(0).strike()).isEqualByComparingTo("22500"); // |-900|
    assertThat(out.items().get(0).oiChange()).isEqualTo(-900L);
    assertThat(out.items().get(1).strike()).isEqualByComparingTo("22600"); // |300|
  }
}
