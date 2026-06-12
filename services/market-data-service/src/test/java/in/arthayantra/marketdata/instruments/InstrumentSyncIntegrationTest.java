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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ContextConfiguration;

/**
 * Phase-9 IT battery: batched sync, tombstoning, NUMERIC roundtrip, search ranking,
 * expiries/strikes from the NFO ladder, and the D10 grant test (ay_backtest SELECT-yes /
 * INSERT-no).
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
@ContextConfiguration(classes = InstrumentSyncIntegrationTest.MutableDump.class)
class InstrumentSyncIntegrationTest extends MarketDataIntegrationTestBase {

  /** A dump gateway whose contents tests can swap between syncs. */
  @TestConfiguration
  static class MutableDump {
    static final AtomicReference<List<InstrumentRecord>> ROWS =
        new AtomicReference<>(new MockInstrumentDumpGateway().fetchDump());

    @Bean
    @Primary
    InstrumentDumpGateway mutableDumpGateway() {
      return () -> ROWS.get();
    }
  }

  @Autowired private InstrumentSyncService syncService;
  @Autowired private InstrumentRepository repository;
  @Autowired private InstrumentRegistry registry;

  @BeforeEach
  void fullFixture() {
    MutableDump.ROWS.set(new MockInstrumentDumpGateway().fetchDump());
  }

  @Test
  void syncPersistsFixtureWithStableKeysAndExactDecimals() throws SQLException {
    var status = syncService.runSync();

    assertThat(status.state()).isEqualTo("OK");
    assertThat(repository.countActive()).isEqualTo(MutableDump.ROWS.get().size());
    // NUMERIC roundtrip — exact decimals, no float drift (B-7)
    Map<String, Object> nifty = repository.rawRow("NSE", "NIFTY 50");
    assertThat(((BigDecimal) nifty.get("tick_size"))).isEqualByComparingTo("0.05");
    // sync runtime budget: ≤ 5 s for the ~5k fixture (acceptance)
    assertThat(status.durationMs()).isLessThan(5_000);
    // token map rebuilt (B-3 step 6)
    assertThat(registry.keyForToken(256265L)).isPresent();
  }

  @Test
  void vanishedRowsAreTombstonedNeverDeleted() {
    syncService.runSync();
    final long before = repository.countActive();

    List<InstrumentRecord> reduced = new ArrayList<>(MutableDump.ROWS.get());
    InstrumentRecord removed =
        reduced.stream().filter(r -> r.tradingsymbol().equals("RELIANCE")).findFirst().orElseThrow();
    reduced.remove(removed);
    MutableDump.ROWS.set(reduced);
    syncService.runSync();

    assertThat(repository.countActive()).isEqualTo(before - 1);
    var reliance = repository.findByKey("NSE", "RELIANCE").orElseThrow();
    assertThat(reliance.active()).as("tombstoned, not deleted").isFalse();
    // token no longer resolvable once inactive
    assertThat(registry.keyForToken(removed.instrumentToken())).isEmpty();
  }

  @Test
  void searchRanksPrefixMatchesFirst() {
    syncService.runSync();

    List<Instrument> hits = repository.searchRanked("RELI", 10);

    assertThat(hits).isNotEmpty();
    assertThat(hits.get(0).tradingsymbol()).isEqualTo("RELIANCE");
  }

  @Test
  void expiriesAndStrikesComeFromTheNfoLadder() {
    syncService.runSync();

    var expiries = repository.expiries("NIFTY 50");
    assertThat(expiries).isNotEmpty();
    var strikes = repository.strikes("NIFTY 50", expiries.get(0));
    assertThat(strikes).isNotEmpty();
    assertThat(strikes).isSorted();
  }

  @Test
  void backtestRoleCanSelectButNeverInsert() throws SQLException {
    syncService.runSync();

    try (Connection connection = DriverManager.getConnection(jdbcUrl(), dbUser(), dbPassword());
        Statement statement = connection.createStatement()) {
      statement.execute("SET ROLE ay_backtest");
      // SELECT allowed via the single cross-schema grant (CD-1)
      try (var rs = statement.executeQuery("SELECT count(*) FROM marketdata.instruments")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getLong(1)).isGreaterThan(0);
      }
      // INSERT denied — single-writer rule (D10)
      assertThatThrownBy(
              () ->
                  statement.execute(
                      "INSERT INTO marketdata.instruments (exchange, tradingsymbol) VALUES ('X','Y')"))
          .hasMessageContaining("permission denied");
    }
  }
}
