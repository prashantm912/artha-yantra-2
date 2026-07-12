package in.arthayantra.strategysignal.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.error.ApiException;
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
            new BigDecimal("120.5"), new BigDecimal("0.75"), "T2", 62, new BigDecimal("50"));
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

  @Test
  void i2SweepsRunFailSoftAndTheQualityReportWritesAnInsight() {
    // Every I2 sweep must be fail-soft with market-data unreachable (digests → empty) and a possibly
    // empty book/rejection set — exercises the context/EOD/expiry engine paths + their readers.
    engine.runContextSweep();
    engine.runEodSweep();
    engine.runExpirySweep();
    engine.runRiskSweep(); // now also fans out staleFeedKeys + the stale-tick reader

    // The weekly quality report always writes ONE insight (its evidence IS the report, §10.2).
    engine.runQualityReport();
    assertThat(repository.list("QUALITY_REPORT", null, "OPEN", "dataops", null, null, false, 10, 0))
        .isNotEmpty();
  }

  @Test
  void firedVsRejectedEndpointReturnsTheTypedStage1Contrast() {
    // Wiring smoke: an unseeded (version, today) returns the empty typed contrast envelope, never a 5xx.
    InsightController.FiredVsRejectedResponse resp =
        controller.firedVsRejected(UUID.randomUUID(), java.time.LocalDate.now());
    assertThat(resp.fired()).isEmpty();
    assertThat(resp.rejected()).isEmpty();
    assertThat(resp.contrast().firedCount()).isZero();
    assertThat(resp.contrast().rejectedCount()).isZero();
  }

  @Test
  void i3StrategyEvidenceSweepIsFailSoftAndSellDecisionSweepWritesAnInsight() {
    // The strategy-evidence sweep reads GraduationService.board() over the real (draft-only) live set +
    // yesterday's snapshots — must not throw even with an empty board (the cross-module read edge works).
    engine.runStrategyEvidenceSweep();

    // Seed an unacknowledged SELL row → the sell-decision sweep turns it into a SELL_DECISION insight
    // (proves the sell_decisions SQL read + generator + persist end-to-end).
    long sig = SIGNAL_SEQ.incrementAndGet();
    jdbc.update(
        "INSERT INTO sell_decisions (book, run_date, signal_id, exchange, symbol, still_buyable,"
            + " selling_now, verdict) VALUES ('minervini', CURRENT_DATE, ?, 'NSE', 'TESTSELL', false,"
            + " true, 'SELL (test 2xATR)')",
        sig);
    engine.runSellDecisionSweep();
    assertThat(repository.list("SELL_DECISION", null, "OPEN", "book:minervini", null, null, false, 200, 0))
        .anyMatch(i -> i.dedupeKey().startsWith("SELL_DECISION:"));
  }

  @Test
  void actPathTrustGatesBlockedAndDegradedAndAcksInModule() {
    // BLOCKED insight → /act refuses ALL actions (422 DATA_STALE): advice never outruns its data.
    Insight blocked = signalInsight("signal:" + SIGNAL_SEQ.incrementAndGet(), "BLOCKED");
    repository.insertOrRefresh(blocked);
    assertThatThrownBy(() -> controller.act(blocked.id(), new InsightController.ActRequest("ACK", null)))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).httpStatus()).isEqualTo(422));

    // DEGRADED insight → the order-placing OPEN_TICKET prefill is trust-gated off (422); ACK is allowed.
    Insight degraded = signalInsight("signal:" + SIGNAL_SEQ.incrementAndGet(), "DEGRADED");
    repository.insertOrRefresh(degraded);
    assertThatThrownBy(
            () -> controller.act(degraded.id(), new InsightController.ActRequest("OPEN_TICKET", null)))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).httpStatus()).isEqualTo(422));

    InsightController.ActResponse acked =
        controller.act(degraded.id(), new InsightController.ActRequest("ACK", null));
    assertThat(acked.status()).isEqualTo("DONE");
    assertThat(repository.get(degraded.id()).orElseThrow().status()).isEqualTo("ACKED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM insight_actions WHERE insight_id = ? AND action = 'ACK'",
                Integer.class, degraded.id()))
        .isEqualTo(1);
  }

  @Test
  void actOpenTicketReturnsAPrefillAndWritesTheActionTrail() {
    UUID versionId = seededVersionId();
    long sig = seedSignal(versionId, OffsetDateTime.now());
    Insight in = signalInsight("signal:" + sig, "OK");
    repository.insertOrRefresh(in);

    // OPEN_TICKET returns a PREFILL (NEVER places the order) + writes the insight_actions trail (§2.4).
    InsightController.ActResponse resp =
        controller.act(in.id(), new InsightController.ActRequest("OPEN_TICKET", 150L));
    assertThat(resp.status()).isEqualTo("PREFILL");
    assertThat(resp.targetMethod()).isEqualTo("POST");
    assertThat(resp.targetEndpoint()).isEqualTo("/api/v1/paper/orders");
    assertThat(resp.ticket()).isNotNull();
    assertThat(resp.ticket().signalId()).isEqualTo(sig);
    assertThat(resp.ticket().qty()).isEqualTo(150L); // the body qty override rode through
    assertThat(repository.get(in.id()).orElseThrow().status()).isEqualTo("ACTED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM insight_actions WHERE insight_id = ? AND action = 'OPEN_TICKET'",
                Integer.class, in.id()))
        .isEqualTo(1);
  }

  @Test
  void compareGuardsSessionsAndAssemblesColumns() {
    // Comparability guards (§4.1): < 2 ids and 2 non-existent ids both 422.
    assertThatThrownBy(() -> controller.compare(List.of(1L))).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> controller.compare(List.of(999_000_001L, 999_000_002L)))
        .isInstanceOf(ApiException.class);

    UUID versionId = seededVersionId();
    OffsetDateTime now = OffsetDateTime.now();
    long a = seedSignal(versionId, now);
    long b = seedSignal(versionId, now);
    SignalCompareReader.CompareResult res = controller.compare(List.of(a, b));
    assertThat(res.columns()).hasSize(2);
    assertThat(res.columns()).allMatch(c -> "NO_INSIGHT".equals(c.scored()) || "SCORED".equals(c.scored()));

    // Different IST sessions refuse (422): a today-signal vs a two-days-ago signal.
    long old = seedSignal(versionId, now.minusDays(2));
    assertThatThrownBy(() -> controller.compare(List.of(a, old))).isInstanceOf(ApiException.class);
  }

  @Test
  void actCoversTheRemainingProposeBranches() {
    UUID versionId = seededVersionId();
    long sig = seedSignal(versionId, OffsetDateTime.now());
    Insight signalScoped = signalInsight("signal:" + sig, "OK");
    repository.insertOrRefresh(signalScoped);

    // TAKE_SIGNAL → PREFILL targeting the EXISTING /signals/{id}/taken (never opens the position here).
    InsightController.ActResponse take =
        controller.act(signalScoped.id(), new InsightController.ActRequest("TAKE_SIGNAL", null));
    assertThat(take.status()).isEqualTo("PREFILL");
    assertThat(take.targetEndpoint()).isEqualTo("/api/v1/signals/" + sig + "/taken");

    // JOURNAL_DRAFT_ACCEPT → PREFILL targeting /journal with the signal linked.
    Insight forJournal = signalInsight("signal:" + sig, "OK");
    repository.insertOrRefresh(forJournal);
    InsightController.ActResponse journal =
        controller.act(forJournal.id(), new InsightController.ActRequest("JOURNAL_DRAFT_ACCEPT", null));
    assertThat(journal.status()).isEqualTo("PREFILL");
    assertThat(journal.targetEndpoint()).isEqualTo("/api/v1/journal");
    assertThat(journal.journal().signalId()).isEqualTo(sig);

    // ADD_WATCHLIST → PROPOSED with the resolved instrument (from the signal's leg).
    Insight forWatch = signalInsight("signal:" + sig, "OK");
    repository.insertOrRefresh(forWatch);
    InsightController.ActResponse watch =
        controller.act(forWatch.id(), new InsightController.ActRequest("ADD_WATCHLIST", null));
    assertThat(watch.status()).isEqualTo("PROPOSED");
    assertThat(watch.instrument()).isNotBlank();

    // DISMISS + MUTE_TYPE → in-module DONE writes.
    Insight forDismiss = signalInsight("signal:" + sig, "OK");
    repository.insertOrRefresh(forDismiss);
    assertThat(controller.act(forDismiss.id(), new InsightController.ActRequest("DISMISS", null)).status())
        .isEqualTo("DONE");
    assertThat(repository.get(forDismiss.id()).orElseThrow().status()).isEqualTo("DISMISSED");
    Insight forMute = signalInsight("signal:" + sig, "OK");
    repository.insertOrRefresh(forMute);
    assertThat(controller.act(forMute.id(), new InsightController.ActRequest("MUTE_TYPE", null)).status())
        .isEqualTo("DONE");

    // ACK_SELL_DECISION → PROPOSED targeting the EXISTING sell-decision acknowledge endpoint.
    var sellEvidence =
        objectMapper.valueToTree(
            List.of(
                new Evidence(
                    "verdict", "SELL (test)",
                    new Evidence.Source("/api/v1/signals/sell-decisions", null, "2026-07-12"),
                    java.util.Map.of("sellDecisionId", 5099L, "signalId", 7099L))));
    Insight sellInsight =
        new Insight(
            UUID.randomUUID(), OffsetDateTime.now(), "SELL_DECISION", "WARN", "book:minervini",
            "TCS — SELL", "e", sellEvidence, null, null, "OK", List.of(), "SELL_DECISION:5099",
            null, false, "OPEN", null, "dev", "h");
    repository.insertOrRefresh(sellInsight);
    InsightController.ActResponse sellAck =
        controller.act(sellInsight.id(), new InsightController.ActRequest("ACK_SELL_DECISION", null));
    assertThat(sellAck.status()).isEqualTo("PROPOSED");
    assertThat(sellAck.targetEndpoint()).isEqualTo("/api/v1/signals/sell-decisions/5099/ack");
    assertThat(sellAck.sellDecisionId()).isEqualTo(5099L);
  }

  @Test
  void strategyDossierAssemblesForAnUnlistedStrategy() {
    StrategyEvidenceReader.Dossier d = controller.strategyDossier(UUID.randomUUID());
    assertThat(d.stage()).isEqualTo("UNLISTED");
    assertThat(d.criteria()).isEmpty();
    assertThat(d.notes()).isNotEmpty();
  }

  /** A signal-scoped SIGNAL_PRIORITY insight with a given trust state (for the /act trust-gate tests). */
  private Insight signalInsight(String scope, String dataTrust) {
    var ev = objectMapper.valueToTree(List.of(Evidence.of("label", "value")));
    return new Insight(
        UUID.randomUUID(), OffsetDateTime.now(), "SIGNAL_PRIORITY", "WARN", scope, "t", "e", ev,
        new BigDecimal("70.00"), null, dataTrust, List.of("reason"),
        "SIGNAL_PRIORITY:" + scope + ":" + UUID.randomUUID(), null, false, "OPEN", null, "dev", "h");
  }

  /** Any real seeded strategy version id (the repeatable sample) — satisfies the signals FK. */
  private UUID seededVersionId() {
    return jdbc.queryForObject("SELECT id FROM strategy_versions LIMIT 1", UUID.class);
  }

  /** Seeds one minimal signal row for the compare / ticket-prefill reads; returns its id. */
  private long seedSignal(UUID versionId, OffsetDateTime generatedAt) {
    return jdbc.queryForObject(
        "INSERT INTO signals (strategy_version_id, exchange, tradingsymbol, \"interval\", signal_type,"
            + " side, entry_price, stop_loss, target, composite_score, score_breakdown, suggested_qty,"
            + " generated_at) VALUES (?, 'NFO', 'TESTCE', '3m', 'ENTRY', 'BUY', 100, 90, 130, 0.81,"
            + " '{}'::jsonb, 75, ?) RETURNING id",
        Long.class, versionId, generatedAt);
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
