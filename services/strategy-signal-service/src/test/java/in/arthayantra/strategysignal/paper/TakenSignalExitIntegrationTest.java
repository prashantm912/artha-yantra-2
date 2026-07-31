package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import in.arthayantra.strategyengine.fills.InstrumentClass;
import in.arthayantra.strategysignal.paper.InstrumentMetaClient.InstrumentMeta;
import in.arthayantra.strategysignal.registry.RegistryService;
import in.arthayantra.strategysignal.registry.StrategyRepository;
import in.arthayantra.strategysignal.signals.SignalExited;
import in.arthayantra.strategysignal.signals.SignalRepository;
import in.arthayantra.strategysignal.signals.SignalTaken;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The TAKEN-entry exit lifecycle (audit P0-2, mock profile): (1) {@code activeEntry} keeps a TAKEN
 * entry anchored so the engine's exit passes keep running after a take; (2) an engine EXIT
 * ({@link SignalExited}) closes the linked open paper position with the engine's reason; (3) any
 * position close resolves the TAKEN anchor to EXPIRED once no linked position remains open; (4) a
 * taken single-leg open carries the signal's persisted SL/TP as bracket levels.
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
class TakenSignalExitIntegrationTest extends StrategySignalIntegrationTestBase {

  private static final String CONFIG =
      """
      schema: strategy-schema/v1
      id: taken-exit-it
      name: "Taken Exit IT"
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

  @TestConfiguration
  static class Stubs {
    @Bean
    @Primary
    InstrumentMetaClient stubMeta() {
      return (exchange, tradingsymbol) ->
          new InstrumentMeta(InstrumentClass.OPTION, new BigDecimal("0.05"), 50);
    }
  }

  @Autowired private PaperService paper;
  @Autowired private PaperPositionRepository positions;
  @Autowired private RegistryService registry;
  @Autowired private StrategyRepository strategyRepo;
  @Autowired private SignalRepository signals;
  @Autowired private ApplicationEventPublisher events;
  @Autowired private StringRedisTemplate redis;

  @Test
  void activeEntryAnchorsTakenEntries() {
    Seed seed = seedTakenSignalWithPosition("anchor");

    var anchor = signals.activeEntry(seed.versionId(), "NFO", seed.sym());
    assertThat(anchor).isPresent();
    assertThat(anchor.get().id()).isEqualTo(seed.signalId());
    assertThat(anchor.get().status()).isEqualTo("TAKEN");
  }

  @Test
  void engineExitClosesThePaperPositionWithTheEngineReasonAndResolvesTheAnchor() {
    Seed seed = seedTakenSignalWithPosition("exit");

    events.publishEvent(new SignalExited(seed.signalId(), -1, "STRUCTURAL_STOP"));

    assertThat(positions.findOpen("other", "NFO", seed.sym(), "BUY")).isEmpty();
    var settled = positions.listClosed(null, null, seed.sym(), 10, 0);
    assertThat(settled).hasSize(1);
    assertThat(settled.get(0).closeReason()).isEqualTo("STRUCTURAL_STOP");
    // The AFTER_COMMIT resolver expires the TAKEN anchor once no linked position remains open.
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> assertThat(signals.find(seed.signalId()).orElseThrow().status()).isEqualTo("EXPIRED"));
  }

  @Test
  void nonEngineCloseAlsoResolvesTheTakenAnchor() {
    Seed seed = seedTakenSignalWithPosition("mtm");

    assertThat(paper.markToCloseIntraday()).isGreaterThanOrEqualTo(1);

    assertThat(positions.findOpen("other", "NFO", seed.sym(), "BUY")).isEmpty();
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> assertThat(signals.find(seed.signalId()).orElseThrow().status()).isEqualTo("EXPIRED"));
  }

  @Test
  void takenSingleLegOpenCarriesTheSignalBrackets() {
    Seed seed = seedTakenSignalWithPosition("brackets");

    var pos = positions.findOpen("other", "NFO", seed.sym(), "BUY").orElseThrow();
    assertThat(pos.stopLoss()).isEqualByComparingTo("40.00");
    assertThat(pos.takeProfit()).isEqualByComparingTo("120.00");
  }

  private record Seed(UUID versionId, long signalId, String sym) {}

  /** Publishes a fresh strategy, inserts a taken ENTRY (SL 40 / TP 120) and opens its position. */
  private Seed seedTakenSignalWithPosition(String tag) {
    String slug = "taken-exit-it-" + tag + "-" + UUID.randomUUID().toString().substring(0, 8);
    UUID strategyId =
                    registry
                .create(
                    "Taken Exit IT " + slug,
                    null,
                    List.of("it"),
                    CONFIG.replace("id: taken-exit-it", "id: " + slug))
                .id();
    registry.publish(strategyId, null, null);
    UUID versionId = strategyRepo.latestVersion(strategyId).orElseThrow().id();

    String sym = "TKNIT-" + UUID.randomUUID().toString().substring(0, 8);
    OffsetDateTime now = OffsetDateTime.now();
    long signalId =
        signals.insert(
            versionId, "NFO", sym, "1m", "ENTRY", "BUY",
            new BigDecimal("80.00"), new BigDecimal("40.00"), new BigDecimal("120.00"),
            new BigDecimal("0.80"), "{}", now, now.plusHours(1), null);
    signals.transition(signalId, "TAKEN");
    // The PaperSignalListener path (SignalTaken → openSingle) opens the primary leg + brackets.
    events.publishEvent(new SignalTaken(signalId, 50, new BigDecimal("80.00")));
    assertThat(positions.findOpen("other", "NFO", sym, "BUY")).isPresent();
    // Audit V3: the engine-exit / 15:45 sweep settle at the live LTP (null-price tick-engine default).
    // A live intraday option ticks, so seed a fresh last tick — the close now prices off it honestly
    // rather than the removed breakeven fallback (an LTP-less intraday close refuses + alerts instead).
    redis.opsForHash().put(
        "ticks:last", "NFO:" + sym,
        "{\"lastPrice\":\"82.00\",\"timestamp\":\"" + OffsetDateTime.now() + "\"}");
    return new Seed(versionId, signalId, sym);
  }
}
