package in.arthayantra.backtest.replay.options;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.arthayantra.backtest.replay.options.SnapshotPremiumReader.PremiumPoint;
import in.arthayantra.backtest.testsupport.BacktestIntegrationTestBase;
import in.arthayantra.common.web.error.ApiException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Phase-30A snapshot-reader IT against a real Timescale stack: seed {@code
 * marketdata.options_chain_snapshots} at native 5-min granularity, read a premium series, assert an
 * archive gap → 422 {@code DATA_GAP}, and the sub-5m snapshot-grade refusal.
 */
@SpringBootTest(
    properties = {"spring.profiles.active=mock", "artha.backtest.worker-pool-enabled=false"})
class SnapshotPremiumReaderIntegrationTest extends BacktestIntegrationTestBase {

  private static final String UNDERLYING = "NIFTY";
  private static final LocalDate EXPIRY = LocalDate.of(2026, 1, 8);
  private static final BigDecimal STRIKE = new BigDecimal("21500.00");
  private static final String CE = "CE";
  // a one-hour window fully inside a single trading session (2026-01-05 is a Monday)
  private static final OffsetDateTime FROM = OffsetDateTime.parse("2026-01-05T10:00:00+05:30");
  private static final OffsetDateTime TO = OffsetDateTime.parse("2026-01-05T11:00:00+05:30");

  @Autowired private SnapshotPremiumReader reader;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update(
        "DELETE FROM marketdata.options_chain_snapshots WHERE underlying=? AND expiry=?",
        UNDERLYING,
        EXPIRY);
  }

  @Test
  void readsTheFiveMinutePremiumSeriesInOrder() {
    seedSlots(FROM, TO); // 12 slots over a full hour: 10:00,10:05,...,10:55
    List<PremiumPoint> series = reader.read(UNDERLYING, EXPIRY, STRIKE, CE, FROM, TO);

    assertThat(series).hasSize(12);
    assertThat(series.get(0).ts()).isEqualTo(FROM);
    assertThat(series.get(0).premium()).isEqualByComparingTo(new BigDecimal("100.0000"));
    // strictly increasing timestamps at exactly the 5-min cadence
    for (int i = 1; i < series.size(); i++) {
      assertThat(Duration.between(series.get(i - 1).ts(), series.get(i).ts()))
          .isEqualTo(Duration.ofMinutes(5));
    }
  }

  @Test
  void archiveGapRaises422DataGap() {
    // seed all but the last slot → present < expected
    seedSlots(FROM, TO.minusMinutes(5));
    assertThatThrownBy(() -> reader.read(UNDERLYING, EXPIRY, STRIKE, CE, FROM, TO))
        .isInstanceOfSatisfying(
            ApiException.class,
            ex -> {
              assertThat(ex.httpStatus()).isEqualTo(422);
              assertThat(ex.code()).isEqualTo("DATA_GAP");
              assertThat(ex.details()).containsKeys("missingSlots", "expectedSlots");
            });
  }

  @Test
  void offCadenceRowsDoNotCountTowardCoverage() {
    // seed all but the last aligned slot, then add an off-cadence (09:17-style) row inside the
    // window: a naive count(*) would reach `expected` and mask the real gap, but the slot-aligned
    // coverage check must still raise DATA_GAP because a genuine 5-min slot is missing.
    seedSlots(FROM, TO.minusMinutes(5));
    jdbc.update(
        "INSERT INTO marketdata.options_chain_snapshots "
            + "(ts, underlying, expiry, strike, option_type, tradingsymbol, ltp, spot_price) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        FROM.plusMinutes(52), // 10:52 — not on the 5-min grid
        UNDERLYING,
        EXPIRY,
        STRIKE,
        CE,
        "NIFTY26108" + STRIKE.intValue() + CE,
        new BigDecimal("100.0000"),
        new BigDecimal("21600.0000"));
    assertThatThrownBy(() -> reader.read(UNDERLYING, EXPIRY, STRIKE, CE, FROM, TO))
        .isInstanceOfSatisfying(
            ApiException.class, ex -> assertThat(ex.code()).isEqualTo("DATA_GAP"));
  }

  @Test
  void subFiveMinutePrimaryIsRefusedSnapshotGrade() {
    assertThatThrownBy(() -> reader.refuseSubFiveMinute("1m"))
        .isInstanceOfSatisfying(
            ApiException.class,
            ex -> {
              assertThat(ex.httpStatus()).isEqualTo(400);
              assertThat(ex.code()).isEqualTo("VALIDATION_INTERVAL_UNSUPPORTED");
              assertThat(ex.getMessage()).contains("native 5-minute granularity");
            });
  }

  @Test
  void fiveMinuteAndCoarserPrimariesAreAccepted() {
    // no throw
    reader.refuseSubFiveMinute("5m");
    reader.refuseSubFiveMinute("15m");
    reader.refuseSubFiveMinute("1h");
  }

  private void seedSlots(OffsetDateTime from, OffsetDateTime toExclusive) {
    List<OffsetDateTime> slots = new ArrayList<>();
    for (OffsetDateTime ts = from; ts.isBefore(toExclusive); ts = ts.plusMinutes(5)) {
      slots.add(ts);
    }
    jdbc.batchUpdate(
        "INSERT INTO marketdata.options_chain_snapshots "
            + "(ts, underlying, expiry, strike, option_type, tradingsymbol, ltp, spot_price) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        slots,
        slots.size(),
        (ps, ts) -> {
          ps.setObject(1, ts);
          ps.setString(2, UNDERLYING);
          ps.setObject(3, EXPIRY);
          ps.setBigDecimal(4, STRIKE);
          ps.setString(5, CE);
          ps.setString(6, "NIFTY26108" + STRIKE.intValue() + CE);
          ps.setBigDecimal(7, new BigDecimal("100.0000"));
          ps.setBigDecimal(8, new BigDecimal("21600.0000"));
        });
  }
}
