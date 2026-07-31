package in.arthayantra.strategysignal.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.strategysignal.notifier.InsightAlertListener;
import in.arthayantra.strategysignal.notifier.NotifierClient;
import in.arthayantra.strategysignal.notifier.NotificationRepository;
import in.arthayantra.strategysignal.signals.InsightDeliveryAlert;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/** Integration coverage for persisted rows, owner mutes, and the notification audit ledger. */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
class InsightDeliveryIntegrationTest extends StrategySignalIntegrationTestBase {

  @Autowired private InsightRepository repository;
  @Autowired private InsightController controller;
  @Autowired private NotificationRepository notificationRepository;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void strategyMuteKeepsBothRowsButAuditsOnlyTheUnmutedDelivery() {
    String type = "I4_TEST_MUTE_" + UUID.randomUUID();
    String mutedScope = "strategy:" + UUID.randomUUID();
    Insight muted = insight(type, "WARN", mutedScope, false, null);
    Insight unmuted = insight(type, "WARN", "market", false, null);
    repository.insertOrRefresh(muted);
    repository.insertOrRefresh(unmuted);

    assertThat(controller.act(muted.id(), new InsightController.ActRequest("MUTE_TYPE", null)).status())
        .isEqualTo("DONE");
    assertThat(repository.get(muted.id()).orElseThrow().status()).isEqualTo("OPEN");
    assertThat(
            jdbc.queryForObject(
                "SELECT target_ref->>'scope' FROM insight_actions "
                    + "WHERE insight_id = ? AND action = 'MUTE_TYPE'",
                String.class,
                muted.id()))
        .isEqualTo(mutedScope);

    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    InsightPublisher publisher =
        new InsightPublisher(
            mock(StringRedisTemplate.class),
            objectMapper,
            events,
            repository,
            properties(new InsightProperties.Delivery(false, true, false, Severity.NOTICE)));
    publisher.publish(muted);
    publisher.publish(unmuted);

    ArgumentCaptor<InsightDeliveryAlert> alert = ArgumentCaptor.forClass(InsightDeliveryAlert.class);
    verify(events).publishEvent(alert.capture());
    assertThat(alert.getValue().insightId()).isEqualTo(unmuted.id());

    NotifierClient client = mock(NotifierClient.class);
    when(client.configured("NTFY")).thenReturn(true);
    new InsightAlertListener(notificationRepository, client, 3).onInsightDelivery(alert.getValue());

    verify(client).send("NTFY", "ArthaYantra " + unmuted.title(),
        unmuted.explanation() + "\nScope: market");
    assertThat(auditRows(unmuted.id())).isEqualTo(1);
    assertThat(auditRows(muted.id())).isZero();
  }

  @Test
  void suppressedAndCoolingRowsRemainInTheInAppStore() {
    String type = "I4_TEST_ROW_" + UUID.randomUUID();
    Insight suppressed = insight(type, "CRITICAL", "dataops", true, null);
    Insight cooling = insight(type, "NOTICE", "market", false, OffsetDateTime.now().plusMinutes(15));
    repository.insertOrRefresh(suppressed);
    repository.insertOrRefresh(cooling);

    assertThat(repository.get(suppressed.id()).orElseThrow().suppressed()).isTrue();
    assertThat(repository.get(cooling.id()).orElseThrow().cooldownUntil()).isNotNull();
    assertThat(repository.isCooling(cooling.dedupeKey(), OffsetDateTime.now())).isTrue();
    assertThat(
            repository.list(type, null, "OPEN", null, null, null, true, 20, 0))
        .extracting(Insight::id)
        .contains(suppressed.id(), cooling.id());
  }

  @Test
  void secondSweepOverAnUnchangedPersistentConditionDeliversZeroNewPushes() {
    // Pre-arm review M1 (the load-bearing case): a persistent condition (e.g. a dead data source)
    // regenerates every 15 minutes; only the FIRST sighting may push. Before the fix every refresh
    // re-published — ~96 pushes/day from one CRITICAL over a weekend.
    InsightCandidate candidate = candidate("I4_M1:" + UUID.randomUUID(), null);
    InsightGenerator generator = mock(InsightGenerator.class);
    when(generator.type()).thenReturn(InsightType.DATA_TRUST);
    when(generator.generate(any())).thenReturn(List.of(candidate));
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    InsightEngine engine = engineDeliveringTo(generator, events);

    engine.runTrustSweep(); // first sighting INSERTs → one push
    engine.runTrustSweep(); // unchanged condition REFRESHES the OPEN row → zero new pushes

    verify(events, times(1)).publishEvent(any(InsightDeliveryAlert.class));
  }

  @Test
  void ackInsideTheCooldownWindowDoesNotRedeliverOnTheNextSweep() {
    // Pre-arm review M3 (delivery loop): ACK closes the OPEN row and the next sweep re-inserts —
    // the re-insert must land suppressed while the original cooldown runs, or acknowledging an
    // alert re-pages it.
    InsightCandidate candidate = candidate("I4_M3:" + UUID.randomUUID(), 30);
    InsightGenerator generator = mock(InsightGenerator.class);
    when(generator.type()).thenReturn(InsightType.DATA_TRUST);
    when(generator.generate(any())).thenReturn(List.of(candidate));
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    InsightEngine engine = engineDeliveringTo(generator, events);

    engine.runTrustSweep(); // delivers + stamps cooldown_until = now + 30m
    UUID openId =
        jdbc.queryForObject(
            "SELECT id FROM insights WHERE dedupe_key = ?", UUID.class, candidate.dedupeKey());
    repository.updateStatus(openId, "ACKED"); // the owner taps the notification
    engine.runTrustSweep(); // re-inserts a fresh row — suppressed, NOT delivered

    verify(events, times(1)).publishEvent(any(InsightDeliveryAlert.class));
    assertThat(
            jdbc.queryForObject(
                "SELECT suppressed FROM insights WHERE dedupe_key = ? AND status = 'OPEN'",
                Boolean.class,
                candidate.dedupeKey()))
        .isTrue();
  }

  @Test
  void ackedRowInsideItsCooldownStillCools() {
    // Pre-arm review M3 (repository level): isCooling reads the LATEST row for the key regardless
    // of status — the old OPEN-only check let ACK/DISMISS defeat the cooldown.
    String type = "I4_TEST_COOLDOWN_" + UUID.randomUUID();
    Insight row = insight(type, "WARN", "market", false, OffsetDateTime.now().plusMinutes(30));
    repository.insertOrRefresh(row);
    repository.updateStatus(row.id(), "ACKED");

    assertThat(repository.isCooling(row.dedupeKey(), OffsetDateTime.now())).isTrue();
  }

  @Test
  void blockedRepeatPagerCanStillBeMutedWhileOtherActionsStayRefused() {
    // Pre-arm review M2: BLOCKED DATA_TRUST / stale-tick alerts are the likeliest repeat-pagers —
    // the mute off-switch must bypass the advice trust gate; every other action keeps the §7.4 422.
    String type = "I4_TEST_BLOCKED_" + UUID.randomUUID();
    Insight blocked = insightWithTrust(type, "CRITICAL", "dataops", "BLOCKED");
    repository.insertOrRefresh(blocked);

    assertThat(controller.act(blocked.id(), new InsightController.ActRequest("MUTE_TYPE", null)).status())
        .isEqualTo("DONE");
    assertThat(repository.isMuted(type, "dataops")).isTrue();
    assertThat(controller.act(blocked.id(), new InsightController.ActRequest("UNMUTE_TYPE", null)).status())
        .isEqualTo("DONE");
    assertThat(repository.isMuted(type, "dataops")).isFalse();

    assertThatThrownBy(() -> controller.act(blocked.id(), new InsightController.ActRequest("ACK", null)))
        .isInstanceOf(ApiException.class)
        .satisfies(t -> assertThat(((ApiException) t).httpStatus()).isEqualTo(422));
  }

  @Test
  void legacyScopelessMuteActionRowsAreNotDeliveryMutes() {
    // Pre-arm review M5: pre-I4 MUTE_TYPE rows were audit-only — without the delivery marker they
    // must never read as permanent global mutes (marker-gated on purpose, deliberately no migration).
    String type = "I4_TEST_LEGACY_" + UUID.randomUUID();
    Insight row = insight(type, "WARN", "market", false, null);
    repository.insertOrRefresh(row);
    repository.insertAction(row.id(), "MUTE_TYPE", "{\"type\":\"" + type + "\"}", "owner");

    assertThat(repository.isMuted(type, "market")).isFalse();

    controller.act(row.id(), new InsightController.ActRequest("MUTE_TYPE", null));
    assertThat(repository.isMuted(type, "market")).isTrue();
  }

  private int auditRows(UUID insightId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM notification_events WHERE insight_id = ?", Integer.class, insightId);
  }

  /** A minimal persistent-condition candidate (DATA_TRUST-shaped) with an optional cooldown. */
  private static InsightCandidate candidate(String dedupeKey, Integer cooldownMinutes) {
    return new InsightCandidate(
        InsightType.DATA_TRUST,
        Severity.WARN,
        "dataops",
        "persistent condition",
        "persistent condition",
        List.of(),
        null,
        null,
        DataTrust.DEGRADED,
        List.of(),
        dedupeKey,
        cooldownMinutes,
        null,
        false);
  }

  /** An engine over the REAL repository, delivering NTFY alerts to the given mock event publisher. */
  private InsightEngine engineDeliveringTo(InsightGenerator generator, ApplicationEventPublisher events) {
    InsightPublisher publisher =
        new InsightPublisher(
            mock(StringRedisTemplate.class),
            objectMapper,
            events,
            repository,
            properties(new InsightProperties.Delivery(false, true, false, Severity.NOTICE)));
    return new InsightEngine(
        List.of(generator),
        repository,
        mock(TrustService.class),
        mock(BookHeatReader.class),
        mock(ContextClient.class),
        mock(RejectionReader.class),
        mock(PortfolioReader.class),
        mock(StrategyEvidenceReader.class),
        publisher,
        properties(null),
        EngineStamp.of("test", "hash"),
        objectMapper,
        Clock.systemUTC(),
        new SimpleMeterRegistry());
  }

  private Insight insight(
      String type, String severity, String scope, boolean suppressed, OffsetDateTime cooldownUntil) {
    UUID id = UUID.randomUUID();
    return new Insight(
        id,
        OffsetDateTime.now(),
        type,
        severity,
        scope,
        "I4 title " + id,
        "I4 explanation " + id,
        objectMapper.createArrayNode().add("test"),
        null,
        null,
        "OK",
        List.of(),
        type + ":" + scope + ":" + id,
        cooldownUntil,
        suppressed,
        "OPEN",
        null,
        "test",
        "hash");
  }

  /** An insight row with an explicit {@code dataTrust} verdict (the M2 BLOCKED-gate cases). */
  private Insight insightWithTrust(String type, String severity, String scope, String dataTrust) {
    UUID id = UUID.randomUUID();
    return new Insight(
        id,
        OffsetDateTime.now(),
        type,
        severity,
        scope,
        "I4 title " + id,
        "I4 explanation " + id,
        objectMapper.createArrayNode().add("test"),
        null,
        null,
        dataTrust,
        List.of(),
        type + ":" + scope + ":" + id,
        null,
        false,
        "OPEN",
        null,
        "test",
        "hash");
  }

  private static InsightProperties properties(InsightProperties.Delivery delivery) {
    return new InsightProperties(null, null, null, null, null, null, null, null, null, delivery);
  }
}
