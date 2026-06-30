package in.arthayantra.marketdata.instruments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.arthayantra.marketdata.kite.InstrumentDumpGateway;
import in.arthayantra.marketdata.kite.InstrumentDumpGateway.InstrumentRecord;
import in.arthayantra.marketdata.mockfeed.MockInstrumentDumpGateway;
import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

/** Phase-9A battery: FIRST_SEEN accrual, exact CHANGED diffs, as-of boundaries, honesty flag. */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
@ContextConfiguration(classes = ContractSpecHistoryIntegrationTest.TestKnobs.class)
class ContractSpecHistoryIntegrationTest extends MarketDataIntegrationTestBase {

  private static final String FUT_SYMBOL = "NIFTY26JUNFUT";

  /** Mutable dump + mutable clock so two "days" of syncs run in one test. */
  @TestConfiguration
  static class TestKnobs {
    static final AtomicReference<List<InstrumentRecord>> ROWS = new AtomicReference<>();
    static final AtomicReference<Instant> NOW =
        new AtomicReference<>(Instant.parse("2026-06-10T05:00:00Z"));

    @Bean
    @Primary
    InstrumentDumpGateway mutableDump() {
      return () -> ROWS.get();
    }

    @Bean
    @Primary
    Clock mutableClock() {
      return new Clock() {
        @Override
        public ZoneOffset getZone() {
          return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
          return this; // instant-based; LocalDate.now uses instant+IST internally
        }

        @Override
        public Instant instant() {
          return NOW.get();
        }
      };
    }
  }

  @Autowired private InstrumentSyncService syncService;
  @Autowired private ContractSpecResolver resolver;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM contract_spec_history");
    jdbc.update("DELETE FROM instruments");
    TestKnobs.ROWS.set(new MockInstrumentDumpGateway().fetchDump());
    TestKnobs.NOW.set(Instant.parse("2026-06-10T05:00:00Z"));
  }

  private List<InstrumentRecord> withChangedLot(List<InstrumentRecord> rows, int newLot) {
    return rows.stream()
        .map(
            r ->
                r.tradingsymbol().equals(FUT_SYMBOL)
                    ? new InstrumentRecord(
                        r.instrumentToken(), r.exchangeToken(), r.exchange(), r.tradingsymbol(),
                        r.name(),
                        r.instrumentType(), r.segment(), r.expiry(), r.strike(), newLot,
                        r.tickSize())
                    : r)
        .toList();
  }

  @Test
  void accrualLifecycleAndAsOfResolution() {
    // day 1: everything is FIRST_SEEN
    syncService.runSync();
    Long firstSeen =
        jdbc.queryForObject(
            "SELECT count(*) FROM contract_spec_history WHERE change_type='FIRST_SEEN'", Long.class);
    assertThat(firstSeen).isEqualTo(TestKnobs.ROWS.get().size());
    Long nfoSeen =
        jdbc.queryForObject(
            "SELECT count(*) FROM contract_spec_history WHERE change_type='FIRST_SEEN' AND exchange='NFO'",
            Long.class);
    assertThat(nfoSeen).as("the F&O ladder accrues from the very first sync").isGreaterThan(0);

    // day 2: one FUT lot change 75 -> 25; everything else identical
    TestKnobs.NOW.set(Instant.parse("2026-06-11T05:00:00Z"));
    TestKnobs.ROWS.set(withChangedLot(TestKnobs.ROWS.get(), 25));
    syncService.runSync();

    List<java.util.Map<String, Object>> day2 =
        jdbc.queryForList("SELECT * FROM contract_spec_history WHERE as_of_date = DATE '2026-06-11'");
    assertThat(day2).as("exactly one CHANGED row, zero churn on unchanged rows").hasSize(1);
    assertThat(day2.get(0).get("change_type")).isEqualTo("CHANGED");
    assertThat(day2.get(0).get("tradingsymbol")).isEqualTo(FUT_SYMBOL);
    assertThat(day2.get(0).get("lot_size")).isEqualTo(25);
    assertThat(day2.get(0).get("prev_lot_size")).isEqualTo(75);

    // as-of: between accrual start and the change -> OLD lot, no flag
    var beforeChange = resolver.resolve("NFO", FUT_SYMBOL, LocalDate.parse("2026-06-10"));
    assertThat(beforeChange.lotSize()).isEqualTo(75);
    assertThat(beforeChange.specAsofEstimated()).isFalse();

    // boundary: the change date itself resolves to the NEW lot
    var onChange = resolver.resolve("NFO", FUT_SYMBOL, LocalDate.parse("2026-06-11"));
    assertThat(onChange.lotSize()).isEqualTo(25);
    assertThat(onChange.specAsofEstimated()).isFalse();

    // pre-accrual window -> current spec + the mandatory honesty flag
    var preAccrual = resolver.resolve("NFO", FUT_SYMBOL, LocalDate.parse("2022-01-03"));
    assertThat(preAccrual.lotSize()).isEqualTo(25);
    assertThat(preAccrual.specAsofEstimated()).isTrue();
    assertThat(preAccrual.tickSize()).isEqualByComparingTo(new BigDecimal("0.05"));
  }

  @Test
  void backtestRoleCanSelectButNeverInsertOnSpecHistory() throws Exception {
    syncService.runSync();
    try (Connection connection = DriverManager.getConnection(jdbcUrl(), dbUser(), dbPassword());
        Statement statement = connection.createStatement()) {
      statement.execute("SET ROLE ay_backtest");
      try (var rs =
          statement.executeQuery("SELECT count(*) FROM marketdata.contract_spec_history")) {
        assertThat(rs.next()).isTrue();
      }
      assertThatThrownBy(
              () ->
                  statement.execute(
                      "INSERT INTO marketdata.contract_spec_history "
                          + "(exchange, tradingsymbol, as_of_date, change_type) "
                          + "VALUES ('X','Y',CURRENT_DATE,'FIRST_SEEN')"))
          .hasMessageContaining("permission denied");
    }
  }
}
