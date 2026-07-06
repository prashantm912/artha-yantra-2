package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.arthayantra.strategysignal.signals.Books;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Pins the audit-H12 gap: per-book paper isolation was untested at the DB layer. Exercises the REAL
 * V021 partial-unique index (two books may each hold the same (exchange, symbol, side) OPEN at once,
 * but one book cannot double-open — closing frees a re-open), the reset SCOPING (wiping one book
 * leaves another's ledger intact), and {@link BookResolver#bookForSignal} SQL-CASE precedence
 * agreeing with {@link Books#fromTags} (the two encodings of the same rule).
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
class PaperBookIsolationIntegrationTest extends StrategySignalIntegrationTestBase {

  @Autowired private PaperPositionRepository positions;
  @Autowired private BookResolver bookResolver;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void twoBooksMayEachHoldTheSameSymbolOpenButOneBookCannotDoubleOpen() {
    String sym = "ISO-" + UUID.randomUUID();
    BigDecimal px = new BigDecimal("100");

    long scalperId = positions.insertOpen(Books.SCALPER, "NSE", sym, "BUY", 1, px, null, null);
    long minerviniId = positions.insertOpen(Books.MINERVINI, "NSE", sym, "BUY", 1, px, null, null);

    // Isolation: BOTH books hold the same (exchange, symbol, side) OPEN simultaneously.
    assertThat(scalperId).isPositive();
    assertThat(minerviniId).isPositive();
    assertThat(positions.findOpen(Books.SCALPER, "NSE", sym, "BUY")).isPresent();
    assertThat(positions.findOpen(Books.MINERVINI, "NSE", sym, "BUY")).isPresent();

    // But a SECOND open on the same book+key violates the per-book partial-unique index.
    assertThatThrownBy(() -> positions.insertOpen(Books.SCALPER, "NSE", sym, "BUY", 1, px, null, null))
        .isInstanceOf(DataIntegrityViolationException.class);

    // The index is partial (WHERE status='OPEN') — closing the scalper lot frees a re-open.
    assertThat(positions.close(scalperId, BigDecimal.ZERO, "TEST")).isEqualTo(1);
    long reopened = positions.insertOpen(Books.SCALPER, "NSE", sym, "BUY", 1, px, null, null);
    assertThat(reopened).isPositive();
  }

  @Test
  void wipingOneBooksLedgerLeavesAnotherBooksPositionsIntact() {
    String scalperSym = "ISO-" + UUID.randomUUID();
    String minerviniSym = "ISO-" + UUID.randomUUID();
    BigDecimal px = new BigDecimal("100");
    positions.insertOpen(Books.SCALPER, "NSE", scalperSym, "BUY", 1, px, null, null);
    positions.insertOpen(Books.MINERVINI, "NSE", minerviniSym, "BUY", 1, px, null, null);

    positions.deleteAll(Books.SCALPER); // the per-book reset SQL (book != null → only that book)

    assertThat(positions.findOpen(Books.SCALPER, "NSE", scalperSym, "BUY")).isEmpty();
    assertThat(positions.findOpen(Books.MINERVINI, "NSE", minerviniSym, "BUY")).isPresent();
  }

  @Test
  void bookForSignalSqlPrecedenceMatchesBooksFromTags() {
    // A strategy tagged with two families resolves to the higher-precedence one in BOTH encodings.
    long signalId = seedSignalForStrategyTagged("minervini", "manas-arora");
    assertThat(bookResolver.bookForSignal(signalId)).isEqualTo(Books.MINERVINI);
    assertThat(bookResolver.bookForSignal(signalId))
        .isEqualTo(Books.fromTags(java.util.List.of("minervini", "manas-arora")));

    // A strategy in no known family resolves to OTHER (not MANUAL — that is for signalless hand orders).
    long orphan = seedSignalForStrategyTagged("momentum");
    assertThat(bookResolver.bookForSignal(orphan)).isEqualTo(Books.OTHER);

    // A missing signal → OTHER (the fail-open default).
    assertThat(bookResolver.bookForSignal(-1L)).isEqualTo(Books.OTHER);
  }

  /** Seeds strategy → version → signal with the given family tags; returns the signal id. */
  private long seedSignalForStrategyTagged(String... tags) {
    String suffix = UUID.randomUUID().toString();
    // family tags are bare identifiers (scalper/minervini/manas-arora/momentum) so a plain PG array
    // literal needs no quoting/escaping; a raw String[] would need Connection.createArrayOf.
    String tagsLiteral = "{" + String.join(",", tags) + "}";
    UUID strategyId =
        jdbc.queryForObject(
            "INSERT INTO strategies (slug, name, tags) VALUES (?, ?, ?::text[]) RETURNING id",
            UUID.class,
            "iso-" + suffix,
            "ISO " + suffix,
            tagsLiteral);
    UUID versionId =
        jdbc.queryForObject(
            """
            INSERT INTO strategy_versions
              (strategy_id, version, config_yaml, config, schema_version, checksum, status)
            VALUES (?, '1', '', '{}'::jsonb, '1', ?, 'published') RETURNING id
            """,
            UUID.class,
            strategyId,
            "chk-" + suffix);
    return jdbc.queryForObject(
        """
        INSERT INTO signals
          (strategy_version_id, exchange, tradingsymbol, "interval", signal_type, side,
           composite_score, score_breakdown)
        VALUES (?, 'NSE', 'ISOSIG', '1m', 'ENTRY', 'BUY', 0.7, '{}'::jsonb) RETURNING id
        """,
        Long.class,
        versionId);
  }
}
