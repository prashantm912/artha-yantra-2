package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import in.arthayantra.strategysignal.signals.SignalEngine.Outcome;
import in.arthayantra.strategysignal.signals.SignalEngine.StrategyEvalKey;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The two READ paths the {@code /signal-rejections} funnel view (signal-analysis README §7 row 7)
 * added: the V053 per-strategy denominator, and the per-rail rollup narrowed to one strategy slug.
 *
 * <p>Both are SQL — a {@code SUM} across boots and a {@code WHERE} predicate — so neither can be
 * pinned by a mock-repository unit test. The denominator one is also the load-bearing half: the
 * funnel's first stage IS the denominator, and reading a single boot's rows would silently
 * under-report every day that spans a restart, which is precisely the failure V053 keyed {@code
 * boot_id} into the primary key to survive.
 *
 * <p>⚠️ Shared singleton DB, NO per-method cleanup. Two different isolation tricks are used, for two
 * different reasons:
 *
 * <ul>
 *   <li>The denominator rows use a FIXED 2031 session date, FIXED boot ids and FIXED slugs. {@link
 *       StrategyEvalDenominatorRepository#bootCount} is day-wide, so the date must be exclusive to
 *       this suite; and because the upsert REPLACES, fixed ids make a surefire rerun rewrite the
 *       same three rows instead of accumulating new ones. (A 2031 date is also past every prune in
 *       the suite, which only ever deletes sessions OLDER than a cutoff near today.)
 *   <li>The rejection rows use RANDOM per-run slugs, because {@code signal_rejections} inserts
 *       accumulate — a rerun would otherwise double the counts under a fixed slug.
 * </ul>
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
class EvalFunnelReadIntegrationTest extends StrategySignalIntegrationTestBase {

  @Autowired private StrategyEvalDenominatorRepository denominators;
  @Autowired private SignalRejectionRepository rejections;
  @Autowired private JdbcTemplate jdbc;

  /** A 2031 IST session date no other suite writes into — {@code bootCount} is day-wide. */
  private static final LocalDate SESSION = LocalDate.of(2031, 5, 14);

  /** Fixed so a rerun REPLACES these rows rather than minting a third and fourth boot. */
  private static final UUID BOOT_ONE = UUID.fromString("f0000000-0000-4000-8000-000000000001");

  private static final UUID BOOT_TWO = UUID.fromString("f0000000-0000-4000-8000-000000000002");

  private static final String DENOM_SLUG_A = "funnel-it-denominator-a";
  private static final String DENOM_SLUG_B = "funnel-it-denominator-b";

  /** The rejection read window, matching the denominator's session date. */
  private static final OffsetDateTime WINDOW_FROM =
      OffsetDateTime.parse("2031-05-14T09:15:00+05:30");

  private static final OffsetDateTime WINDOW_TO = WINDOW_FROM.plusDays(1);

  private static final String SYMBOL = "FUNNELFUT";

  @Test
  void theDenominatorSumsAcrossBootsAndReportsHowManyThereWere() {
    denominators.upsertCounts(
        BOOT_ONE,
        counts(
            key(DENOM_SLUG_A, Outcome.CHART_GATE_FAILED), 100L,
            key(DENOM_SLUG_A, Outcome.FIRED), 2L,
            key(DENOM_SLUG_B, Outcome.CONFLUENCE_BLOCKED), 7L));
    // A restart mints a fresh epoch and zeroes the adders, so boot two's numbers are SMALLER. They
    // must ADD to boot one's, never replace them — the day total is the sum.
    denominators.upsertCounts(
        BOOT_TWO,
        counts(key(DENOM_SLUG_A, Outcome.CHART_GATE_FAILED), 40L, key(DENOM_SLUG_A, Outcome.FIRED),
            1L));

    assertThat(denominators.outcomeCounts(SESSION))
        .as("one row per observed (slug, outcome), summed across boots, ordered slug then outcome")
        .extracting(
            StrategyEvalDenominatorRepository.OutcomeCount::strategySlug,
            StrategyEvalDenominatorRepository.OutcomeCount::outcome,
            StrategyEvalDenominatorRepository.OutcomeCount::evalCount)
        .containsExactly(
            tuple(DENOM_SLUG_A, Outcome.CHART_GATE_FAILED.tag(), 140L),
            tuple(DENOM_SLUG_A, Outcome.FIRED.tag(), 3L),
            tuple(DENOM_SLUG_B, Outcome.CONFLUENCE_BLOCKED.tag(), 7L));

    assertThat(denominators.bootCount(SESSION))
        .as("the reader must be able to tell a restarted day from an uninterrupted one")
        .isEqualTo(2);
  }

  @Test
  void aDateTheRollupNeverWroteReadsAsAbsentRatherThanZero() {
    // The funnel must render "no denominator recorded" for such a day. An empty list is the ONLY
    // honest answer: V053 writes no zero rows, so absence never proves nothing was evaluated.
    assertThat(denominators.outcomeCounts(SESSION.plusYears(1))).isEmpty();
    assertThat(denominators.bootCount(SESSION.plusYears(1))).isZero();
  }

  @Test
  void railCountsNarrowToOneStrategySlug() {
    String slugA = uniqueSlug("funnel-it-rail-a");
    insertRejection(slugA, "volume-floor");
    insertRejection(slugA, "volume-floor");
    insertRejection(slugA, "vwap-align");
    // slugB's rail is EXCLUSIVE to it, so "A's counts exclude it" is a real discrimination proof
    // rather than an assertion that would pass on an ignored filter.
    String slugB = uniqueSlug("funnel-it-rail-b");
    insertRejection(slugB, "oi-quadrant");

    assertThat(rejections.railCounts(null, slugA, WINDOW_FROM, WINDOW_TO))
        .extracting(
            SignalRejectionRepository.RailCount::rail, SignalRejectionRepository.RailCount::count)
        .containsExactly(tuple("volume-floor", 2L), tuple("vwap-align", 1L));

    assertThat(rejections.railCounts(null, slugB, WINDOW_FROM, WINDOW_TO))
        .extracting(SignalRejectionRepository.RailCount::rail)
        .containsExactly("oi-quadrant");

    assertThat(rejections.railCounts(null, null, WINDOW_FROM, WINDOW_TO))
        .as("an omitted slug still spans every strategy — the filter is additive")
        .extracting(SignalRejectionRepository.RailCount::rail)
        .contains("volume-floor", "vwap-align", "oi-quadrant");
  }

  private static String uniqueSlug(String prefix) {
    return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
  }

  private static StrategyEvalKey key(String slug, Outcome outcome) {
    return new StrategyEvalKey(SESSION, slug, outcome);
  }

  private static Map<StrategyEvalKey, Long> counts(Object... keyThenCount) {
    Map<StrategyEvalKey, Long> map = new LinkedHashMap<>();
    for (int i = 0; i < keyThenCount.length; i += 2) {
      map.put((StrategyEvalKey) keyThenCount[i], ((Number) keyThenCount[i + 1]).longValue());
    }
    return map;
  }

  private void insertRejection(String slug, String rail) {
    long id =
        rejections.insert(
            versionId(), slug, "NFO", SYMBOL, "3m", "CE", rail, new BigDecimal("7865"),
            new BigDecimal("125000"), new BigDecimal("-117135"), rail + " below floor",
            new BigDecimal("0.70"), new BigDecimal("0.60"), "{}", WINDOW_FROM, null, false);
    // insert() stamps generated_at with now(); the 2031 window is what isolates this suite, and a
    // row left in "today" would become live session evidence for the context-reading canaries.
    jdbc.update("UPDATE signal_rejections SET generated_at = ? WHERE id = ?", WINDOW_FROM, id);
  }

  private UUID versionId() {
    return jdbc.queryForObject("SELECT id FROM strategy_versions LIMIT 1", UUID.class);
  }
}
