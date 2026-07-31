package in.arthayantra.strategysignal.notifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import in.arthayantra.strategysignal.registry.RegistryService;
import in.arthayantra.strategysignal.registry.StrategyRepository;
import in.arthayantra.strategysignal.signals.SignalEmitted;
import in.arthayantra.strategysignal.signals.SignalRepository;
import in.arthayantra.strategysignal.notifier.NotificationRepository.DeliveryStats;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Phase 41 notifier IT: the checksum-invariance line (toggling notifications never perturbs a
 * version's D18 checksum — the Phase-41 FAIL line), plus a SENT audit and cooldown SUPPRESSION.
 * NotifierService is exercised SYNCHRONOUSLY via a hand-built instance (avoids the @Async proxy).
 */
@SpringBootTest(properties = "spring.profiles.active=mock")
class NotifierIntegrationTest extends StrategySignalIntegrationTestBase {

  private static final String CONFIG =
      """
      schema: strategy-schema/v1
      id: notifier-it
      name: "Notifier IT"
      version: 1.0.0
      universe: { mode: explicit, instruments: [ { exchange: NSE, tradingsymbol: RELIANCE } ] }
      timeframes: { primary: 1m }
      indicators:
        - { name: RSI, alias: rsi_1m, timeframe: 1m, params: { period: 2 }, weight: 1.0,
            normalize: { type: step, bands: [ { score: 1.0 } ] } }
      entry_rules: { direction: long, gate: { all: [ "close > 1" ] }, scoring: { threshold: 0.05 } }
      exit_rules: [ { type: stop_loss, params: { basis: premium_pct, value: 50 } } ]
      risk: { position_sizing: { method: fixed_quantity, params: { quantity: 1 } }, max_positions: 1,
              session: { style: intraday } }
      """;

  @Autowired RegistryService registry;
  @Autowired StrategyRepository strategyRepo;
  @Autowired SignalRepository signalRepo;
  @Autowired NotificationRepository notificationRepo;
  @Autowired JdbcTemplate jdbc;

  @Test
  void togglingNotificationsDoesNotPerturbTheVersionChecksum() {
    UUID id = create("notifier-it-checksum", "Notifier IT Checksum");
    registry.publish(id, null, null);
    String before = registry.detail(id, null).checksum();

    registry.updateNotifications(id, true, "NTFY");

    String after = registry.detail(id, null).checksum();
    assertThat(after).isEqualTo(before);
  }

  @Test
  void sendAuditsSentAndCooldownSuppressesTheRepeat() {
    UUID id = create("notifier-it-send", "Notifier IT Send");
    registry.publish(id, null, null);
    registry.updateNotifications(id, true, "NTFY");
    UUID versionId = strategyRepo.latestVersion(id).orElseThrow().id();
    long signalId = insertSignal(versionId);

    NotifierClient client = Mockito.mock(NotifierClient.class);
    Mockito.when(client.configured("NTFY")).thenReturn(true);
    FloodControl flood = new FloodControl(600, 30, Clock.systemUTC());
    NotifierService service = new NotifierService(notificationRepo, client, flood, 3, false);

    SignalEmitted event =
        new SignalEmitted(
            signalId, versionId, "NSE", "RELIANCE", "BUY",
            new BigDecimal("100.00"), new BigDecimal("95.00"), new BigDecimal("110.00"),
            new BigDecimal("0.80"), new BigDecimal("0.50"));

    service.onSignal(event); // SENT
    service.onSignal(event); // within cooldown → SUPPRESSED

    verify(client, times(1)).send(org.mockito.ArgumentMatchers.eq("NTFY"),
        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());

    List<String> statuses =
        jdbc.queryForList(
            "SELECT status FROM notification_events WHERE signal_id = ? ORDER BY id", String.class,
            signalId);
    assertThat(statuses).containsExactly("SENT", "SUPPRESSED");
  }

  /**
   * V15 input: {@code deliveryStats} buckets rows by status over the window. On the shared singleton
   * DB the count is global, so isolate this method's contribution via a before/after DELTA (surefire
   * runs methods sequentially, so nothing else writes between the two synchronous reads).
   */
  @Test
  void deliveryStatsBucketsByStatusOverTheWindow() {
    UUID id = create("notifier-it-health", "Notifier IT Health");
    Instant since = Instant.now().minusSeconds(3600);
    DeliveryStats before = notificationRepo.deliveryStats(since);

    notificationRepo.record(null, id, "NTFY", "SENT", 1, null);
    notificationRepo.record(null, id, "NTFY", "FAILED", 3, "boom");
    notificationRepo.record(null, id, "NTFY", "FAILED", 3, "boom");
    notificationRepo.record(null, id, "NTFY", "SUPPRESSED", 1, "COOLDOWN");

    DeliveryStats after = notificationRepo.deliveryStats(since);
    assertThat(after.sent() - before.sent()).isEqualTo(1L);
    assertThat(after.failed() - before.failed()).isEqualTo(2L);
    assertThat(after.suppressed() - before.suppressed()).isEqualTo(1L);
  }

  /**
   * The IT DB is a shared singleton with no per-method cleanup, so each method needs its own
   * slug + name — the registry slug is the config {@code id}, and both id and name are unique
   * across strategies (a duplicate is a 409). Rewrite the constant config's id per call.
   */
  private UUID create(String slug, String name) {
    String config = CONFIG.replace("id: notifier-it", "id: " + slug);
    return registry.create(name, null, List.of("it"), config).id();
  }

  private long insertSignal(UUID versionId) {
    OffsetDateTime now = OffsetDateTime.now();
    return signalRepo.insert(
        versionId, "NSE", "RELIANCE", "1m", "ENTRY", "BUY",
        new BigDecimal("100.00"), new BigDecimal("95.00"), new BigDecimal("110.00"),
        new BigDecimal("0.80"), "{}", now, now.plusHours(1), null);
  }
}
