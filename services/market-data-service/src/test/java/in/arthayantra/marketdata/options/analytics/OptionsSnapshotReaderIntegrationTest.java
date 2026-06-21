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

  @Test
  void latestPairReturnsTwoMostRecentBuckets() {
    String u = "PAIRTEST";
    LocalDate exp = LocalDate.of(2026, 6, 25);
    OffsetDateTime b0 =
        OffsetDateTime.of(2026, 6, 20, 9, 16, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OffsetDateTime b1 = b0.plusMinutes(5); // next 5-min bucket
    OffsetDateTime b2 = b0.plusMinutes(10); // newest 5-min bucket
    insertRow(jdbc, b0, u, exp, "22500", "CE", "100.00", 1000L, 0L);
    insertRow(jdbc, b1, u, exp, "22500", "CE", "110.00", 1200L, 0L);
    insertRow(jdbc, b2, u, exp, "22500", "CE", "130.00", 1500L, 0L);

    List<OptionsSnapshotReader.StrikePoint> pair = reader.latestPair(u, exp, OiInterval.M5);

    // exactly the two newest buckets (b1, b2), oldest-first — b0 excluded
    assertThat(pair).extracting(OptionsSnapshotReader.StrikePoint::oi).containsExactly(1200L, 1500L);
  }

  @Test
  void latestHonorsDateInHistoryMode() {
    String u = "READER_HIST";
    LocalDate exp = LocalDate.of(2026, 6, 25);
    OffsetDateTime day1 =
        OffsetDateTime.of(2026, 6, 18, 10, 0, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OffsetDateTime day2 =
        OffsetDateTime.of(2026, 6, 19, 10, 0, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    insertRow(jdbc, day1, u, exp, "22500", "CE", "100.00", 1111L, 0L);
    insertRow(jdbc, day2, u, exp, "22500", "CE", "200.00", 2222L, 0L);

    List<OptionsSnapshotReader.StrikePoint> d1 =
        reader.latest(u, exp, OiInterval.M5, LocalDate.of(2026, 6, 18));

    assertThat(d1).hasSize(1);
    assertThat(d1.get(0).oi()).isEqualTo(1111L); // day2 excluded by the IST-day window
  }

  @Test
  void seriesPopulatesVolume() {
    String u = "READER_VOL";
    LocalDate exp = LocalDate.of(2026, 6, 25);
    OffsetDateTime t0 =
        OffsetDateTime.of(2026, 6, 20, 9, 16, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    insertRow(jdbc, t0, u, exp, "22500", "CE", "100.00", 1000L, 0L, 7777L);

    List<OptionsSnapshotReader.StrikePoint> pts =
        reader.series(u, exp, OiInterval.M5, t0.minusMinutes(1), t0.plusMinutes(6));

    assertThat(pts).hasSize(1);
    assertThat(pts.get(0).volume()).isEqualTo(7777L);
  }

  @Test
  void sessionStatsDerivesOhlcVolumeAndPrevClose() {
    String u = "READER_SESS";
    LocalDate exp = LocalDate.of(2026, 6, 25);
    LocalDate session = LocalDate.of(2026, 6, 19); // Friday (trading day)
    LocalDate prior = LocalDate.of(2026, 6, 18); // Thursday (prior trading day)

    // Prior session: newest bucket ltp per strike → prevClose.
    OffsetDateTime p0 =
        OffsetDateTime.of(2026, 6, 18, 14, 0, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    insertRow(jdbc, p0, u, exp, "22500", "CE", "80.00", 500L, 0L, 1000L);
    insertRow(jdbc, p0.plusMinutes(5), u, exp, "22500", "CE", "85.00", 600L, 0L, 1200L);

    // Session: 5 buckets, ltp rises 100→140 then falls 140→110→90 (decline buckets), cumulative vol.
    OffsetDateTime s0 =
        OffsetDateTime.of(2026, 6, 19, 9, 16, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    insertRow(jdbc, s0, u, exp, "22500", "CE", "100.00", 1000L, 0L, 2000L); // open
    insertRow(jdbc, s0.plusMinutes(5), u, exp, "22500", "CE", "140.00", 1100L, 0L, 2300L); // high
    insertRow(jdbc, s0.plusMinutes(10), u, exp, "22500", "CE", "110.00", 1200L, 0L, 2500L); // decline
    insertRow(jdbc, s0.plusMinutes(15), u, exp, "22500", "CE", "90.00", 1300L, 0L, 2600L); // decline/low
    insertRow(jdbc, s0.plusMinutes(20), u, exp, "22500", "CE", "120.00", 1400L, 0L, 3000L); // last (still < high)

    List<OptionsSnapshotReader.PerStrikeSessionStat> stats =
        reader.sessionStats(u, exp, session, 5);

    assertThat(stats).hasSize(1);
    OptionsSnapshotReader.PerStrikeSessionStat s = stats.get(0);
    assertThat(s.optionType()).isEqualTo("CE");
    assertThat(s.open()).isEqualByComparingTo("100.00");
    assertThat(s.high()).isEqualByComparingTo("140.00");
    assertThat(s.low()).isEqualByComparingTo("90.00");
    assertThat(s.last()).isEqualByComparingTo("120.00");
    assertThat(s.dayVolume()).isEqualTo(1000L); // 3000 - 2000 cumulative
    // decline buckets vs running-high(140): 110 (2500-2300=200) + 90 (2600-2500=100)
    //   + 120 (3000-2600=400, still below 140) = 700
    assertThat(s.declineVolume()).isEqualTo(700L);
    assertThat(s.prevClose()).isEqualByComparingTo("85.00"); // prior session newest bucket
  }

  @Test
  void sessionStatsPrevCloseNullWhenNoPriorSession() {
    String u = "READER_NOPRIOR";
    LocalDate exp = LocalDate.of(2026, 6, 25);
    LocalDate session = LocalDate.of(2026, 6, 19);
    OffsetDateTime s0 =
        OffsetDateTime.of(2026, 6, 19, 9, 16, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    insertRow(jdbc, s0, u, exp, "22500", "CE", "100.00", 1000L, 0L, 2000L);

    List<OptionsSnapshotReader.PerStrikeSessionStat> stats =
        reader.sessionStats(u, exp, session, 5);

    assertThat(stats).hasSize(1);
    assertThat(stats.get(0).prevClose()).isNull();
  }

  @Test
  void sessionStatsEmptyWhenNoSnapshots() {
    assertThat(
            reader.sessionStats(
                "READER_EMPTY", LocalDate.of(2026, 6, 25), LocalDate.of(2026, 6, 19), 5))
        .isEmpty();
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
    insertRow(jdbc, ts, u, exp, strike, type, ltp, oi, oiChange, null);
  }

  /** As above, with an explicit cumulative {@code volume}. */
  static void insertRow(
      JdbcTemplate jdbc,
      OffsetDateTime ts,
      String u,
      LocalDate exp,
      String strike,
      String type,
      String ltp,
      Long oi,
      Long oiChange,
      Long volume) {
    jdbc.update(
        "INSERT INTO options_chain_snapshots "
            + "(ts, underlying, expiry, strike, option_type, tradingsymbol, ltp, oi, oi_change, volume, spot_price) "
            + "VALUES (?,?,?,?::numeric,?,?,?::numeric,?,?,?,?::numeric) "
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
        volume,
        "22480.00");
  }

  /** As above, plus an explicit per-leg {@code iv} (the Active Strikes IV series reads it). */
  static void insertRow(
      JdbcTemplate jdbc,
      OffsetDateTime ts,
      String u,
      LocalDate exp,
      String strike,
      String type,
      String ltp,
      Long oi,
      Long oiChange,
      Long volume,
      String iv) {
    jdbc.update(
        "INSERT INTO options_chain_snapshots "
            + "(ts, underlying, expiry, strike, option_type, tradingsymbol, ltp, oi, oi_change, volume,"
            + " spot_price, iv) "
            + "VALUES (?,?,?,?::numeric,?,?,?::numeric,?,?,?,?::numeric,?::numeric) "
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
        volume,
        "22480.00",
        iv);
  }
}
