package in.arthayantra.strategysignal.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategysignal.notifier.InsightAlertListener;
import in.arthayantra.strategysignal.notifier.NotifierClient;
import in.arthayantra.strategysignal.notifier.NotificationRepository;
import in.arthayantra.strategysignal.signals.InsightDeliveryAlert;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
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

  private int auditRows(UUID insightId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM notification_events WHERE insight_id = ?", Integer.class, insightId);
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

  private static InsightProperties properties(InsightProperties.Delivery delivery) {
    return new InsightProperties(null, null, null, null, null, null, null, null, null, delivery);
  }
}
