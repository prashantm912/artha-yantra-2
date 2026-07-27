package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V048: an EXIT signal must carry WHY it fired, durably, on its own row.
 *
 * <p>Before V048 the reason reached only a log line, a {@code SignalExited} event, and — via that
 * event — the paper close. That is adequate only while every exiting strategy holds a paper
 * position, and scalpers do not: all 30 paper positions live on the swing books while scalpers had
 * emitted 10 EXIT signals whose reasons survived nowhere. "Why did this scalper exit?" was
 * unanswerable from the database, which is exactly the instrument the confluence-flip rail audit
 * needs (docs/signal-analysis/2026-07-27-confluence-flip-rail-audit.md §6).
 *
 * <p>Pinned through the REAL schema rather than a mock, because the column, the insert column list
 * and the row mapper have to agree — a mock would pass with any one of the three wrong.
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
class SignalExitReasonIntegrationTest extends StrategySignalIntegrationTestBase {

  @Autowired private SignalRepository signals;
  @Autowired private JdbcTemplate jdbc;

  /**
   * Any existing version row is fine — this test is about the signals COLUMN, not the registry, so
   * it reuses a seeded version rather than minting (and schema-validating) a throwaway strategy.
   * Assertions key on a unique SYMBOL instead, which is what keeps them safe on the shared singleton
   * DB that has no per-method cleanup.
   */
  private UUID anyVersionId() {
    return jdbc.queryForObject(
        "SELECT id FROM strategy_versions ORDER BY created_at DESC LIMIT 1", UUID.class);
  }

  private String freshSymbol(String kind) {
    return ("XR" + kind + UUID.randomUUID().toString().substring(0, 8)).toUpperCase(java.util.Locale.ROOT);
  }

  private long insertSignal(UUID versionId, String symbol, String type, String exitReason) {
    return signals.insert(
        versionId, "NFO", symbol, "3m", type, "BUY",
        new BigDecimal("100"), null, null, new BigDecimal("0.5"), "{}",
        OffsetDateTime.now(), OffsetDateTime.now().plusHours(1), exitReason);
  }

  @Test
  void anExitSignalPersistsItsReasonAndReadsItBack() {
    UUID versionId = anyVersionId();
    String symbol = freshSymbol("EX");

    long id = insertSignal(versionId, symbol, "EXIT", "CONFLUENCE_FLIP");

    // through the mapper — proves column, insert list and row() all agree
    SignalRepository.SignalRow row =
        signals.list(null, null, "NFO", symbol, null, null, 10, 0).stream()
            .filter(r -> r.id() == id)
            .findFirst()
            .orElseThrow();
    assertThat(row.exitReason()).isEqualTo("CONFLUENCE_FLIP");

    // and directly, so a mapper that silently returned a constant could not pass
    assertThat(
            jdbc.queryForObject("SELECT exit_reason FROM signals WHERE id = ?", String.class, id))
        .isEqualTo("CONFLUENCE_FLIP");
  }

  /** ENTRY rows carry no reason — the column must be genuinely nullable, not defaulted. */
  @Test
  void anEntrySignalLeavesTheReasonNull() {
    UUID versionId = anyVersionId();

    long id = insertSignal(versionId, freshSymbol("EN"), "ENTRY", null);

    assertThat(
            jdbc.queryForObject("SELECT exit_reason FROM signals WHERE id = ?", String.class, id))
        .isNull();
  }

  /**
   * The question this column exists to answer: which exit reasons fired, and how often. If the
   * partial index or the column type were wrong this is the query that would break.
   */
  @Test
  void reasonsAreAggregatableAcrossExits() {
    UUID versionId = anyVersionId();
    String run = UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
    insertSignal(versionId, "AGG" + run + "A", "EXIT", "STRUCTURAL_STOP");
    insertSignal(versionId, "AGG" + run + "B", "EXIT", "STRUCTURAL_STOP");
    insertSignal(versionId, "AGG" + run + "C", "EXIT", "CONFLUENCE_FLIP");
    insertSignal(versionId, "AGG" + run + "D", "ENTRY", null);

    List<String> reasons =
        jdbc.queryForList(
            "SELECT exit_reason FROM signals WHERE tradingsymbol LIKE ? AND exit_reason IS NOT"
                + " NULL ORDER BY exit_reason",
            String.class,
            "AGG" + run + "%");

    assertThat(reasons)
        .as("ENTRY rows must not appear; both EXIT reasons must")
        .containsExactly("CONFLUENCE_FLIP", "STRUCTURAL_STOP", "STRUCTURAL_STOP");
  }
}
