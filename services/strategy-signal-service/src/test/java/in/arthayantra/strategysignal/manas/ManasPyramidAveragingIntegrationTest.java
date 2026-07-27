package in.arthayantra.strategysignal.manas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategyengine.fills.InstrumentClass;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategysignal.paper.InstrumentMetaClient;
import in.arthayantra.strategysignal.paper.InstrumentMetaClient.InstrumentMeta;
import in.arthayantra.strategysignal.paper.PaperPositionRepository;
import in.arthayantra.strategysignal.paper.PaperPositionRepository.PositionRow;
import in.arthayantra.strategysignal.registry.RegistryService;
import in.arthayantra.strategysignal.registry.StrategyRepository;
import in.arthayantra.strategysignal.signals.Books;
import in.arthayantra.strategysignal.signals.EmissionGuard;
import in.arthayantra.strategysignal.signals.MarketDataCandlesClient;
import in.arthayantra.strategysignal.signals.SignalRepository;
import in.arthayantra.strategysignal.signals.SignalTaken;
import in.arthayantra.strategysignal.swing.SwingBatchEngine;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The Manas Arora §3.4 pyramiding flag path ({@code artha.manas-arora.pyramid.enabled}, #612 / F2 —
 * currently OFF live) exercised end-to-end against a REAL Spring context + Timescale (chip
 * task_67cf8715: the mock-only {@code ManasAroraSwingEngineTest} proved the decision math, but no
 * IntegrationTest proved the context + DB wiring). With the flag ON, a held Manas winner that makes a
 * fresh qualifying move takes a pyramid ADD as a 2nd ENTRY signal, and — via the real
 * AutoPaper→SignalTaken→PaperSignalListener→PaperService chain — that add AVERAGES into the ONE open
 * paper position (§3.4's "averaged single-lot" design: {@code PaperService.upsertPosition}), never a
 * second position. This proves the wiring, not new semantics.
 *
 * <p>Only the two market-data collaborators the engine reaches over REST are stubbed (candles +
 * emission guard); the flag itself flows through the REAL {@link ManasPyramidPolicy} bean.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.signals.engine-enabled=false",
      "artha.manas-arora.pyramid.enabled=true"
    })
class ManasPyramidAveragingIntegrationTest extends StrategySignalIntegrationTestBase {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

  @TestConfiguration
  static class Stubs {
    /** Equity meta (lot 1) so the paper fill sizes the share qty directly. */
    @Bean
    @Primary
    InstrumentMetaClient stubMeta() {
      return (exchange, tradingsymbol) ->
          new InstrumentMeta(InstrumentClass.EQUITY, new BigDecimal("0.05"), 1);
    }

    /** The engine's daily-bar source (equities do not tick) — a Mockito mock the test crafts. */
    @Bean
    @Primary
    MarketDataCandlesClient stubCandles() {
      return mock(MarketDataCandlesClient.class);
    }

    /** The emission guard SPI — mocked so sizing/gating is deterministic (mirrors the unit test). */
    @Bean
    @Primary
    EmissionGuard stubGuard() {
      return mock(EmissionGuard.class);
    }
  }

  @Autowired private SwingBatchEngine engine;
  @Autowired private ManasPyramidPolicy pyramidPolicy; // real bean — reads pyramid.enabled=true
  @Autowired private SignalRepository signals;
  @Autowired private RegistryService registry;
  @Autowired private StrategyRepository strategyRepo;
  @Autowired private PaperPositionRepository positions;
  @Autowired private ApplicationEventPublisher events;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private MarketDataCandlesClient candles; // the @Primary mock
  @Autowired private EmissionGuard guard; // the @Primary mock

  @Test
  void aFlagOnPyramidAddOpensASecondLotThatAveragesIntoTheOnePaperPosition() throws Exception {
    // Auto-paper the manas-arora book (the live pyramid path auto-papers each emitted entry).
    jdbc.update("DELETE FROM risk_settings WHERE book = ? AND key = 'auto_paper_trade'", Books.MANAS_ARORA);
    jdbc.update(
        "INSERT INTO risk_settings (book, key, value) VALUES (?, 'auto_paper_trade', ?::jsonb)",
        Books.MANAS_ARORA, "{\"enabled\": true}");

    String uid = UUID.randomUUID().toString().substring(0, 8);
    String sym = "MPAIT" + uid; // unique symbol — the shared DB has no per-method cleanup
    UUID versionId = publishManasBreakout(uid);

    // Lot 1: a held TAKEN entry at 142, with an open paper position of 10 opened via the real listener.
    long lot1 =
        signals.insert(
            versionId, "NSE", sym, "1d", "ENTRY", "BUY",
            new BigDecimal("142"), new BigDecimal("137"), new BigDecimal("170"),
            new BigDecimal("0.80"), "{}", bar(19), bar(19).plusDays(1), null);
    signals.transition(lot1, "TAKEN");
    events.publishEvent(new SignalTaken(lot1, 10, new BigDecimal("142")));
    PositionRow afterLot1 =
        positions.findOpen(Books.MANAS_ARORA, "NSE", sym, "BUY").orElseThrow();
    assertThat(afterLot1.qty()).isEqualTo(10L);
    BigDecimal avgAfterLot1 = afterLot1.avgEntryPrice();

    // The funnel offers this held name again, breaking out on volume ~+7% above lot 1 (152 vs 142).
    List<EngineCandle> series = craft(3_000L);
    when(candles.fetch(eq("NSE"), eq(sym), eq("1d"), any(), any())).thenReturn(series);
    ManasFunnelClient funnel = mock(ManasFunnelClient.class);
    when(funnel.buyableAndOnDeck())
        .thenReturn(
            List.of(
                new ManasFunnelClient.Candidate(
                    sym, new BigDecimal("152"), new BigDecimal("150"), "breakout", null,
                    new BigDecimal("150"), null, false)));
    // Guard: entry allowed, a deterministic qty of 10, and the book well within the §3.4.3 risk cap.
    when(guard.entryAllowed(Books.MANAS_ARORA)).thenReturn(true);
    when(guard.bookEquity(Books.MANAS_ARORA)).thenReturn(new BigDecimal("1000000"));
    when(guard.openRiskInr(Books.MANAS_ARORA)).thenReturn(new BigDecimal("10000")); // ~1% of equity
    when(guard.suggestedQty(any(), any(), any(), any(), any(), any())).thenReturn(new BigDecimal("10"));

    // A test doctrine carrying the REAL flag-derived pyramid bean (enabled=true so runDaily is live).
    ManasDoctrine doctrine =
        new ManasDoctrine(funnel, signals, pyramidPolicy, objectMapper, true, 520, 10, 1440);

    SwingBatchEngine.SwingRun run = engine.runDaily(doctrine);

    // 1) The add fired as a fresh ENTRY signal for the held symbol, tagged pyramidLot 2.
    assertThat(run.entries()).as("the pyramid add fires as a 2nd lot").isEqualTo(1);
    List<SignalRepository.SignalRow> mine =
        signals.activeEntries().stream().filter(r -> r.tradingsymbol().equals(sym)).toList();
    assertThat(mine).as("lot 1 + the pyramid add").hasSize(2);
    assertThat(mine)
        .anySatisfy(
            r -> {
              assertThat(r.manasAroraDetail()).isNotNull();
              assertThat(r.manasAroraDetail().path("pyramidLot").asInt()).isEqualTo(2);
            });

    // 2) The add AVERAGED into the ONE paper position (not a 2nd position): qty summed, price blended
    // up towards the higher lot-2 fill but still below it (§3.4 averaged single-lot).
    List<PositionRow> openForSym =
        positions.listOpen(Books.MANAS_ARORA).stream()
            .filter(p -> p.tradingsymbol().equals(sym))
            .toList();
    assertThat(openForSym).as("still ONE averaged position, never a second").hasSize(1);
    PositionRow averaged = openForSym.get(0);
    assertThat(averaged.qty()).as("10 (lot 1) + 10 (lot 2)").isEqualTo(20L);
    assertThat(averaged.avgEntryPrice())
        .as("blended above lot 1's avg")
        .isGreaterThan(avgAfterLot1);
    assertThat(averaged.avgEntryPrice())
        .as("still a blend, below the higher lot-2 fill")
        .isLessThan(new BigDecimal("152"));
  }

  /** Publishes a uniquely-named Manas breakout swing strategy (the real YAML) and returns its version. */
  private UUID publishManasBreakout(String uid) throws Exception {
    String yaml;
    try (InputStream in =
        getClass().getResourceAsStream("/manas-arora-strategies/manas-arora-breakout.yaml")) {
      assertThat(in).isNotNull();
      yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    String config = yaml.replace("id: manas-arora-breakout", "id: mpa-it-" + uid);
    UUID strategyId =
        (UUID)
            registry
                .create(
                    "MPA IT " + uid,
                    null,
                    List.of("manas-arora", "swing", "equity", "breakout"),
                    config)
                .get("id");
    registry.publish(strategyId, null, null);
    return strategyRepo.latestVersion(strategyId).orElseThrow().id();
  }

  // ---- crafted daily series (a base rising to a +vol breakout at 152) — the unit test's fixture ----

  private static List<EngineCandle> craft(long breakoutVolume) {
    double[] tail = {146, 148, 146, 148, 147};
    List<EngineCandle> bars = new ArrayList<>();
    for (int d = 0; d <= 18; d++) {
      bars.add(volBar(d, 100.0 + (149.0 - 100.0) * d / 18.0, 1_000L));
    }
    for (int i = 0; i < tail.length; i++) {
      bars.add(volBar(19 + i, tail[i], 1_000L));
    }
    bars.add(volBar(24, 152.0, breakoutVolume));
    return bars;
  }

  private static EngineCandle volBar(int day, double price, long volume) {
    BigDecimal p = BigDecimal.valueOf(price);
    return new EngineCandle(bar(day), p, p, p, p, volume, null);
  }

  private static OffsetDateTime bar(int day) {
    return OffsetDateTime.of(2026, 6, 1, 0, 0, 0, 0, IST).plusDays(day);
  }
}
