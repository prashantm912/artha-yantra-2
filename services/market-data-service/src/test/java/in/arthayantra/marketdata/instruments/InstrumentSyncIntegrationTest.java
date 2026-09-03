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
import java.sql.Timestamp;
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
import org.springframework.jdbc.core.JdbcTemplate;
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
  @Autowired private JdbcTemplate jdbc;

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

  /**
   * Ledger H30: the by-key read answers 200 for any row that exists, so a caller had no way to
   * tell a real instrument from a bare key the historical importer created to hang candles off.
   * {@code masterMetadataMissing} is that distinction — and the three FALSE cases are the
   * load-bearing half: a flag hardwired true, or defined as {@code !active}, would pass the
   * placeholder assertion alone.
   *
   * <p>⚠️ The SYN-CONT case is the only one of the four that separates the CONJUNCTION from
   * {@code instrumentToken == null} alone — the simplification a future editor is most likely to
   * reach for, since the other three fixtures are "all three null" and "all three present". A
   * synthetic continuous-future row is tokenless BY DESIGN yet named and segmented, it is
   * {@code is_active = true} and live, and it is returned by this endpoint — so a token-only
   * regression would ship {@code NIFTY-FUT-CONT} on the wire claiming we know nothing about it.
   */
  @Test
  void byKeyFlagsRowsThatCarryNoMasterMetadata() {
    syncService.runSync();

    // The importer's exact shape (tools/historical-import/ingest.py, _UPSERT_INSTRUMENT):
    // a bare key, is_active=false, no token / name / segment.
    jdbc.update(
        """
        INSERT INTO instruments
          (exchange, tradingsymbol, instrument_type, underlying_exchange,
           underlying_tradingsymbol, expiry, strike, is_active,
           first_seen_at, last_seen_at, updated_at)
        VALUES ('NFO', 'H30IMPORTED25000CE', 'CE', 'NSE', 'NIFTY 50',
                DATE '2024-01-25', 25000, false, now(), now(), now())
        ON CONFLICT (exchange, tradingsymbol) DO NOTHING
        """);
    // A row the master sync DID know and has since tombstoned: inactive, but fully populated.
    jdbc.update(
        """
        INSERT INTO instruments
          (exchange, tradingsymbol, instrument_token, exchange_token, name, segment,
           instrument_type, is_active, first_seen_at, last_seen_at, updated_at)
        VALUES ('NSE', 'H30DELISTEDCO', 930001, 3633, 'H30 Delisted Co', 'NSE', 'EQ',
                false, now(), now(), now())
        ON CONFLICT (exchange, tradingsymbol) DO NOTHING
        """);

    var placeholder = repository.findByKey("NFO", "H30IMPORTED25000CE").orElseThrow();
    assertThat(placeholder.masterMetadataMissing())
        .as("importer placeholder — the master has never populated this row")
        .isTrue();

    var live = repository.findByKey("NSE", "RELIANCE").orElseThrow();
    assertThat(live.active()).isTrue();
    assertThat(live.masterMetadataMissing())
        .as("a real, fully-populated instrument must read FALSE")
        .isFalse();

    var delisted = repository.findByKey("NSE", "H30DELISTEDCO").orElseThrow();
    assertThat(delisted.active()).isFalse();
    assertThat(delisted.masterMetadataMissing())
        .as("inactive but fully populated — the flag is NOT a synonym for !active")
        .isFalse();

    // B-19 synthetic continuous future, seeded through the real writer. A unique symbol: the ITs
    // share one DB with no per-method cleanup, and the real NIFTY-FUT-CONT belongs to
    // ContinuousFuturesIntegrationTest.
    repository.upsertSyntheticCont("NFO", "H30SYNTH-FUT-CONT", "H30SYNTH", "NSE", "H30SYNTH");
    try {
      var synthetic = repository.findByKey("NFO", "H30SYNTH-FUT-CONT").orElseThrow();
      assertThat(synthetic.instrumentToken())
          .as("guards the case below: this row must stay TOKENLESS or it discriminates nothing")
          .isNull();
      assertThat(synthetic.masterMetadataMissing())
          .as("tokenless by design but named — the rule is the CONJUNCTION, not token-only")
          .isFalse();
    } finally {
      // The ONLY fixture in this method that is is_active=true, and SYN-CONT rows are exempt from
      // tombstoning by design — so left behind it would survive every later sync and break
      // syncPersistsFixtureWithStableKeysAndExactDecimals' exact countActive() equality whenever
      // that method happened to run after this one. In a finally block because IT state also
      // persists across surefire reruns: a failing assertion must not poison the next run.
      jdbc.update(
          "DELETE FROM instruments WHERE exchange = 'NFO' AND tradingsymbol = 'H30SYNTH-FUT-CONT'");
    }
  }

  // -----------------------------------------------------------------------------------------------
  // H26 A2-1 — kite_last_seen_at. The column means "KITE'S OWN DUMP asserted this row at this
  // moment", which is deliberately NOT what last_seen_at means (any writer advances that). Later
  // U-A2 units read the distinction to tell a row Kite still publishes from one that merely exists
  // in our table, so a row that gets the stamp WITHOUT Kite asserting it — or fails to get it WITH
  // Kite asserting it — silently corrupts every rule built on top. Nothing reads it yet, which is
  // exactly why it needs pinning now: a defect here is invisible until something trusts it.
  // -----------------------------------------------------------------------------------------------

  @Test
  void syncStampsKiteLastSeenOnRowsTheDumpAsserts() {
    syncService.runSync();

    assertThat(kiteLastSeen("NSE", "RELIANCE"))
        .as("the Kite dump asserted this row, so the column must say so")
        .isNotNull();
  }

  @Test
  void aRESYNCADVANCESKiteLastSeenOnAnExistingRow() {
    // ⚠ THE ASSERTION THAT CATCHES THE OBVIOUS WRONG IMPLEMENTATION. Wiring the column into the
    // INSERT branch alone passes the test above and looks complete — but every row that already
    // existed keeps its old value forever, however many times Kite re-asserts it. The column would
    // then read "Kite has not seen this lately" for precisely the rows Kite publishes most
    // reliably, inverting its meaning. Back-dating makes this deterministic rather than relying on
    // two now() calls seconds apart being distinguishable.
    syncService.runSync();
    jdbc.update(
        "UPDATE instruments SET kite_last_seen_at = TIMESTAMPTZ '2020-01-01T00:00:00+05:30'"
            + " WHERE exchange = 'NSE' AND tradingsymbol = 'RELIANCE'");

    syncService.runSync();

    assertThat(kiteLastSeen("NSE", "RELIANCE"))
        .as("the ON CONFLICT branch must advance it, not only the INSERT")
        .isAfter(Timestamp.valueOf("2021-01-01 00:00:00"));
  }

  @Test
  void anImporterPlaceholderNeverGetsAKiteStamp() {
    // The importer's exact shape (tools/historical-import/ingest.py, _UPSERT_INSTRUMENT): a bare
    // key with no token, name or segment. Kite has never published it, and the whole point of a
    // separate column is that this stays visibly true.
    syncService.runSync();
    jdbc.update(
        """
        INSERT INTO instruments
          (exchange, tradingsymbol, instrument_type, underlying_exchange,
           underlying_tradingsymbol, expiry, strike, is_active,
           first_seen_at, last_seen_at, updated_at)
        VALUES ('NFO', 'A21IMPORTED25000CE', 'CE', 'NSE', 'NIFTY 50',
                DATE '2024-01-25', 25000, false, now(), now(), now())
        ON CONFLICT (exchange, tradingsymbol) DO NOTHING
        """);

    assertThat(kiteLastSeen("NFO", "A21IMPORTED25000CE"))
        .as("an importer placeholder was never in a Kite dump")
        .isNull();
  }

  @Test
  void aSyntheticContinuousRowNeverGetsAKiteStamp() {
    // ⚠ Separate from the importer case on purpose: SYN-CONT rows are written by a DIFFERENT
    // production method (upsertSyntheticCont), so one test cannot demonstrate both. They are
    // tokenless BY DESIGN and are ours, not Kite's — V060's backfill excluded them for the same
    // reason, and a future edit that "helpfully" stamped them would make the column claim Kite
    // publishes a symbol that exists nowhere but here.
    syncService.runSync();
    repository.upsertSyntheticCont(
        "NFO", "A21NIFTY-FUT-CONT", "A21 continuous", "NSE", "NIFTY 50");

    assertThat(kiteLastSeen("NFO", "A21NIFTY-FUT-CONT"))
        .as("a synthetic row is ours, never something Kite asserted")
        .isNull();
  }

  private Timestamp kiteLastSeen(String exchange, String tradingsymbol) {
    return jdbc.queryForObject(
        "SELECT kite_last_seen_at FROM instruments WHERE exchange = ? AND tradingsymbol = ?",
        Timestamp.class,
        exchange,
        tradingsymbol);
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
