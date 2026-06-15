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
class OptionsSnapshotReaderIntegrationTest extends MarketDataIntegrationTestBase {

  @Autowired JdbcTemplate jdbc;
  @Autowired OptionsSnapshotReader reader;

  @Test
  void downsamplesToLastValuePerBucket() {
    // Two 1-min snapshots inside the same 5-min bucket; last() must win per strike/side.
    String u = "READER_TEST";
    LocalDate exp = LocalDate.of(2026, 6, 25);
    OffsetDateTime t0 =
        OffsetDateTime.of(2026, 6, 20, 9, 16, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OffsetDateTime t1 = t0.plusMinutes(2); // same 5-min bucket as t0
    insertRow(jdbc, t0, u, exp, "22500", "CE", "100.00", 1000L, 10L);
    insertRow(jdbc, t1, u, exp, "22500", "CE", "120.00", 1500L, 25L); // later → wins

    List<OptionsSnapshotReader.StrikePoint> pts =
        reader.series(u, exp, OiInterval.M5, t0.minusMinutes(1), t0.plusMinutes(6));

    assertThat(pts).hasSize(1);
    assertThat(pts.get(0).optionType()).isEqualTo("CE");
    assertThat(pts.get(0).ltp()).isEqualByComparingTo("120.00");
    assertThat(pts.get(0).oi()).isEqualTo(1500L);
  }

  @Test
  void latestReturnsMostRecentBucketRows() {
    String u = "READER_LATEST";
    LocalDate exp = LocalDate.of(2026, 6, 25);
    OffsetDateTime t0 =
        OffsetDateTime.of(2026, 6, 20, 9, 16, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    insertRow(jdbc, t0, u, exp, "22500", "CE", "100.00", 1000L, 0L);
    insertRow(jdbc, t0, u, exp, "22500", "PE", "90.00", 1200L, 0L);

    List<OptionsSnapshotReader.StrikePoint> pts = reader.latest(u, exp, OiInterval.M5);

    assertThat(pts).extracting(OptionsSnapshotReader.StrikePoint::optionType)
        .containsExactlyInAnyOrder("CE", "PE");
  }

  @Test
  void latestReturnsExactlyOneBucketWhenSnapshotsStraddleBucketEdge() {
    // Regression: two snapshots for the SAME strike in ADJACENT 5-min buckets. latest() must
    // return only the most-recent bucket — a rolling [maxTs - width, maxTs] window would span
    // both and double-count the strike's point-in-time OI downstream.
    String u = "READER_STRADDLE";
    LocalDate exp = LocalDate.of(2026, 6, 25);
    OffsetDateTime tA =
        OffsetDateTime.of(2026, 6, 20, 9, 18, 0, 0, ZoneOffset.ofHoursMinutes(5, 30)); // [09:15,09:20)
    OffsetDateTime tB =
        OffsetDateTime.of(2026, 6, 20, 9, 22, 0, 0, ZoneOffset.ofHoursMinutes(5, 30)); // [09:20,09:25)
    insertRow(jdbc, tA, u, exp, "22500", "CE", "100.00", 1000L, 0L);
    insertRow(jdbc, tB, u, exp, "22500", "CE", "200.00", 2000L, 0L);

    List<OptionsSnapshotReader.StrikePoint> pts = reader.latest(u, exp, OiInterval.M5);

    assertThat(pts).hasSize(1); // one bucket, not two
    assertThat(pts.get(0).oi()).isEqualTo(2000L); // the later bucket wins
  }

  /** Helper: minimal insert into options_chain_snapshots (only the columns the reader touches). */
  static void insertRow(
      JdbcTemplate jdbc,
      OffsetDateTime ts,
      String u,
      LocalDate exp,
      String strike,
      String type,
      String ltp,
      Long oi,
      Long oiChange) {
    jdbc.update(
        "INSERT INTO options_chain_snapshots "
            + "(ts, underlying, expiry, strike, option_type, tradingsymbol, ltp, oi, oi_change, spot_price) "
            + "VALUES (?,?,?,?::numeric,?,?,?::numeric,?,?,?::numeric) "
            + "ON CONFLICT DO NOTHING",
        java.sql.Timestamp.from(ts.toInstant()),
        u,
        java.sql.Date.valueOf(exp),
        strike,
        type,
        u + strike + type,
        ltp,
        oi,
        oiChange,
        "22480.00");
  }
}
