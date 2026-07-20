package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V044 applied, {@link CompositeRejectionRepository} round-trips every column, the retention prune
 * deletes only past the horizon, and the table is append-only BY GRANT for the read-only
 * {@code ay_strategy} role (the V015/V038/V042 doctrine).
 *
 * <p>Shared singleton DB with no per-method cleanup, so every row carries a UUID-unique slug and the
 * assertions filter to those slugs rather than counting rows.
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
class CompositeRejectionRepositoryIntegrationTest extends StrategySignalIntegrationTestBase {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

  @Autowired private CompositeRejectionRepository repository;
  @Autowired private JdbcTemplate jdbc;

  private UUID anyVersionId() {
    return jdbc.queryForObject("SELECT id FROM strategy_versions LIMIT 1", UUID.class);
  }

  /** The real live-scalper numbers: ST DOWN + RSI 55 ⇒ composite 0.125 against a 0.2 threshold. */
  private long insert(UUID versionId, String slug) {
    return repository.insert(
        versionId, slug, "NFO", "NIFTY26JULFUT", "3m",
        new BigDecimal("0.125"), new BigDecimal("0.2"), new BigDecimal("-0.075"),
        """
        {"composite":"0.125","threshold":"0.2","passed":false,\
        "indicators":[{"alias":"rsi14","rawValue":"55","score":"0.25","weight":"1"},\
        {"alias":"supertrend","rawValue":"-1","score":"0","weight":"1"}]}""",
        OffsetDateTime.now(IST).withNano(0));
  }

  @Test
  void insertAndReadRoundTripPreservesEveryColumn() {
    UUID versionId = anyVersionId();
    String slug = "composite-roundtrip-" + UUID.randomUUID();
    OffsetDateTime from = OffsetDateTime.now(IST).minusMinutes(1);

    assertThat(insert(versionId, slug)).isPositive();

    List<CompositeRejectionRepository.CompositeRejectionRow> rows =
        repository.list(versionId, from, null, 200, 0);
    assertThat(rows)
        .anySatisfy(
            r -> {
              assertThat(r.strategySlug()).isEqualTo(slug);
              assertThat(r.exchange()).isEqualTo("NFO");
              assertThat(r.tradingsymbol()).isEqualTo("NIFTY26JULFUT");
              assertThat(r.interval()).isEqualTo("3m");
              assertThat(r.composite()).isEqualByComparingTo("0.125");
              assertThat(r.threshold()).isEqualByComparingTo("0.2");
              assertThat(r.margin()).isEqualByComparingTo("-0.075");
              // THE PAYOFF: the RSI operand survives the JSONB round trip, separable per factor
              assertThat(r.scoreBreakdown().path("indicators").get(0).path("alias").asText())
                  .isEqualTo("rsi14");
              assertThat(r.scoreBreakdown().path("indicators").get(0).path("rawValue").asText())
                  .isEqualTo("55");
            });
  }

  @Test
  void theRsiOperandDistributionIsQueryableStraightOutOfTheJsonb() {
    // The owner's actual payoff query shape (jsonb_array_elements over score_breakdown), proven to
    // execute and return the RSI operand — not just that the bytes round-trip.
    UUID versionId = anyVersionId();
    String slug = "composite-payoffsql-" + UUID.randomUUID();
    insert(versionId, slug);

    List<BigDecimal> rsi =
        jdbc.query(
            """
            SELECT (i->>'rawValue')::numeric AS rsi
            FROM composite_rejections r
            CROSS JOIN LATERAL jsonb_array_elements(r.score_breakdown->'indicators') AS i
            WHERE r.strategy_slug = ? AND i->>'alias' = 'rsi14'
            """,
            (rs, n) -> rs.getBigDecimal("rsi"),
            slug);
    assertThat(rsi).singleElement().satisfies(v -> assertThat(v).isEqualByComparingTo("55"));
  }

  @Test
  void thePruneDeletesPastTheHorizonAndSparesRecentRows() {
    UUID versionId = anyVersionId();
    String recent = "composite-prune-recent-" + UUID.randomUUID();
    String stale = "composite-prune-stale-" + UUID.randomUUID();
    insert(versionId, recent);
    long staleId = insert(versionId, stale);
    // backdate one row past a 30-day horizon (generated_at defaults to now(); this is the only
    // way to age a row without waiting, and it is why the test writes raw SQL here)
    jdbc.update(
        "UPDATE composite_rejections SET generated_at = now() - interval '31 days' WHERE id = ?",
        staleId);

    int deleted = repository.deleteOlderThanDays(30);

    assertThat(deleted).isGreaterThanOrEqualTo(1);
    assertThat(slugsPresent(recent, stale)).contains(recent).doesNotContain(stale);
  }

  private List<String> slugsPresent(String... slugs) {
    return jdbc.queryForList(
        "SELECT strategy_slug FROM composite_rejections WHERE strategy_slug = ANY (?)",
        String.class,
        (Object) slugs);
  }

  @Test
  void tableIsAppendOnlyByGrantForTheReadOnlyRole() throws SQLException {
    UUID versionId = anyVersionId();
    try (Connection conn = DriverManager.getConnection(jdbcUrl(), dbUser(), dbPassword());
        Statement st = conn.createStatement()) {
      st.execute("SET ROLE ay_strategy");
      st.execute("SET search_path TO strategy");
      // INSERT allowed
      st.execute(
          "INSERT INTO composite_rejections"
              + " (strategy_version_id, strategy_slug, exchange, tradingsymbol, \"interval\","
              + " composite, threshold, margin, score_breakdown, bar_time)"
              + " VALUES ('" + versionId + "', 'grant-test-" + UUID.randomUUID() + "', 'NFO',"
              + " 'NIFTY26JULFUT', '3m', 0.125, 0.2, -0.075, '{}'::jsonb, now())");
      // UPDATE/DELETE denied by grant — the prune runs as `artha`, never as this role
      assertThatThrownBy(() -> st.execute("UPDATE composite_rejections SET strategy_slug = 'x'"))
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> st.execute("DELETE FROM composite_rejections"))
          .isInstanceOf(SQLException.class);
    }
  }
}
