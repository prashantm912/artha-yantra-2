package in.arthayantra.strategysignal.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategysignal.notifier.InsightAlertListener;
import in.arthayantra.strategysignal.notifier.NotifierClient;
import in.arthayantra.strategysignal.notifier.NotificationRepository;
import in.arthayantra.strategysignal.signals.InsightDeliveryAlert;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Unit coverage for the fail-closed I4 delivery seam. */
class InsightDeliveryTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-07-26T09:00:00+05:30");

  @Test
  void shippedDefaultsDoNotDeliverAnyChannel() {
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    InsightRepository repository = mock(InsightRepository.class);
    InsightProperties properties = new InsightProperties(null, null, null, null, null, null, null, null, null, null);
    InsightPublisher publisher =
        new InsightPublisher(
            mock(StringRedisTemplate.class), MAPPER, events, repository, properties);

    assertThat(publisher.publish(insight(Severity.NOTICE, "market"))).isFalse();
    assertThat(properties.delivery().ws()).isFalse();
    assertThat(properties.delivery().ntfyEnabled()).isFalse();
    assertThat(properties.delivery().telegramEnabled()).isFalse();

    verify(events, never()).publishEvent(any());
    verify(repository, never()).isMuted(anyString(), anyString());
  }

  @Test
  void noticeWarnAndCriticalDeliverButInfoNeverDoes() {
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    InsightRepository repository = mock(InsightRepository.class);
    InsightPublisher publisher = publisher(new InsightProperties.Delivery(false, true, false, Severity.NOTICE), events, repository);

    assertThat(publisher.publish(insight(Severity.INFO, "info"))).isFalse();
    verify(events, never()).publishEvent(any());

    publisher.publish(insight(Severity.NOTICE, "notice"));
    publisher.publish(insight(Severity.WARN, "warn"));
    publisher.publish(insight(Severity.CRITICAL, "critical"));

    verify(events, times(3)).publishEvent(any(InsightDeliveryAlert.class));
  }

  @Test
  void suppressedInsightDoesNotDeliver() {
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    InsightRepository repository = mock(InsightRepository.class);
    InsightPublisher publisher = publisher(new InsightProperties.Delivery(true, true, true, Severity.NOTICE), events, repository);

    assertThat(publisher.publish(insight(Severity.CRITICAL, "suppressed", true))).isFalse();

    verify(events, never()).publishEvent(any());
    verify(repository, never()).isMuted(anyString(), anyString());
  }

  @Test
  void mutedStrategyScopeDoesNotDeliverAnUnmutedScopeAtTheSameSeverity() {
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    InsightRepository repository = mock(InsightRepository.class);
    when(repository.isMuted(eq("DATA_TRUST"), eq("strategy:muted"))).thenReturn(true);
    when(repository.isMuted(eq("DATA_TRUST"), eq("strategy:unmuted"))).thenReturn(false);
    InsightPublisher publisher = publisher(new InsightProperties.Delivery(false, true, false, Severity.NOTICE), events, repository);

    Insight muted = insight("DATA_TRUST", Severity.WARN, "strategy:muted");
    Insight unmuted = insight("DATA_TRUST", Severity.WARN, "strategy:unmuted");

    publisher.publish(muted);
    publisher.publish(unmuted);

    verify(events).publishEvent(any(InsightDeliveryAlert.class));
    verify(repository).isMuted("DATA_TRUST", "strategy:muted");
    verify(repository).isMuted("DATA_TRUST", "strategy:unmuted");
  }

  @Test
  void coolingCandidateIsPersistedAsSuppressedAndDoesNotDeliver() {
    InsightCandidate candidate =
        new InsightCandidate(
            InsightType.DATA_TRUST,
            Severity.NOTICE,
            "dataops",
            "cooling",
            "cooling",
            List.of(),
            null,
            null,
            DataTrust.DEGRADED,
            List.of("cooldown"),
            "DATA_TRUST:cooling-" + UUID.randomUUID(),
            15,
            null,
            false);
    InsightGenerator generator = mock(InsightGenerator.class);
    when(generator.type()).thenReturn(InsightType.DATA_TRUST);
    when(generator.generate(any())).thenReturn(List.of(candidate));

    InsightRepository repository = mock(InsightRepository.class);
    when(repository.isCooling(eq(candidate.dedupeKey()), any())).thenReturn(true);
    when(repository.insertOrRefresh(any(Insight.class)))
        .thenAnswer(
            invocation -> new InsightRepository.Upsert(invocation.getArgument(0), true));
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    InsightPublisher publisher = publisher(new InsightProperties.Delivery(false, true, false, Severity.NOTICE), events, repository);
    TrustService trustService = mock(TrustService.class);

    InsightEngine engine =
        new InsightEngine(
            List.of(generator),
            repository,
            trustService,
            mock(BookHeatReader.class),
            mock(ContextClient.class),
            mock(RejectionReader.class),
            mock(PortfolioReader.class),
            mock(StrategyEvidenceReader.class),
            publisher,
            new InsightProperties(null, null, null, null, null, null, null, null, null, null),
            EngineStamp.of("test", "hash"),
            MAPPER,
            Clock.fixed(Instant.parse("2026-07-26T03:30:00Z"), ZoneOffset.UTC),
            new SimpleMeterRegistry());

    engine.runTrustSweep();

    var row = org.mockito.ArgumentCaptor.forClass(Insight.class);
    verify(repository).insertOrRefresh(row.capture());
    assertThat(row.getValue().suppressed()).isTrue();
    assertThat(row.getValue().cooldownUntil()).isAfter(NOW);
    verify(events, never()).publishEvent(any());
  }

  @Test
  void deliveredInsightIsAuditedAndUnconfiguredDeliveryIsNot() {
    NotificationRepository repository = mock(NotificationRepository.class);
    NotifierClient client = mock(NotifierClient.class);
    when(client.configured("NTFY")).thenReturn(true);
    InsightAlertListener listener = new InsightAlertListener(repository, client, 3);
    UUID deliveredId = UUID.randomUUID();

    listener.onInsightDelivery(
        new InsightDeliveryAlert(deliveredId, "title", "body", "market", "NTFY"));

    verify(client).send("NTFY", "ArthaYantra title", "body\nScope: market");
    verify(repository).recordInsight(deliveredId, null, "NTFY", "SENT", 1, null);

    when(client.configured("TELEGRAM")).thenReturn(false);
    listener.onInsightDelivery(
        new InsightDeliveryAlert(UUID.randomUUID(), "title", "body", "market", "TELEGRAM"));
    verify(client, never()).send(eq("TELEGRAM"), anyString(), anyString());
  }

  @Test
  void auditFailureAfterSuccessfulSendNeverResends() {
    // Pre-arm review M4: transport succeeded, ONLY the audit insert fails — the retry loop must not
    // re-send the phone notification (before the fix, send + audit shared one retry block and the
    // owner got the same push up to retry-max times).
    NotificationRepository repository = mock(NotificationRepository.class);
    doThrow(new RuntimeException("audit insert failed"))
        .when(repository)
        .recordInsight(any(), any(), anyString(), anyString(), anyInt(), any());
    NotifierClient client = mock(NotifierClient.class);
    when(client.configured("NTFY")).thenReturn(true);
    InsightAlertListener listener = new InsightAlertListener(repository, client, 3);

    listener.onInsightDelivery(
        new InsightDeliveryAlert(UUID.randomUUID(), "title", "body", "market", "NTFY"));

    verify(client, times(1)).send("NTFY", "ArthaYantra title", "body\nScope: market");
    verify(repository, times(1)).recordInsight(any(), any(), anyString(), anyString(), anyInt(), any());
  }

  private static InsightPublisher publisher(
      InsightProperties.Delivery delivery,
      ApplicationEventPublisher events,
      InsightRepository repository) {
    return new InsightPublisher(mock(StringRedisTemplate.class), MAPPER, events, repository,
        new InsightProperties(null, null, null, null, null, null, null, null, null, delivery));
  }

  private static Insight insight(Severity severity, String scope) {
    return insight(severity, scope, false);
  }

  private static Insight insight(String type, Severity severity, String scope) {
    Insight base = insight(severity, scope, false);
    return new Insight(
        base.id(), base.generatedAt(), type, base.severity(), base.scope(), base.title(), base.explanation(),
        base.evidence(), base.priority(), base.priorityDetail(), base.dataTrust(), base.trustReasons(),
        type + ":" + scope + ":" + UUID.randomUUID(), base.cooldownUntil(), base.suppressed(), base.status(),
        base.expiresAt(), base.engineVersion(), base.configHash());
  }

  private static Insight insight(Severity severity, String scope, boolean suppressed) {
    return new Insight(
        UUID.randomUUID(),
        NOW,
        "DATA_TRUST",
        severity.name(),
        scope,
        "title",
        "body",
        MAPPER.createArrayNode(),
        null,
        null,
        "DEGRADED",
        List.of(),
        "DATA_TRUST:" + scope + ":" + UUID.randomUUID(),
        null,
        suppressed,
        "OPEN",
        null,
        "test",
        "hash");
  }
}
