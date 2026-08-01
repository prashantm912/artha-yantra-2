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
 * Cross-vendor review Major 2: the {@code degraded} filter on
 * {@link SignalRejectionRepository#list} must return the rows the UI's badge calls OK — no more.
 *
 * <p>{@code degraded = false} is the state of THREE different populations: genuinely-clean rows,
 * rows written before V054 (the column default, never judged) and context-less rows the classifier
 * declined to judge. Only the first is "healthy inputs". A naive {@code degraded = ?} predicate
 * hands all three back under a "Healthy inputs only" label, which is the same class of lie this
 * feature exists to stop — and it is a SQL predicate, so no mock-repo unit test can pin it. Asserted
 * here against the real lineage.
 *
 * <p>⚠️ Every row is stamped into a FIXED 2031 window this suite owns, never {@code now()}. The
 * shared singleton DB has no per-method cleanup, so a row left in "today" becomes live session
 * evidence for {@code DotHealthCanary} and any other context-reading suite (measured, not
 * theorised — see {@code SessionBarSideVerdictsIntegrationTest}'s header).
 */
@SpringBootTest(
    properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
class SignalRejectionDataHealthFilterIntegrationTest extends StrategySignalIntegrationTestBase {

  @Autowired private SignalRejectionRepository rejections;
  @Autowired private JdbcTemplate jdbc;

  /** An isolated read window: a 2031 date no other suite writes into. */
  private static final OffsetDateTime WINDOW_FROM =
      OffsetDateTime.parse("2031-05-13T09:15:00+05:30");

  private static final OffsetDateTime WINDOW_TO = WINDOW_FROM.plusDays(1);

  /** Unique to this suite — the singleton DB persists rows across methods and reruns. */
  private static final String SYMBOL = "DHFILTERFUT";

  private static final String DEGRADED_JSON =
      "{\"degraded\":true,\"contextBearing\":true,\"oiSuppressed\":false,"
          + "\"flags\":[\"iv-rank-absent\"]}";
  private static final String CLEAN_JSON =
      "{\"degraded\":false,\"contextBearing\":true,\"oiSuppressed\":false,\"flags\":[]}";
  private static final String CONTEXTLESS_JSON =
      "{\"degraded\":false,\"contextBearing\":false,\"oiSuppressed\":false,\"flags\":[]}";

  @Test
  void theHealthyFilterReturnsOnlyJudgedContextBearingCleanRows() {
    long degraded = insert("dh-degraded", DEGRADED_JSON, true);
    long clean = insert("dh-clean", CLEAN_JSON, false);
    // Blocked before the chain fetch: degraded=false, but nothing was ever judged.
    long contextless = insert("dh-contextless", CONTEXTLESS_JSON, false);
    // Pre-V054 history: data_health IS NULL, degraded=false purely from the column DEFAULT.
    long legacy = insert("dh-legacy", null, false);

    assertThat(ids(true))
        .as("degraded-only returns exactly the flagged row")
        .containsExactly(degraded);

    assertThat(ids(false))
        .as(
            "healthy-only must EXCLUDE the never-judged (legacy) and the nothing-to-judge"
                + " (context-less) rows — they are unknown, not clean")
        .containsExactly(clean);

    assertThat(ids(null))
        .as("no filter returns every row in the window")
        .containsExactlyInAnyOrder(degraded, clean, contextless, legacy);
  }

  /** The ids the filter returns, newest-first, scoped to this suite's symbol + window. */
  private List<Long> ids(Boolean degraded) {
    return rejections
        .list(null, null, "NFO", SYMBOL, WINDOW_FROM, WINDOW_TO, degraded, 100, 0)
        .stream()
        .map(SignalRejectionRepository.RejectionRow::id)
        .toList();
  }

  private long insert(String slug, String dataHealthJson, boolean degraded) {
    long id =
        rejections.insert(
            versionId(), slug, "NFO", SYMBOL, "3m", "CE", "volume-floor", new BigDecimal("7865"),
            new BigDecimal("125000"), new BigDecimal("-117135"), "volume < 125000",
            new BigDecimal("0.70"), new BigDecimal("0.60"), "{}", WINDOW_FROM, dataHealthJson,
            degraded);
    // insert() defaults generated_at to now(); the window read is what isolates this suite.
    jdbc.update("UPDATE signal_rejections SET generated_at = ? WHERE id = ?", WINDOW_FROM, id);
    return id;
  }

  private UUID versionId() {
    return jdbc.queryForObject("SELECT id FROM strategy_versions LIMIT 1", UUID.class);
  }
}
