package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategysignal.scalper.SentimentLevelShadow;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V064 (H40): the {@code shadow_reason} column exists and the repository's INSERT round-trips into
 * it.
 *
 * <p><b>Why this test exists at all, and why it is an IT rather than another mock verify.</b>
 * {@link ExitOracleShadowWriterTest} mocks the repository, so it pins the ARGUMENT the writer
 * passes and can say nothing about whether the SQL matches the schema. That gap is not academic
 * here: {@link ExitOracleShadowWriter} swallows a failing insert on purpose (a protective exit path
 * must never take a DB error), so an INSERT naming a column the migration never added would fail
 * SILENTLY — no rows, one WARN, and a measurement surface that looks armed and is dead. Every mock
 * assertion in the sibling test would stay green. This is the only check that closes that.
 *
 * <p>Shared singleton DB with no per-method cleanup, so the row is keyed on a random
 * {@code entry_signal_id} — the table's UNIQUE is {@code (entry_signal_id, bar_time)} and its
 * insert is {@code ON CONFLICT DO NOTHING}, which would swallow a collision rather than fail.
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
class ExitOracleShadowRepositoryIntegrationTest extends StrategySignalIntegrationTestBase {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

  @Autowired private ExitOracleShadowRepository repository;
  @Autowired private JdbcTemplate jdbc;

  private Map<String, Object> insertAndRead(
      long entrySignalId, SentimentLevelShadow.Reason reason) {
    OffsetDateTime barTime = OffsetDateTime.now(IST).withNano(0);
    repository.insert(
        entrySignalId, "exit-oracle-roundtrip", barTime, "PE", "CE", "CE", true,
        new BigDecimal("0.00"), new BigDecimal("30"),
        true, true, "CE", true,
        new BigDecimal("0.94"), new BigDecimal("0.6"), true, null,
        true, true, reason);
    return jdbc.queryForMap(
        "SELECT * FROM exit_oracle_shadow WHERE entry_signal_id = ?", entrySignalId);
  }

  /**
   * The whole point of V064: the reason survives the write. A missing column would not throw here
   * either -- {@code jdbc.update} would fail first, which is the loud failure this test buys.
   */
  @Test
  void theShadowReasonRoundTripsThroughTheRealSchema() {
    long id = ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L);
    assertThat(insertAndRead(id, SentimentLevelShadow.Reason.MONTHLY_EXPIRY_SUPPRESSED))
        .containsEntry("shadow_reason", "MONTHLY_EXPIRY_SUPPRESSED")
        .containsEntry("strategy_slug", "exit-oracle-roundtrip");
  }

  /**
   * The legacy contract, asserted against the DDL rather than by writing a null row: the column
   * must stay NULLABLE so rows written before V064 remain readable. A NOT NULL column could not
   * have been added to a populated table without inventing a value for them.
   *
   * <p><b>Why this reads {@code information_schema} instead of inserting a null.</b> An earlier cut
   * of this test called the repository with a null reason and asserted the row came back null. It
   * PASSED, and it was wrong in the way that matters: it minted the legacy state through the
   * production API, which is exactly what V064's header says cannot happen. A test that forges the
   * state it claims is unforgeable does not pin the contract, it disproves it. The repository now
   * takes the enum and rejects null, so that state is unreachable from code, and the only honest
   * question left is the one asked here -- does the SCHEMA still permit the rows that already
   * exist. Review finding, 2026-08-27.
   */
  @Test
  void theColumnIsNullableSoPreV064RowsRemainReadable() {
    Map<String, Object> ddl =
        jdbc.queryForMap(
            "SELECT is_nullable, data_type FROM information_schema.columns"
                + " WHERE table_schema = current_schema()"
                + " AND table_name = 'exit_oracle_shadow'"
                + " AND column_name = 'shadow_reason'");
    assertThat(ddl).containsEntry("is_nullable", "YES").containsEntry("data_type", "text");
  }
}
