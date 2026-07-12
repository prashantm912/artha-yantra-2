package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import in.arthayantra.strategysignal.scalper.ScalperConfig;
import in.arthayantra.strategysignal.scalper.ScalperConfluenceGate;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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
 * ITEM 2 (chip task_3e95fade): the live BTST exit sweep + the relocated avoid-friday-carry guard.
 *
 * <p>The sim ({@code TickwiseGoldenRunner} btst branch, P0-5 #759) exits a btst carry at the NEXT
 * session's pre-close daily bar (close→close; {@code time_stop max_holding_days:1} fires one trading
 * day after entry). These tests prove the live port ({@code SignalEngine.preCloseEvaluate} →
 * {@code sweepBtstExit}) does the same, and that the guard relocation (review MED 2, 2026-07-12)
 * behaves: a position opened day D exits at the day D+1 pre-close sweep with the sim's reason
 * taxonomy (TIME_STOP); an avoid-friday-carry scalper's ACTIVE carry still EXITS on a Friday (the
 * guard gates ENTRY only now); and with no carry, the Friday ENTRY evaluation is skipped entirely —
 * proven against a Thursday control where the same setup DOES reach the confluence gate.
 *
 * <p>Drives {@code preCloseEvaluate} directly with a hand-built {@code Loaded}. The daily series is
 * seeded with the entry session's bar; today's pre-close daily bar is appended by the method under
 * test from the seeded 1m session. A stub candle client returns empty so no warm-up clobbers the
 * seeded series. The confluence gate is a Mockito mock (stubbed BLOCKED) so gate reachability is a
 * direct observable — invoked on the Thursday control, never on Friday / on the exit sweep.
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=true"})
class BtstPreCloseExitIntegrationTest extends StrategySignalIntegrationTestBase {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private static final LocalDate ENTRY_DAY = LocalDate.of(2026, 6, 29); // Monday
  private static final LocalDate EXIT_DAY = LocalDate.of(2026, 6, 30); // Tuesday (D+1)
  private static final LocalDate THURSDAY = LocalDate.of(2026, 6, 25);
  private static final LocalDate FRIDAY = LocalDate.of(2026, 6, 26);

  /** SMA(1) scores deterministically from the first bar — the entry eval NEVER silently warms. */
  private static final String CONFIG =
      """
      schema: strategy-schema/v1
      id: btst-exit-it
      name: "BTST Exit IT"
      version: 1.0.0
      universe: { mode: explicit, instruments: [ { exchange: NFO, tradingsymbol: BTSTIT } ] }
      timeframes: { primary: 3m }
      indicators:
        - { name: SMA, alias: sma_3m, timeframe: 3m, params: { period: 1 }, weight: 1.0,
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

    /** Gate reachability IS the Friday-guard observable — a mock makes it directly verifiable. */
    @Bean
    @Primary
    ScalperConfluenceGate mockConfluenceGate() {
      return Mockito.mock(ScalperConfluenceGate.class);
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
  @Autowired private ScalperConfluenceGate scalperGate;

  @BeforeEach
  void resetGate() {
    reset(scalperGate);
    // Any reached gate blocks cleanly (sparse diagnostic; recordRejection swallows persistence
    // hiccups) — the assertions discriminate on REACHABILITY, never on gate internals.
    when(scalperGate.evaluateWithDiagnostic(
            any(), any(), any(), anyInt(), any(), any(), any(), any(), anyBoolean()))
        .thenReturn(
            new ScalperConfluenceGate.Result(
                Optional.empty(),
                new ScalperConfluenceGate.RejectionDiagnostic(
                    "it_control_block", null, null, null, null, "control-case block", null, null,
                    List.of(), null, null, null, null, null, null),
                null));
  }

  @Test
  void carryOpenedDayDExitsAtDayD1PreCloseSweepWithTimeStop() {
    String sym = "BTSTIT-" + UUID.randomUUID().toString().substring(0, 8);
    Published p = publish(sym);
    var instrument = new StrategyDefinition.InstrumentRef("NFO", sym);

    seriesStore.append(new SeriesKey("NFO", sym, "1d"), dailyBar(ENTRY_DAY));
    for (int m = 17; m <= 19; m++) {
      seriesStore.append(new SeriesKey("NFO", sym, "1m"), bar(EXIT_DAY, 15, m));
    }
    seedTakenCarry(p.versionId(), sym, ENTRY_DAY);

    // scalper=null → definition-direction anchor; the sweep wiring is identical for a scalper carry.
    engine.preCloseEvaluate(loaded(p, instrument, null), instrument, EXIT_DAY);

    await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(openCount(sym)).isZero());
    assertThat(closeReason(sym)).isEqualTo("TIME_STOP");
    assertThat(signals.activeEntry(p.versionId(), "NFO", sym)).isEmpty();
  }

  @Test
  void avoidFridayCarryStillExitsAnActiveCarryOnFriday() {
    // Review MED 2 (a): the guard relocation's load-bearing case — under the OLD top-of-method guard
    // a Thursday carry of an avoid-friday-carry scalper could NEVER exit on Friday. Now it must.
    String sym = "BTSTFRX-" + UUID.randomUUID().toString().substring(0, 8);
    Published p = publish(sym);
    final var instrument = new StrategyDefinition.InstrumentRef("NFO", sym);

    seriesStore.append(new SeriesKey("NFO", sym, "1d"), dailyBar(THURSDAY));
    for (int m = 17; m <= 19; m++) {
      seriesStore.append(new SeriesKey("NFO", sym, "1m"), bar(FRIDAY, 15, m));
    }
    seedTakenCarry(p.versionId(), sym, THURSDAY);

    engine.preCloseEvaluate(loaded(p, instrument, avoidFridayScalper()), instrument, FRIDAY);

    await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(openCount(sym)).isZero());
    assertThat(closeReason(sym)).isEqualTo("TIME_STOP");
    // The exit sweep never consults the confluence gate (exits are never confluence-gated).
    verifyNoInteractions(scalperGate);
  }

  @Test
  void avoidFridayCarrySkipsTheFridayEntryEvaluation() {
    // Review MED 2 (b): with NO carry, the Friday guard must skip the ENTRY evaluation entirely —
    // no gate consult, no signal row. The Thursday control below proves this same setup otherwise
    // reaches the gate, so an accidentally-deleted guard fails THIS assertion, not silence.
    String sym = "BTSTFRE-" + UUID.randomUUID().toString().substring(0, 8);
    Published p = publish(sym);
    final var instrument = new StrategyDefinition.InstrumentRef("NFO", sym);

    seriesStore.append(new SeriesKey("NFO", sym, "3m"), bar(FRIDAY, 15, 15));
    for (int m = 17; m <= 19; m++) {
      seriesStore.append(new SeriesKey("NFO", sym, "1m"), bar(FRIDAY, 15, m));
    }

    engine.preCloseEvaluate(loaded(p, instrument, avoidFridayScalper()), instrument, FRIDAY);

    verifyNoInteractions(scalperGate);
    assertThat(signalCount(p.versionId())).isZero();
  }

  @Test
  void nonFridayEntryEvaluationReachesTheConfluenceGate() {
    // The control for the Friday-skip test: identical setup on a THURSDAY reaches the gate (carry
    // clock flag true), proving gate-reachability is a live observable — the Friday test's
    // verifyNoInteractions is therefore load-bearing, not vacuously green.
    String sym = "BTSTTHU-" + UUID.randomUUID().toString().substring(0, 8);
    Published p = publish(sym);
    var instrument = new StrategyDefinition.InstrumentRef("NFO", sym);

    seriesStore.append(new SeriesKey("NFO", sym, "3m"), bar(THURSDAY, 15, 15));
    for (int m = 17; m <= 19; m++) {
      seriesStore.append(new SeriesKey("NFO", sym, "1m"), bar(THURSDAY, 15, m));
    }

    engine.preCloseEvaluate(loaded(p, instrument, avoidFridayScalper()), instrument, THURSDAY);

    verify(scalperGate)
        .evaluateWithDiagnostic(any(), any(), any(), anyInt(), any(), any(), any(), any(), eq(true));
  }

  private record Published(UUID strategyId, UUID versionId, String slug, StrategyDefinition definition) {}

  /** Publishes a fresh config for {@code sym}; returns ids + the compiled definition. */
  private Published publish(String sym) {
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
    StrategyDefinition definition =
        StrategyCompiler.compile(signals.versionConfig(versionId).orElseThrow());
    return new Published(strategyId, versionId, slug, definition);
  }

  private SignalEngine.Loaded loaded(
      Published p, StrategyDefinition.InstrumentRef instrument, ScalperConfig scalper) {
    return new SignalEngine.Loaded(
        p.strategyId(), p.versionId(), p.slug(), "BTST Exit IT " + p.slug(), "1.0.0", "it-checksum",
        p.definition(), List.of(instrument), Set.of(), scalper, "other");
  }

  /** A minimal scalper config whose only load-bearing property is the avoid-friday-carry tag. */
  private static ScalperConfig avoidFridayScalper() {
    return new ScalperConfig(
        "NSE", "NIFTY 50", null, null, 2, null, new BigDecimal("0.5"), false,
        ScalperConfig.StructuralStop.NONE, false, false, false, false, false, false, false, false,
        false, List.of("scalper", "avoid-friday-carry"));
  }

  /** An ENTRY emitted at {@code day}'s pre-close (~15:19 IST), TAKEN, with an open paper position. */
  private void seedTakenCarry(UUID versionId, String sym, LocalDate day) {
    OffsetDateTime entryPreClose = day.atTime(15, 19).atOffset(IST);
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
  }

  private static EngineCandle dailyBar(LocalDate day) {
    OffsetDateTime start = day.atStartOfDay().atOffset(IST);
    return new EngineCandle(start, bd("100"), bd("101"), bd("99"), bd("100"), 1_000L);
  }

  private static EngineCandle bar(LocalDate day, int hour, int minute) {
    OffsetDateTime start = day.atTime(hour, minute).atOffset(IST);
    return new EngineCandle(start, bd("100"), bd("100.5"), bd("99.5"), bd("100"), 100L);
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

  private String closeReason(String sym) {
    return jdbc.queryForObject(
        "SELECT close_reason FROM paper_positions WHERE tradingsymbol=? AND status='CLOSED'",
        String.class, sym);
  }

  private int signalCount(UUID versionId) {
    Integer c =
        jdbc.queryForObject(
            "SELECT count(*) FROM signals WHERE strategy_version_id=?", Integer.class, versionId);
    return c == null ? 0 : c;
  }
}
