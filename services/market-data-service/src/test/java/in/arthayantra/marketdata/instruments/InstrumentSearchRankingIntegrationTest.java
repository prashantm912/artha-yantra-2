package in.arthayantra.marketdata.instruments;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Audit AC-1: a dated future was un-findable in a broad instrument search — for "NIFTY" the synthetic
 * CONT + thousands of CE/PE strikes tie its trigram similarity and win the {@code tradingsymbol}
 * alphabetical tie-break, pushing {@code NIFTY26JULFUT} past the result limit. {@code searchRanked}
 * now boosts non-option instruments above the option flood; this proves the dated future surfaces
 * within a tight limit AND that a full option symbol still ranks its exact match first.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
class InstrumentSearchRankingIntegrationTest extends MarketDataIntegrationTestBase {

  // A unique underlying so no other IT's seeded rows match this query (shared DB, no cleanup).
  private static final String ROOT = "ZZNIFTY";
  private static final String DATED_FUT = ROOT + "26JULFUT";
  private static final String CONT = ROOT + "-FUT-CONT";

  @Autowired private InstrumentRepository repository;
  @Autowired private JdbcTemplate jdbc;

  // Shared DB, no per-method cleanup: remove the seeded rows so a later suite's absolute active-count
  // assertion (InstrumentSyncIntegrationTest) does not see the tombstone-exempt SYN-CONT survivor.
  @AfterEach
  void cleanup() {
    jdbc.update("DELETE FROM instruments WHERE tradingsymbol LIKE ?", ROOT + "%");
  }

  @Test
  void datedFutureSurfacesAboveTheOptionAndContFloodForABroadUnderlyingQuery() {
    seedUniverse();

    List<Instrument> top = repository.searchRanked(ROOT, 5);
    List<String> symbols = top.stream().map(Instrument::tradingsymbol).toList();

    // Without the type boost the ~30 options ("...26JUL10000CE" … ) sort before the future
    // alphabetically and fill the limit, so the dated future never appears in the top 5.
    assertThat(symbols).contains(DATED_FUT);
    // Both futures (dated + CONT) rank above every option strike.
    assertThat(symbols).doesNotContain(ROOT + "26JUL25000CE");
  }

  @Test
  void aFullOptionSymbolStillRanksItsExactMatchFirst() {
    seedUniverse();

    // The boost is additive, not a hard type tier: a strong option match (~1.0 similarity) still wins.
    Instrument first = repository.searchRanked(ROOT + "26JUL25000CE", 5).get(0);
    assertThat(first.tradingsymbol()).isEqualTo(ROOT + "26JUL25000CE");
  }

  private void seedUniverse() {
    insert(DATED_FUT, "FUT", "NFO-FUT");
    insert(CONT, "FUT", "SYN-CONT");
    for (int strike = 10_000; strike < 40_000; strike += 1_000) {
      insert(ROOT + "26JUL" + strike + "CE", "CE", "NFO-OPT");
    }
  }

  private void insert(String tradingsymbol, String instrumentType, String segment) {
    jdbc.update(
        """
        INSERT INTO instruments (exchange, tradingsymbol, instrument_type, segment,
                                 underlying_exchange, underlying_tradingsymbol, is_active)
        VALUES ('NFO', ?, ?, ?, 'NFO', ?, TRUE)
        ON CONFLICT (exchange, tradingsymbol)
        DO UPDATE SET instrument_type = EXCLUDED.instrument_type, is_active = TRUE
        """,
        tradingsymbol, instrumentType, segment, ROOT);
  }
}
