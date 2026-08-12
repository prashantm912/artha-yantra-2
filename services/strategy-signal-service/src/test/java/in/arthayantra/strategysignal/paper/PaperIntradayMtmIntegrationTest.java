package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategyengine.fills.InstrumentClass;
import in.arthayantra.strategysignal.paper.InstrumentMetaClient.InstrumentMeta;
import in.arthayantra.strategysignal.registry.RegistryService;
import in.arthayantra.strategysignal.registry.StrategyRepository;
import in.arthayantra.strategysignal.signals.SignalRepository;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The 15:45 mark-to-close sweep (mock profile): an OPEN position whose originating signal belongs to
 * a {@code risk.session.style: intraday} strategy is settled with {@code close_reason=INTRADAY_MTM}.
 * Regression pin for the {@code intradayOpen()} SELECT/mapper column mismatch (the shared row mapper
 * reads {@code stop_loss}/{@code take_profit}, so the hand-written column list must include them —
 * a mismatch aborts the WHOLE sweep on the first row, exactly when a position is open).
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
class PaperIntradayMtmIntegrationTest extends StrategySignalIntegrationTestBase {

  private static final String CONFIG =
      """
      schema: strategy-schema/v1
      id: mtm-sweep-it
      name: "MTM Sweep IT"
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
  @Autowired private SignalRepository signalRepo;
  @Autowired private StringRedisTemplate redis;

  @Test
  void sweepClosesSignalLinkedIntradayPositionWithIntradayMtmReason() {
    // Shared singleton IT DB: unique slug + name per method, unique symbol per run.
    String slug = "mtm-sweep-it-" + UUID.randomUUID().toString().substring(0, 8);
    UUID strategyId =
                    registry
                .create("MTM Sweep IT " + slug, null, List.of("it"), CONFIG.replace("id: mtm-sweep-it", "id: " + slug))
                .id();
    registry.publish(strategyId, null, null);
    UUID versionId = strategyRepo.latestVersion(strategyId).orElseThrow().id();

    String sym = "MTMIT-" + UUID.randomUUID().toString().substring(0, 8);
    OffsetDateTime now = OffsetDateTime.now();
    long signalId =
        signalRepo.insert(
            versionId, "NFO", sym, "1m", "ENTRY", "BUY",
            new BigDecimal("80.00"), new BigDecimal("40.00"), new BigDecimal("120.00"),
            new BigDecimal("0.80"), "{}", now, now.plusHours(1), null);

    paper.openOrder(
        new PaperService.OrderRequest(
            signalId, "NFO", sym, "BUY", 50, new BigDecimal("80.00"), null, null));
    assertThat(positions.findOpen("other", "NFO", sym, "BUY")).isPresent();

    // Audit V3: the 15:45 sweep settles at the live LTP (null price). A live intraday option ticks, so
    // seed a fresh last tick — the sweep prices off it honestly instead of the removed breakeven
    // fallback (a truly-tickless intraday close now refuses + alerts rather than fabricating 0 P&L).
    redis.opsForHash().put(
        "ticks:last", "NFO:" + sym,
        "{\"lastPrice\":\"82.00\",\"timestamp\":\"" + OffsetDateTime.now() + "\"}");

    // >= 1: the shared DB may carry other tests' intraday leftovers; ours MUST be among the closed.
    assertThat(paper.markToCloseIntraday()).isGreaterThanOrEqualTo(1);

    assertThat(positions.findOpen("other", "NFO", sym, "BUY")).isEmpty();
    var settled = positions.listClosed(null, null, sym, 10, 0);
    assertThat(settled).hasSize(1);
    assertThat(settled.get(0).closeReason()).isEqualTo("INTRADAY_MTM");
  }
}
