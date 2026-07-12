package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Pins T1 (research-fidelity audit §10): the paper {@code book} is PERSISTED on the signal row at
 * emission (a BEFORE-INSERT trigger derives it from the strategy's family tags), read from the column
 * thereafter, and — the point of the change — FROZEN against a later tag edit that would otherwise
 * silently rewrite which book historical signals belonged to.
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
class SignalBookIntegrationTest extends StrategySignalIntegrationTestBase {

  @Autowired private SignalRepository signals;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void bookIsStampedFromTheStrategyTagsAtInsertAndFrozenAgainstLaterTagEdits() {
    Seed seed = seedTaggedSignal("minervini");

    // The trigger stamped the column from the strategy's family tags at insert.
    assertThat(jdbc.queryForObject("SELECT book FROM signals WHERE id=?", String.class, seed.signalId))
        .isEqualTo("minervini");
    assertThat(signals.bookForSignal(seed.signalId)).isEqualTo(Books.MINERVINI);

    // Retag the strategy AFTER the signal fired — the OLD read-time tags join would now report 'scalper';
    // the persisted column stays 'minervini' (the T1 freeze).
    jdbc.update("UPDATE strategies SET tags='{scalper}'::text[] WHERE id=?", seed.strategyId);
    assertThat(jdbc.queryForObject("SELECT book FROM signals WHERE id=?", String.class, seed.signalId))
        .isEqualTo("minervini");
    assertThat(signals.bookForSignal(seed.signalId)).isEqualTo(Books.MINERVINI);
  }

  @Test
  void listFiltersByThePersistedBookColumn() {
    Seed minervini = seedTaggedSignal("minervini");
    Seed scalper = seedTaggedSignal("scalper");

    List<Long> minerviniIds =
        signals.list(null, "minervini", null, null, null, null, null, 500, 0).stream()
            .map(SignalRepository.SignalRow::id)
            .toList();
    assertThat(minerviniIds).contains(minervini.signalId).doesNotContain(scalper.signalId);
  }

  @Test
  void anUnrecognisedFamilyStampsOther() {
    Seed seed = seedTaggedSignal("momentum");
    assertThat(jdbc.queryForObject("SELECT book FROM signals WHERE id=?", String.class, seed.signalId))
        .isEqualTo("other");
    assertThat(signals.bookForSignal(seed.signalId)).isEqualTo(Books.OTHER);
  }

  private record Seed(UUID strategyId, UUID versionId, long signalId) {}

  /** Seeds strategy → published version → a signal (raw insert; the trigger stamps book). */
  private Seed seedTaggedSignal(String... tags) {
    String suffix = UUID.randomUUID().toString();
    String tagsLiteral = "{" + String.join(",", tags) + "}";
    UUID strategyId =
        jdbc.queryForObject(
            "INSERT INTO strategies (slug, name, tags) VALUES (?, ?, ?::text[]) RETURNING id",
            UUID.class,
            "book-" + suffix,
            "Book " + suffix,
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
    long signalId =
        jdbc.queryForObject(
            """
            INSERT INTO signals
              (strategy_version_id, exchange, tradingsymbol, "interval", signal_type, side,
               composite_score, score_breakdown)
            VALUES (?, 'NSE', ?, '1m', 'ENTRY', 'BUY', 0.7, '{}'::jsonb) RETURNING id
            """,
            Long.class,
            versionId,
            "BOOKSIG" + suffix.substring(0, 8));
    return new Seed(strategyId, versionId, signalId);
  }
}
