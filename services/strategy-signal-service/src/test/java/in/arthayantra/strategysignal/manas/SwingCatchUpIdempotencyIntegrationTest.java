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
import in.arthayantra.strategysignal.swing.SwingBatchEngine;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The catch-up double-fill linchpin (2026-07-17 review, Critical 2), proven end-to-end against a REAL
 * Spring context + Timescale + the auto-paper chain. The reviewer's key finding: {@code
 * PaperService.upsertPosition} AVERAGES a second open into the first (never rejects), so
 * {@code uq_paper_positions_open} guards the ROW, not the qty — a second emission for one symbol
 * doubles the fill. The catch-up's atomic claim + family mutex prevent CONCURRENT re-entry; this proves
 * the other half: a SEQUENTIAL re-run (the catch-up retrying a session, or a crash-recovery reclaim)
 * is idempotent at the engine level — the first run's ENTRY makes the symbol HELD, so the second run's
 * entry pass skips it and opens NO second (averaged) lot. Pyramiding is OFF (default), the single-lot
 * held-skip path.
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
class SwingCatchUpIdempotencyIntegrationTest extends StrategySignalIntegrationTestBase {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private static final LocalDate SESSION = LocalDate.of(2026, 6, 25); // the crafted series' last bar

  @TestConfiguration
  static class Stubs {
    @Bean
    @Primary
    InstrumentMetaClient stubMeta() {
      return (exchange, tradingsymbol) ->
          new InstrumentMeta(InstrumentClass.EQUITY, new BigDecimal("0.05"), 1);
    }

    @Bean
    @Primary
    MarketDataCandlesClient stubCandles() {
      return mock(MarketDataCandlesClient.class);
    }

    @Bean
    @Primary
    EmissionGuard stubGuard() {
      return mock(EmissionGuard.class);
    }
  }

  @Autowired private SwingBatchEngine engine;
  @Autowired private ManasPyramidPolicy pyramidPolicy; // real bean, pyramid.enabled defaults false
  @Autowired private SignalRepository signals;
  @Autowired private RegistryService registry;
  @Autowired private StrategyRepository strategyRepo;
  @Autowired private PaperPositionRepository positions;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private MarketDataCandlesClient candles;
  @Autowired private EmissionGuard guard;

  @Test
  void aSecondCatchUpRunForTheSameSessionOpensNoSecondLot() throws Exception {
    jdbc.update("DELETE FROM risk_settings WHERE book = ? AND key = 'auto_paper_trade'", Books.MANAS_ARORA);
    jdbc.update(
        "INSERT INTO risk_settings (book, key, value) VALUES (?, 'auto_paper_trade', ?::jsonb)",
        Books.MANAS_ARORA, "{\"enabled\": true}");

    String uid = UUID.randomUUID().toString().substring(0, 8);
    String sym = "CUIDEM" + uid; // unique — the shared DB has no per-method cleanup
    publishManasBreakout(uid);

    // A fresh (non-held) breakout candidate that fires an ENTRY on the pinned session's bar.
    List<EngineCandle> series = craft(3_000L);
    when(candles.fetch(eq("NSE"), eq(sym), eq("1d"), any(), any())).thenReturn(series);
    ManasFunnelClient funnel = mock(ManasFunnelClient.class);
    when(funnel.buyableAndOnDeck())
        .thenReturn(
            List.of(
                new ManasFunnelClient.Candidate(
                    sym, new BigDecimal("152"), new BigDecimal("150"), "breakout", null,
                    new BigDecimal("150"), null, false)));
    when(guard.entryAllowed(Books.MANAS_ARORA)).thenReturn(true);
    when(guard.suggestedQty(any(), any(), any(), any(), any(), any())).thenReturn(new BigDecimal("10"));

    ManasDoctrine doctrine =
        new ManasDoctrine(funnel, signals, pyramidPolicy, objectMapper, true, 520, 10, 1440);

    // Run 1 (the catch-up, pinned): the entry fires and auto-papers one lot of 10.
    SwingBatchEngine.SwingRun first = engine.runDaily(doctrine, SESSION, true);
    assertThat(first.entries()).as("the fresh entry fires").isEqualTo(1);
    PositionRow afterFirst =
        positions.findOpen(Books.MANAS_ARORA, "NSE", sym, "BUY").orElseThrow();
    assertThat(afterFirst.qty()).isEqualTo(10L);

    // Run 2 for the SAME session (a retry / crash-recovery reclaim). The symbol is now HELD, so the
    // entry pass skips it — NO second emission, NO averaged double fill.
    SwingBatchEngine.SwingRun second = engine.runDaily(doctrine, SESSION, true);
    assertThat(second.entries()).as("the held symbol is skipped on the re-run").isZero();

    List<PositionRow> openForSym =
        positions.listOpen(Books.MANAS_ARORA).stream()
            .filter(p -> p.tradingsymbol().equals(sym))
            .toList();
    assertThat(openForSym).as("still exactly ONE position").hasSize(1);
    assertThat(openForSym.get(0).qty())
        .as("qty is the single fill (10), NOT doubled to 20")
        .isEqualTo(10L);
    // And exactly one ENTRY signal exists for the symbol (the re-run emitted none).
    long entrySignals =
        signals.activeEntries().stream().filter(r -> r.tradingsymbol().equals(sym)).count();
    assertThat(entrySignals).isEqualTo(1);
  }

  /** Publishes a uniquely-named Manas breakout swing strategy (the real YAML) and returns its version. */
  private UUID publishManasBreakout(String uid) throws Exception {
    String yaml;
    try (InputStream in =
        getClass().getResourceAsStream("/manas-arora-strategies/manas-arora-breakout.yaml")) {
      assertThat(in).isNotNull();
      yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    String config = yaml.replace("id: manas-arora-breakout", "id: cuidem-it-" + uid);
    UUID strategyId =
        (UUID)
            registry
                .create(
                    "CUIDEM IT " + uid, null,
                    List.of("manas-arora", "swing", "equity", "breakout"), config)
                .get("id");
    registry.publish(strategyId, null, null);
    return strategyRepo.latestVersion(strategyId).orElseThrow().id();
  }

  // ---- crafted daily series (a base rising to a +vol breakout at 152) — the pyramid test's fixture ----

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
