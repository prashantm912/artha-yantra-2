package in.arthayantra.strategysignal.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategysignal.signals.SignalEmitted;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * INT I1 schema + context-load IT (mock profile, {@code engine-enabled=false}). Booting this class at
 * all is the "insights beans load with the live engine OFF" proof (the #634 discipline — the
 * always-on engine/repo/controller beans must not hard-depend on {@code SignalEngine}; only the
 * scheduled {@link InsightSweeper} is gated, and it is correctly ABSENT here). Also asserts V032/V033
 * applied, the {@code ay_strategy} grants (SET ROLE), and an insight round-trip incl. dedupe upsert.
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
class InsightsSchemaIntegrationTest extends StrategySignalIntegrationTestBase {

  @Autowired private InsightEngine engine;
  @Autowired private InsightRepository repository;
  @Autowired private InsightController controller;
  @Autowired private TrustService trustService;
  @Autowired private NotificationEventsRepository notifications;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private ApplicationEventPublisher publisher;

  /** Unique signal ids so shared-DB reruns never collide on the SIGNAL_PRIORITY dedupe scope. */
  private static final AtomicLong SIGNAL_SEQ = new AtomicLong(900_000);

  @Test
  void contextLoadsWithEngineDisabled() {
    // The engine + read beans are always-on; the scheduled sweeper is gated OFF here.
    assertThat(engine).isNotNull();
    assertThat(repository).isNotNull();
    assertThat(controller).isNotNull();
    assertThat(trustService).isNotNull();
    assertThat(notifications).isNotNull();
  }

  @Test
  void v032AndV033ObjectsExist() {
    assertThat(jdbc.queryForObject("SELECT to_regclass('strategy.insights')::text", String.class))
        .isEqualTo("insights");
    assertThat(jdbc.queryForObject("SELECT to_regclass('strategy.insight_actions')::text", String.class))
        .isEqualTo("insight_actions");
    assertThat(jdbc.queryForObject("SELECT to_regclass('strategy.insight_feedback')::text", String.class))
        .isEqualTo("insight_feedback");
    // V033: notification_events.strategy_id now nullable + an insight_id column exists.
    assertThat(
            jdbc.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                    + "WHERE table_schema='strategy' AND table_name='notification_events' AND column_name='strategy_id'",
                String.class))
        .isEqualTo("YES");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                    + "WHERE table_schema='strategy' AND table_name='notification_events' AND column_name='insight_id'",
                Integer.class))
        .isEqualTo(1);
  }

  @Test
  void insightRoundTripsWithDedupeUpsertAndTriage() {
    String dedupe = "SIGNAL_PRIORITY:signal:" + UUID.randomUUID();
    Insight first = insight(dedupe, "priority 71", new BigDecimal("71.00"));
    repository.insertOrRefresh(first);
    // Same OPEN dedupe_key → UPSERT refreshes the row in place (§2.5.1), never a duplicate.
    Insight regenerated = insight(dedupe, "priority 83", new BigDecimal("83.00"));
    repository.insertOrRefresh(regenerated);

    List<Insight> byScope = repository.list(null, null, "OPEN", first.scope(), null, null, false, 100, 0);
    assertThat(byScope).hasSize(1);
    Insight stored = byScope.get(0);
    assertThat(stored.title()).isEqualTo("priority 83");
    assertThat(stored.priority()).isEqualByComparingTo("83.00");
    assertThat(stored.evidence().isArray()).isTrue();
    assertThat(stored.evidence()).isNotEmpty();
    assertThat(stored.engineVersion()).isNotBlank();
    assertThat(stored.configHash()).isNotBlank();
    assertThat(stored.dataTrust()).isEqualTo("OK");

    // Triage: ack → status ACKED + an insight_actions row; feedback UPSERTs.
    InsightController.TriageResponse acked = controller.ack(stored.id());
    assertThat(acked.status()).isEqualTo("ACKED");
    assertThat(repository.get(stored.id()).orElseThrow().status()).isEqualTo("ACKED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM insight_actions WHERE insight_id = ? AND action = 'ACK'",
                Integer.class,
                stored.id()))
        .isEqualTo(1);
    controller.feedback(stored.id(), new InsightController.FeedbackRequest("USEFUL", "reproduced by hand"));
    assertThat(
            jdbc.queryForObject(
                "SELECT verdict FROM insight_feedback WHERE insight_id = ?", String.class, stored.id()))
        .isEqualTo("USEFUL");
  }

  @Test
  void notificationEventsSupportsInsightScopedRows() {
    // V033: a market/dataops-scoped push audit has NO strategy (null strategy_id) + an insight_id.
    Insight dataTrust = insight("DATA_TRUST:screener:" + UUID.randomUUID(), "screener BLOCKED", null);
    repository.insertOrRefresh(dataTrust);
    jdbc.update(
        "INSERT INTO notification_events (signal_id, strategy_id, insight_id, channel, status) "
            + "VALUES (NULL, NULL, ?, 'NTFY', 'SUPPRESSED')",
        dataTrust.id());
    assertThat(
            notifications.recent(50).stream()
                .anyMatch(r -> dataTrust.id().equals(r.insightId()) && r.strategyId() == null))
        .isTrue();
  }

  @Test
  void insightsAndActionsHonorTheAyStrategyGrant() throws SQLException {
    try (Connection conn = DriverManager.getConnection(jdbcUrl(), dbUser(), dbPassword());
        Statement st = conn.createStatement()) {
      st.execute("SET ROLE ay_strategy");
      st.execute("SET search_path TO strategy");
      UUID id = UUID.randomUUID();
      // insights: SELECT/INSERT/UPDATE/DELETE all granted (status mutates + prune).
      st.execute(
          "INSERT INTO insights (id, generated_at, type, severity, scope, title, explanation, evidence,"
              + " data_trust, dedupe_key, engine_version, config_hash) VALUES ('"
              + id + "', now(), 'DATA_TRUST', 'WARN', 'dataops', 't', 'e', '[]'::jsonb, 'DEGRADED', 'g:"
              + id + "', 'dev', 'h')");
      st.execute("UPDATE insights SET status='ACKED' WHERE id='" + id + "'");
      // insight_actions: append-only by grant — INSERT allowed, UPDATE/DELETE denied.
      st.execute(
          "INSERT INTO insight_actions (insight_id, action) VALUES ('" + id + "', 'ACK')");
      assertThatThrownBy(() -> st.execute("UPDATE insight_actions SET action='X'"))
          .hasMessageContaining("permission denied");
      assertThatThrownBy(() -> st.execute("DELETE FROM insight_actions"))
          .hasMessageContaining("permission denied");
    }
  }

  @Test
  void engineSweepsAndEmitPathWriteInsightsFailSoft() {
    // Trust sweep: market-data is unreachable in this IT → every family degrades CONSERVATIVELY
    // (never a 5xx), so DATA_TRUST insights are written. Exercises ContextClient + TrustService +
    // DataTrustGenerator + engine persist end-to-end on the fail-soft path.
    engine.runTrustSweep();
    assertThat(repository.list("DATA_TRUST", null, "OPEN", "dataops", null, null, false, 100, 0))
        .isNotEmpty();

    // Risk sweep: exercises BookHeatReader over the (empty) open book — must not throw.
    engine.runRiskSweep();

    // Emit path via the real in-process event bus (async on notifierExecutor): a fired scalper signal
    // → a SIGNAL_PRIORITY insight. Trust is DEGRADED here (market-data down) so it is scored + capped.
    long signalId = SIGNAL_SEQ.incrementAndGet();
    SignalEmitted.ScalpDetail scalp =
        new SignalEmitted.ScalpDetail(
            "NIFTY 50", "CE", new BigDecimal("25200"), "NIFTY25JUL25200CE",
            new BigDecimal("120.5"), new BigDecimal("0.75"), "T2", 62);
    publisher.publishEvent(
        new SignalEmitted(
            signalId, UUID.randomUUID(), "NFO", "NIFTY25JULFUT", "BUY",
            new BigDecimal("100"), new BigDecimal("90"), new BigDecimal("140"),
            new BigDecimal("0.81"), new BigDecimal("0.72"), scalp));
    await()
        .atMost(Duration.ofSeconds(15))
        .until(() -> !repository.list(null, null, null, "signal:" + signalId, null, null, false, 10, 0).isEmpty());
    Insight priorityInsight =
        repository.list(null, null, null, "signal:" + signalId, null, null, false, 10, 0).get(0);
    assertThat(priorityInsight.type()).isEqualTo("SIGNAL_PRIORITY");
    assertThat(priorityInsight.expiresAt()).isNotNull(); // signal-scoped insights expire (TTL)

    // Read surface: list (+ day filter), summary, focus, trust-summary, notification-events, dismiss.
    assertThat(controller.list(null, null, "OPEN", null, null, false, 50, 0).items()).isNotEmpty();
    controller.list(null, null, null, null, java.time.LocalDate.now(), true, 50, 0);
    assertThat(controller.summary().bySeverity()).isNotNull();
    assertThat(controller.focus(20).signalQueue()).isNotNull();
    assertThat(controller.trustSummary().families()).isNotEmpty();
    assertThat(controller.notificationEvents(50).items()).isNotNull();
    InsightController.TriageResponse dismissed = controller.dismiss(priorityInsight.id());
    assertThat(dismissed.status()).isEqualTo("DISMISSED");
  }

  private Insight insight(String dedupe, String title, BigDecimal priority) {
    var evidence = objectMapper.valueToTree(List.of(Evidence.of("label", "value")));
    return new Insight(
        UUID.randomUUID(),
        OffsetDateTime.now(),
        "SIGNAL_PRIORITY",
        "NOTICE",
        dedupe.startsWith("SIGNAL_PRIORITY") ? "signal:" + dedupe.hashCode() : "dataops",
        title,
        "explanation text",
        evidence,
        priority,
        null,
        "OK",
        List.of("ticks live"),
        dedupe,
        null,
        false,
        "OPEN",
        null,
        "dev-sha",
        "cfg-hash");
  }
}
