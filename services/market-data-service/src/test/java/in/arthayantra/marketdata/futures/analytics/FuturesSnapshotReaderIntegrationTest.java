package in.arthayantra.marketdata.futures.analytics;

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
class FuturesSnapshotReaderIntegrationTest extends MarketDataIntegrationTestBase {

  @Autowired JdbcTemplate jdbc;
  @Autowired FuturesSnapshotReader reader;

  private void insert(String u, OffsetDateTime ts, String symbol, long oi, long oiChange) {
    jdbc.update(
        "INSERT INTO futures_oi_snapshots (ts, underlying, tradingsymbol, expiry, ltp, volume, oi, oi_change) "
            + "VALUES (?,?,?,?,?::numeric,?,?,?) ON CONFLICT DO NOTHING",
        java.sql.Timestamp.from(ts.toInstant()),
        u,
        symbol,
        java.sql.Date.valueOf(LocalDate.of(2026, 6, 25)),
        "100.00",
        10L,
        oi,
        oiChange);
  }

  @Test
  void downsamplesPerContractToLastValue() {
    String u = "FUTREAD";
    OffsetDateTime t0 =
        OffsetDateTime.of(2026, 6, 20, 9, 16, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    insert(u, t0, u + "26JUNFUT", 5000L, 200L);

    List<FuturesSnapshotReader.FutPoint> pts =
        reader.series(u, OiInterval.M5, t0.minusMinutes(1), t0.plusMinutes(6));

    assertThat(pts).hasSize(1);
    assertThat(pts.get(0).tradingsymbol()).isEqualTo(u + "26JUNFUT");
    assertThat(pts.get(0).oi()).isEqualTo(5000L);
  }

  @Test
  void latestPairReturnsTwoMostRecentBucketsPerContract() {
    String u = "FUTPAIR";
    OffsetDateTime b0 =
        OffsetDateTime.of(2026, 6, 20, 9, 16, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OffsetDateTime b1 = b0.plusMinutes(5);
    OffsetDateTime b2 = b0.plusMinutes(10);
    insert(u, b0, u + "26JUNFUT", 1000L, 0L);
    insert(u, b1, u + "26JUNFUT", 1200L, 0L);
    insert(u, b2, u + "26JUNFUT", 1500L, 0L);

    List<FuturesSnapshotReader.FutPoint> pair = reader.latestPair(u, OiInterval.M5);

    // exactly the two newest buckets (b1, b2), oldest-first
    assertThat(pair).extracting(FuturesSnapshotReader.FutPoint::oi).containsExactly(1200L, 1500L);
  }

  @Test
  void latestHonorsDateInHistoryMode() {
    String u = "FUTHIST";
    OffsetDateTime day1 =
        OffsetDateTime.of(2026, 6, 18, 10, 0, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OffsetDateTime day2 =
        OffsetDateTime.of(2026, 6, 19, 10, 0, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    insert(u, day1, u + "26JUNFUT", 1111L, 0L);
    insert(u, day2, u + "26JUNFUT", 2222L, 0L);

    List<FuturesSnapshotReader.FutPoint> d1 =
        reader.latest(u, OiInterval.M5, LocalDate.of(2026, 6, 18));

    assertThat(d1).hasSize(1);
    assertThat(d1.get(0).oi()).isEqualTo(1111L); // day2 excluded by the IST-day window
  }

  private void insertExp(
      String u, OffsetDateTime ts, String sym, long oi, LocalDate expiry) {
    jdbc.update(
        "INSERT INTO futures_oi_snapshots (ts, underlying, tradingsymbol, expiry, ltp, volume, oi, oi_change) "
            + "VALUES (?,?,?,?,?::numeric,?,?,?) ON CONFLICT DO NOTHING",
        java.sql.Timestamp.from(ts.toInstant()),
        u,
        sym,
        java.sql.Date.valueOf(expiry),
        "100.00",
        10L,
        oi,
        0L);
  }

  @Test
  void latestPairAllTagsUnderlyingAcrossTheSet() {
    OffsetDateTime b0 =
        OffsetDateTime.of(2026, 6, 20, 9, 16, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OffsetDateTime b1 = b0.plusMinutes(5);
    LocalDate jun = LocalDate.of(2026, 6, 25);
    insertExp("GRIDA", b0, "GRIDA26JUNFUT", 1000L, jun);
    insertExp("GRIDA", b1, "GRIDA26JUNFUT", 1100L, jun);
    insertExp("GRIDB", b0, "GRIDB26JUNFUT", 2000L, jun);
    insertExp("GRIDB", b1, "GRIDB26JUNFUT", 2200L, jun);

    List<FuturesSnapshotReader.FutPoint> pair =
        reader.latestPairAll(List.of("GRIDA", "GRIDB"), OiInterval.M5, null);

    assertThat(pair)
        .extracting(FuturesSnapshotReader.FutPoint::underlying)
        .contains("GRIDA", "GRIDB");
    assertThat(pair).hasSize(4); // 2 underlyings x 2 buckets
  }

  private void insertOhlc(
      String u,
      OffsetDateTime ts,
      String sym,
      String ltp,
      long oi,
      long oiChange,
      String dayOpen,
      String dayHigh,
      String dayLow) {
    jdbc.update(
        "INSERT INTO futures_oi_snapshots "
            + "(ts, underlying, tradingsymbol, expiry, ltp, volume, oi, oi_change, "
            + " day_open, day_high, day_low) "
            + "VALUES (?,?,?,?,?::numeric,?,?,?,?::numeric,?::numeric,?::numeric) ON CONFLICT DO NOTHING",
        java.sql.Timestamp.from(ts.toInstant()),
        u,
        sym,
        java.sql.Date.valueOf(LocalDate.of(2026, 6, 25)),
        ltp,
        100L,
        oi,
        oiChange,
        dayOpen,
        dayHigh,
        dayLow);
  }

  @Test
  void eodRollsUpPerContractPerDay() {
    String u = "FUTEODREAD";
    OffsetDateTime t1 =
        OffsetDateTime.of(2026, 6, 18, 9, 30, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OffsetDateTime t2 =
        OffsetDateTime.of(2026, 6, 18, 15, 20, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    insertOhlc(u, t1, u + "26JUNFUT", "100", 1000L, 50L, "99", "101", "98");
    insertOhlc(u, t2, u + "26JUNFUT", "105", 1100L, 100L, "99", "106", "97");

    List<FuturesSnapshotReader.EodRow> rows =
        reader.eod(u, LocalDate.of(2026, 6, 18), LocalDate.of(2026, 6, 18));

    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).close()).isEqualByComparingTo("105"); // last ltp
    assertThat(rows.get(0).high()).isEqualByComparingTo("106"); // max day_high
    assertThat(rows.get(0).low()).isEqualByComparingTo("97"); // min day_low
    assertThat(rows.get(0).oiClose()).isEqualTo(1100L);
    assertThat(rows.get(0).oiChange()).isEqualTo(150L); // 50 + 100
  }

  @Test
  void surfacesDayOhlcColumns() {
    String u = "FUTOHLC";
    OffsetDateTime t0 =
        OffsetDateTime.of(2026, 6, 20, 9, 16, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    jdbc.update(
        "INSERT INTO futures_oi_snapshots "
            + "(ts, underlying, tradingsymbol, expiry, ltp, volume, oi, oi_change, "
            + " day_open, day_high, day_low, prev_close) "
            + "VALUES (?,?,?,?,?::numeric,?,?,?,?::numeric,?::numeric,?::numeric,?::numeric) "
            + "ON CONFLICT DO NOTHING",
        java.sql.Timestamp.from(t0.toInstant()),
        u,
        u + "26JUNFUT",
        java.sql.Date.valueOf(LocalDate.of(2026, 6, 25)),
        "100.00",
        10L,
        5000L,
        200L,
        "99.50",
        "101.20",
        "98.80",
        "99.00");

    List<FuturesSnapshotReader.FutPoint> pts = reader.latest(u, OiInterval.M5);

    assertThat(pts).hasSize(1);
    assertThat(pts.get(0).dayHigh()).isEqualByComparingTo("101.20");
    assertThat(pts.get(0).dayLow()).isEqualByComparingTo("98.80");
    assertThat(pts.get(0).prevClose()).isEqualByComparingTo("99.00");
    assertThat(pts.get(0).volume()).isEqualTo(10L); // the series() volume projection (Futures OI Analysis)
  }

  @Test
  void latestAnchorsOnMaxTsRegardlessOfWallClock() {
    String u = "FUTLATEST";
    OffsetDateTime t0 =
        OffsetDateTime.of(2026, 6, 20, 9, 16, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    insert(u, t0, u + "26JUNFUT", 7000L, 300L);

    List<FuturesSnapshotReader.FutPoint> pts = reader.latest(u, OiInterval.M5);

    assertThat(pts).hasSize(1);
    assertThat(pts.get(0).oi()).isEqualTo(7000L);
  }
}
