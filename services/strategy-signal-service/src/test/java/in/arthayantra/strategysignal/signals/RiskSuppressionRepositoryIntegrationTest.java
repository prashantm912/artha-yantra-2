package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
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
 * PF-03: the {@code risk_suppressions} migration (V042) applied, the repository round-trips, and the
 * table is append-only BY GRANT for the read-only {@code ay_strategy} role (mirrors the V015/V038
 * doctrine). Shared singleton DB — the FK reuses whatever seeded {@code strategy_versions} row exists.
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
class RiskSuppressionRepositoryIntegrationTest extends StrategySignalIntegrationTestBase {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

  @Autowired private RiskSuppressionRepository repository;
  @Autowired private JdbcTemplate jdbc;

  private UUID anyVersionId() {
    return jdbc.queryForObject("SELECT id FROM strategy_versions LIMIT 1", UUID.class);
  }

  @Test
  void insertAndReadRoundTripPreservesEveryColumn() {
    UUID versionId = anyVersionId();
    String slug = "risksuppress-roundtrip-" + UUID.randomUUID();
    OffsetDateTime barTime = OffsetDateTime.now(IST).withNano(0);
    long id =
        repository.insert(
            versionId, slug, "scalper", "max_open_paper_positions", "NFO", "NIFTY26JULFUT", "3m",
            "SELL", "CE", "NIFTY26JUL24000CE", barTime);
    assertThat(id).isPositive();

    List<RiskSuppressionRepository.RiskSuppressionRow> rows =
        repository.list(versionId, "max_open_paper_positions", "scalper", 50, 0);
    assertThat(rows)
        .anySatisfy(
            r -> {
              assertThat(r.strategySlug()).isEqualTo(slug);
              assertThat(r.book()).isEqualTo("scalper");
              assertThat(r.rail()).isEqualTo("max_open_paper_positions");
              assertThat(r.exchange()).isEqualTo("NFO");
              assertThat(r.tradingsymbol()).isEqualTo("NIFTY26JULFUT");
              assertThat(r.interval()).isEqualTo("3m");
              assertThat(r.side()).isEqualTo("SELL");
              assertThat(r.optionType()).isEqualTo("CE");
              assertThat(r.optionTradingsymbol()).isEqualTo("NIFTY26JUL24000CE");
              assertThat(r.barTime().toInstant()).isEqualTo(barTime.toInstant());
            });
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
          "INSERT INTO risk_suppressions"
              + " (strategy_version_id, strategy_slug, book, rail, exchange, tradingsymbol,"
              + " \"interval\", bar_time)"
              + " VALUES ('" + versionId + "', 'grant-test-" + UUID.randomUUID() + "', 'scalper',"
              + " 'kill_switch', 'NFO', 'NIFTY26JULFUT', '3m', now())");
      // UPDATE/DELETE denied by grant
      assertThatThrownBy(() -> st.execute("UPDATE risk_suppressions SET rail = 'tamper'"))
          .hasMessageContaining("permission denied");
      assertThatThrownBy(() -> st.execute("DELETE FROM risk_suppressions"))
          .hasMessageContaining("permission denied");
    }
  }
}
