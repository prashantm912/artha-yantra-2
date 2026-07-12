package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategyengine.config.StrategyCompiler;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.fills.InstrumentClass;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.SeriesKey;
import in.arthayantra.strategysignal.paper.InstrumentMetaClient;
import in.arthayantra.strategysignal.paper.InstrumentMetaClient.InstrumentMeta;
import in.arthayantra.strategysignal.registry.RegistryService;
import in.arthayantra.strategysignal.registry.StrategyRepository;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

/**
 * ITEM 2 (chip task_3e95fade): the live BTST exit sweep. The sim ({@code TickwiseGoldenRunner} btst
 * branch, P0-5 #759) exits a btst carry at the NEXT session's pre-close daily bar (close→close;
 * {@code time_stop max_holding_days:1} fires one trading day after entry). This test proves the live
 * port ({@code SignalEngine.preCloseEvaluate} → {@code sweepBtstExit}) does the same: a position opened
 * day D exits at the day D+1 pre-close sweep, and the linked paper position closes with the sim's
 * reason taxonomy ({@code TIME_STOP}).
 *
 * <p>Drives {@code preCloseEvaluate} directly with a hand-built {@code Loaded} (scalper=null, so the
 * exit anchors on the definition direction and the confluence gate is not involved — the sweep wiring
 * is identical for a scalper carry, only the held-side resolution differs). The daily series is seeded
 * with the entry session's bar; today's pre-close daily bar is appended by the method under test from
 * the seeded 1m session. A stub candle client returns empty so no warm-up clobbers the seeded series.
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=true"})
class BtstPreCloseExitIntegrationTest extends StrategySignalIntegrationTestBase {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private static final LocalDate ENTRY_DAY = LocalDate.of(2026, 6, 29); // Monday
  private static final LocalDate EXIT_DAY = LocalDate.of(2026, 6, 30); // Tuesday (D+1)

  private static final String CONFIG =
      """
      schema: strategy-schema/v1
      id: btst-exit-it
      name: "BTST Exit IT"
      version: 1.0.0
      universe: { mode: explicit, instruments: [ { exchange: NFO, tradingsymbol: BTSTIT } ] }
      timeframes: { primary: 3m }
      indicators:
        - { name: RSI, alias: rsi_3m, timeframe: 3m, params: { period: 2 }, weight: 1.0,
            normalize: { type: step, bands: [ { score: 1.0 } ] } }
      entry_rules: { direction: both, gate: { all: [ "volume > 0" ] }, scoring: { threshold: 0.05 } }
      exit_rules:
        - { type: stop_loss, params: { basis: premium_pct, value: 50 } }
        - { type: time_stop, params: { max_holding_days: 1 } }
      risk: { position_sizing: { method: fixed_quantity, params: { quantity: 1 } }, max_positions: 1,
              session: { style: btst, allow_overnight: true, pre_close_at: "15:20", square_off: "15:20" } }
      """;

  @TestConfiguration
  static class Stubs {
    @Bean
    @Primary
    InstrumentMetaClient stubMeta() {
      return (exchange, tradingsymbol) ->
          new InstrumentMeta(InstrumentClass.OPTION, new BigDecimal("0.05"), 50);
    }

    /** Every warm-up returns empty so refreshFromRest never clobbers the directly-seeded series. */
    @Bean
    @Primary
    MarketDataCandlesClient stubCandles(RestClient.Builder builder, ObjectMapper objectMapper) {
      return new MarketDataCandlesClient(builder, objectMapper, "http://localhost:0") {
        @Override
        public List<EngineCandle> fetch(
            String exchange, String tradingsymbol, String interval,
            OffsetDateTime from, OffsetDateTime to) {
          return List.of();
        }
      };
    }
  }

  @Autowired private SignalEngine engine;
  @Autowired private LiveSeriesStore seriesStore;
  @Autowired private SignalRepository signals;
  @Autowired private RegistryService registry;
  @Autowired private StrategyRepository strategyRepo;
  @Autowired private ApplicationEventPublisher events;
  @Autowired private StringRedisTemplate redis;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void carryOpenedDayDExitsAtDayD1PreCloseSweepWithTimeStop() {
    String sym = "BTSTIT-" + UUID.randomUUID().toString().substring(0, 8);

    // A published-config version for the signal FK + the compiled definition the sweep evaluates.
    String slug = "btst-exit-it-" + UUID.randomUUID().toString().substring(0, 8);
    UUID strategyId =
        (UUID)
            registry
                .create(
                    "BTST Exit IT " + slug,
                    null,
                    List.of("it"),
                    CONFIG.replace("id: btst-exit-it", "id: " + slug).replace("BTSTIT", sym))
                .get("id");
    UUID versionId = strategyRepo.latestVersion(strategyId).orElseThrow().id();
    final StrategyDefinition definition =
        StrategyCompiler.compile(signals.versionConfig(versionId).orElseThrow());
    final var instrument = new StrategyDefinition.InstrumentRef("NFO", sym);

    // Seed the entry session's (D) daily bar + today's (D+1) 1m session. refreshFromRest returns empty
    // (stub), so these are the only bars; preCloseEvaluate appends the D+1 pre-close daily bar itself.
    seriesStore.append(new SeriesKey("NFO", sym, "1d"), dailyBar(ENTRY_DAY));
    for (int m = 17; m <= 19; m++) {
      seriesStore.append(new SeriesKey("NFO", sym, "1m"), oneMinuteBar(EXIT_DAY, 15, m));
    }

    // The carry: an ENTRY emitted at the D pre-close (~15:20 IST), TAKEN, with an open paper position.
    OffsetDateTime entryPreClose = ENTRY_DAY.atTime(15, 19).atOffset(IST);
    long signalId =
        signals.insert(
            versionId, "NFO", sym, "1d", "ENTRY", "BUY",
            new BigDecimal("100.00"), null, null, new BigDecimal("0.80"), "{}",
            entryPreClose, entryPreClose.plusHours(48));
    signals.transition(signalId, "TAKEN");
    events.publishEvent(new SignalTaken(signalId, 50, new BigDecimal("100.00")));
    assertThat(openCount(sym)).isEqualTo(1);
    // The exit settles at the last REAL tick (#694) — seed one so the close prices honestly.
    redis
        .opsForHash()
        .put("ticks:last", "NFO:" + sym,
            "{\"lastPrice\":\"100.00\",\"timestamp\":\"" + OffsetDateTime.now(IST) + "\"}");

    // Build a Loaded and run the D+1 pre-close clock. scalper=null → definition-direction anchor.
    SignalEngine.Loaded loaded =
        new SignalEngine.Loaded(
            strategyId, versionId, slug, "BTST Exit IT " + slug, "1.0.0", "it-checksum",
            definition, List.of(instrument), Set.of(), null, "other");
    engine.preCloseEvaluate(loaded, instrument, EXIT_DAY);

    // The carry exited at the D+1 pre-close: the paper position is CLOSED with the sim's TIME_STOP reason.
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(openCount(sym)).isZero());
    String closeReason =
        jdbc.queryForObject(
            "SELECT close_reason FROM paper_positions WHERE tradingsymbol=? AND status='CLOSED'",
            String.class, sym);
    assertThat(closeReason).isEqualTo("TIME_STOP");
    // The engine emitted an EXIT resolving the anchor (the entry is no longer ACTIVE/TAKEN).
    assertThat(signals.activeEntry(versionId, "NFO", sym)).isEmpty();
  }

  private static EngineCandle dailyBar(LocalDate day) {
    OffsetDateTime start = day.atStartOfDay().atOffset(IST);
    return new EngineCandle(
        start, bd("100"), bd("101"), bd("99"), bd("100"), 1_000L);
  }

  private static EngineCandle oneMinuteBar(LocalDate day, int hour, int minute) {
    OffsetDateTime start = day.atTime(hour, minute).atOffset(IST);
    return new EngineCandle(
        start, bd("100"), bd("100.5"), bd("99.5"), bd("100"), 100L);
  }

  private static BigDecimal bd(String v) {
    return new BigDecimal(v);
  }

  private int openCount(String sym) {
    Integer c =
        jdbc.queryForObject(
            "SELECT count(*) FROM paper_positions WHERE tradingsymbol=? AND status='OPEN'",
            Integer.class, sym);
    return c == null ? 0 : c;
  }
}
