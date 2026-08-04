package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The V058 boot gate (cross-vendor review Critical 3): <b>default-OFF is correct, but it is NOT a
 * rollback mechanism once scoped rows exist.</b>
 *
 * <p>Both transitions are silently unsafe in OPPOSITE directions, and neither is recoverable in
 * SQL because the information needed to disambiguate is not on the row:
 *
 * <ul>
 *   <li>ARM with OPEN unattributed rows → NULL is the wildcard arm of every scoped predicate, so
 *       ANY strategy's exit can close a lot it never opened.
 *   <li>DISARM with OPEN attributed rows → entries stop stamping a strategy and {@code findOpen}
 *       goes blind again, so one exit settles lots that were deliberately split.
 * </ul>
 *
 * <p>The guard converts both into a loud startup refusal at the one moment a human can act.
 *
 * <p><b>Determinism note.</b> The IT database is a shared singleton with no per-method cleanup, so
 * OTHER classes' leftovers may hold attributed OPEN rows on other books. The disarm-direction test
 * therefore computes the property from the CURRENT database and arms every offending book EXCEPT
 * its own, so the only violation the guard can report is the one this test created.
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
class PaperStrategyScopeGuardIntegrationTest extends StrategySignalIntegrationTestBase {

  @Autowired private PaperPositionRepository positions;
  @Autowired private JdbcTemplate jdbc;

  @AfterEach
  void leaveNoMixedRows() {
    jdbc.update(
        "UPDATE paper_positions SET status='CLOSED', closed_at=now(), close_reason='IT-CLEANUP'"
            + " WHERE status='OPEN' AND (strategy_id IS NOT NULL OR book LIKE 'scopeguard-%')");
  }

  /** Arming a book that still holds an unattributed OPEN lot must refuse to boot. */
  @Test
  void armingABookThatStillHoldsAnUnattributedOpenLotRefusesToBoot() {
    String book = "scopeguard-arm-" + UUID.randomUUID().toString().substring(0, 8);
    String sym = "GUARD-" + UUID.randomUUID().toString().substring(0, 8);
    positions.insertOpen(book, "NFO", sym, "BUY", 10, new BigDecimal("100"), null, null, null);

    assertThatThrownBy(() -> new PaperStrategyScopeGuard(positions, book).afterPropertiesSet())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(book)
        .hasMessageContaining("no strategy_id");
  }

  /** Disarming a book that still holds a strategy-scoped OPEN lot must refuse to boot. */
  @Test
  void disarmingABookThatStillHoldsAScopedOpenLotRefusesToBoot() {
    String book = "scopeguard-disarm-" + UUID.randomUUID().toString().substring(0, 8);
    String sym = "GUARD-" + UUID.randomUUID().toString().substring(0, 8);
    positions.insertOpen(
        book, "NFO", sym, "BUY", 10, new BigDecimal("100"), null, null, null, null, null,
        UUID.randomUUID());

    assertThatThrownBy(
            () -> new PaperStrategyScopeGuard(positions, everyOtherOffendingBook(book))
                .afterPropertiesSet())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(book)
        .hasMessageContaining("NOT listed");
  }

  /** A book with the lot's scope MATCHING the flag boots cleanly in both directions. */
  @Test
  void aBookWhoseOpenLotsAgreeWithTheFlagBootsCleanly() {
    String scoped = "scopeguard-ok-scoped-" + UUID.randomUUID().toString().substring(0, 8);
    String plain = "scopeguard-ok-plain-" + UUID.randomUUID().toString().substring(0, 8);
    String sym = "GUARD-" + UUID.randomUUID().toString().substring(0, 8);
    positions.insertOpen(
        scoped, "NFO", sym, "BUY", 10, new BigDecimal("100"), null, null, null, null, null,
        UUID.randomUUID());
    positions.insertOpen(plain, "NFO", sym, "BUY", 10, new BigDecimal("100"), null, null, null);

    String property =
        java.util.stream.Stream.concat(
                java.util.stream.Stream.of(scoped), offendingBooks().stream())
            .filter(b -> !b.equals(plain))
            .distinct()
            .collect(Collectors.joining(","));
    assertThatCode(() -> new PaperStrategyScopeGuard(positions, property).afterPropertiesSet())
        .doesNotThrowAnyException();
    // The plain book is genuinely unscoped and genuinely unattributed — no mismatch either way.
    assertThat(positions.countOpenScopeMismatch(plain, false)).isZero();
    assertThat(positions.countOpenScopeMismatch(scoped, true)).isZero();
  }

  /** Books currently holding attributed OPEN rows — i.e. everything that must stay armed. */
  private List<String> offendingBooks() {
    return positions.booksWithOpenPositions().stream()
        .filter(b -> positions.countOpenScopeMismatch(b, false) > 0)
        .toList();
  }

  /** The same, minus {@code except} — so only {@code except} can be reported as a violation. */
  private String everyOtherOffendingBook(String except) {
    return offendingBooks().stream()
        .filter(b -> !b.equals(except))
        .collect(Collectors.joining(","));
  }
}
