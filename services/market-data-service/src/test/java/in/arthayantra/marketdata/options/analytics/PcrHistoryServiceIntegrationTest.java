package in.arthayantra.marketdata.options.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.options.OiInterval;
import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
class PcrHistoryServiceIntegrationTest extends MarketDataIntegrationTestBase {

  @Autowired JdbcTemplate jdbc;
  @Autowired PcrHistoryService pcr;

  @Test
  void computesPcrPerBucketFromSummedOi() {
    String u = "PCRTEST";
    LocalDate exp = LocalDate.of(2026, 6, 25);
    OffsetDateTime t0 =
        OffsetDateTime.of(2026, 6, 20, 9, 16, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    // CE OI total 1000, PE OI total 1500 → PCR 1.5000
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, t0, u, exp, "22500", "CE", "100", 1000L, 0L);
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, t0, u, exp, "22500", "PE", "90", 1500L, 0L);

    List<PcrHistoryService.PcrPoint> pts =
        pcr.history(u, exp, OiInterval.M5, t0.minusMinutes(1), t0.plusMinutes(6));

    assertThat(pts).hasSize(1);
    assertThat(pts.get(0).pcr()).isEqualByComparingTo("1.5000");
  }
}
