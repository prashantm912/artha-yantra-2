package in.arthayantra.strategysignal.notifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import in.arthayantra.strategysignal.registry.RegistryService;
import in.arthayantra.strategysignal.registry.StrategyRepository;
import in.arthayantra.strategysignal.signals.SignalEmitted;
import in.arthayantra.strategysignal.signals.SignalEmitted.ScalpDetail;
import in.arthayantra.strategysignal.signals.SignalRepository;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Z1 scalp-alert IT: a live scalper {@link SignalEmitted} (scalp side-channel present) → the alert
 * goes out with the strike/option-side/levels/confluence in the payload; non-scalper and opted-out
 * and feature-off all stay silent; an identical setup inside the dedupe window is suppressed.
 * Exercised SYNCHRONOUSLY via a hand-built instance (avoids the @Async proxy), mirroring {@link
 * NotifierIntegrationTest}.
 */
@SpringBootTest(properties = "spring.profiles.active=mock")
class ScalpAlertServiceIntegrationTest extends StrategySignalIntegrationTestBase {

  private static final String CONFIG =
      """
      schema: strategy-schema/v1
      id: scalp-alert-it
      name: "Scalp Alert IT"
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
  void scalperSignalPushesTheRichAlertWithStrikeSideAndLevels() {
    UUID id = create("scalp-alert-it-send", "Scalp Alert IT Send");
    registry.publish(id, null, null);
    registry.updateNotifications(id, true, "NTFY");
    UUID versionId = strategyRepo.latestVersion(id).orElseThrow().id();
    long signalId = insertSignal(versionId);

    NotifierClient client = Mockito.mock(NotifierClient.class);
    ScalpAlertService service = service(client, /* enabled= */ true);

    service.onScalpSignal(scalpEvent(signalId, versionId, new BigDecimal("22500")));

    ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
    verify(client, times(1)).send(eq("NTFY"), title.capture(), body.capture());
    // side (BUY), underlying, strike + option-side in the title
    assertThat(title.getValue()).contains("BUY", "NIFTY", "22500", "CE");
    // the option leg, entry/SL/target levels + confluence in the body
    assertThat(body.getValue())
        .contains("NIFTY24500CE", "entry 100", "SL 95", "target 110", "confluence 0.82");

    List<String> rows =
        jdbc.queryForList(
            "SELECT status FROM notification_events WHERE signal_id = ? AND detail = 'SCALP' ORDER BY id",
            String.class, signalId);
    assertThat(rows).containsExactly("SENT");
  }

  @Test
  void duplicateSetupInsideTheWindowIsSuppressed() {
    UUID id = create("scalp-alert-it-dedupe", "Scalp Alert IT Dedupe");
    registry.publish(id, null, null);
    registry.updateNotifications(id, true, "NTFY");
    UUID versionId = strategyRepo.latestVersion(id).orElseThrow().id();
    long signalId = insertSignal(versionId);

    NotifierClient client = Mockito.mock(NotifierClient.class);
    ScalpAlertService service = service(client, /* enabled= */ true);

    SignalEmitted event = scalpEvent(signalId, versionId, new BigDecimal("22500"));
    service.onScalpSignal(event); // SENT
    service.onScalpSignal(event); // identical setup within window → SUPPRESSED

    verify(client, times(1)).send(eq("NTFY"), anyString(), anyString());
    List<String> rows =
        jdbc.queryForList(
            "SELECT status FROM notification_events WHERE signal_id = ? ORDER BY id",
            String.class, signalId);
    assertThat(rows).containsExactly("SENT", "SUPPRESSED");
  }

  @Test
  void nonScalperSignalIsNotPushed() {
    UUID id = create("scalp-alert-it-nonscalp", "Scalp Alert IT NonScalp");
    registry.publish(id, null, null);
    registry.updateNotifications(id, true, "NTFY");
    UUID versionId = strategyRepo.latestVersion(id).orElseThrow().id();
    long signalId = insertSignal(versionId);

    NotifierClient client = Mockito.mock(NotifierClient.class);
    ScalpAlertService service = service(client, /* enabled= */ true);

    // a plain (non-scalper) ENTRY carries no scalp side-channel
    service.onScalpSignal(
        new SignalEmitted(
            signalId, versionId, "NSE", "RELIANCE", "BUY",
            new BigDecimal("100.00"), new BigDecimal("95.00"), new BigDecimal("110.00"),
            new BigDecimal("0.80"), new BigDecimal("0.50")));

    verify(client, never()).send(anyString(), anyString(), anyString());
  }

  @Test
  void optedOutStrategyIsNotPushed() {
    UUID id = create("scalp-alert-it-optout", "Scalp Alert IT OptOut");
    registry.publish(id, null, null); // notifications NOT enabled
    UUID versionId = strategyRepo.latestVersion(id).orElseThrow().id();
    long signalId = insertSignal(versionId);

    NotifierClient client = Mockito.mock(NotifierClient.class);
    ScalpAlertService service = service(client, /* enabled= */ true);

    service.onScalpSignal(scalpEvent(signalId, versionId, new BigDecimal("22500")));

    verify(client, never()).send(anyString(), anyString(), anyString());
  }

  @Test
  void featureDisabledIsNotPushed() {
    UUID id = create("scalp-alert-it-off", "Scalp Alert IT Off");
    registry.publish(id, null, null);
    registry.updateNotifications(id, true, "NTFY");
    UUID versionId = strategyRepo.latestVersion(id).orElseThrow().id();
    long signalId = insertSignal(versionId);

    NotifierClient client = Mockito.mock(NotifierClient.class);
    ScalpAlertService service = service(client, /* enabled= */ false); // global flag OFF

    service.onScalpSignal(scalpEvent(signalId, versionId, new BigDecimal("22500")));

    verify(client, never()).send(anyString(), anyString(), anyString());
  }

  private ScalpAlertService service(NotifierClient client, boolean enabled) {
    ScalpAlertDedupe dedupe = new ScalpAlertDedupe(300, Clock.systemUTC());
    return new ScalpAlertService(notificationRepo, client, dedupe, enabled, 3);
  }

  private static SignalEmitted scalpEvent(long signalId, UUID versionId, BigDecimal strike) {
    ScalpDetail scalp =
        new ScalpDetail(
            "NIFTY", "CE", strike, "NIFTY24500CE", new BigDecimal("120.00"), new BigDecimal("0.82"),
            null, null); // not an open-high-low strategy -> no OIP-AI tier on this alert
    return new SignalEmitted(
        signalId, versionId, "NSE", "RELIANCE", "BUY",
        new BigDecimal("100.00"), new BigDecimal("95.00"), new BigDecimal("110.00"),
        new BigDecimal("0.80"), new BigDecimal("0.50"), scalp);
  }

  /**
   * The IT DB is a shared singleton with no per-method cleanup, so each method needs its own
   * slug + name — the registry slug is the config {@code id}, both unique across strategies (a
   * duplicate is a 409). Rewrite the constant config's id per call.
   */
  private UUID create(String slug, String name) {
    String config = CONFIG.replace("id: scalp-alert-it", "id: " + slug);
    return (UUID) registry.create(name, null, List.of("it"), config).get("id");
  }

  private long insertSignal(UUID versionId) {
    OffsetDateTime now = OffsetDateTime.now();
    return signalRepo.insert(
        versionId, "NSE", "RELIANCE", "1m", "ENTRY", "BUY",
        new BigDecimal("100.00"), new BigDecimal("95.00"), new BigDecimal("110.00"),
        new BigDecimal("0.80"), "{}", now, now.plusHours(1));
  }
}
