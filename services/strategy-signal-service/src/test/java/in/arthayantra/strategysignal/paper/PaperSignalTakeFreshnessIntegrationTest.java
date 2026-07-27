package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategyengine.fills.InstrumentClass;
import in.arthayantra.strategysignal.paper.InstrumentMetaClient.InstrumentMeta;
import in.arthayantra.strategysignal.registry.RegistryService;
import in.arthayantra.strategysignal.registry.StrategyRepository;
import in.arthayantra.strategysignal.signals.SignalRepository;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HOLD (task_94f40cf6): the MANUAL take path (POST /api/v1/paper/orders with a signalId) must reject a
 * signal taken too long after emission with 422 SIGNAL_STALE (age + limit in the body) — the setup's
 * thesis has gone stale even though the fill prices off a fresh tick. Book-aware: SWING books
 * (minervini / manas-arora) are EXEMPT (their EOD signals are meant to be taken the NEXT session);
 * scalper / other books are gated at {@code artha.paper.signal-take-max-age-minutes}. This is SEPARATE
 * from the #694 TICK-freshness gate (DATA_STALE, which guards the FILL price).
 *
 * <p>The gated cases use the {@code other} book (non-swing) so the fresh open is a plain equity fill;
 * the exempt case uses {@code minervini}. Governors on both are disabled per method so the fresh/exempt
 * opens are not vetoed for an unrelated reason; the stale case 422s BEFORE the governor + fill.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.signals.engine-enabled=false",
      "artha.paper.signal-take-max-age-minutes=60"
    })
@AutoConfigureMockMvc
class PaperSignalTakeFreshnessIntegrationTest extends StrategySignalIntegrationTestBase {

  private static final String GATED_BOOK = "other";
  private static final String SWING_BOOK = "minervini";

  private static final String CONFIG =
      """
      schema: strategy-schema/v1
      id: freshness-it
      name: "Freshness IT"
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
  static class StubInstruments {
    @Bean
    @Primary
    InstrumentMetaClient stubInstrumentMetaClient() {
      // every test symbol resolves as a plain equity (lot 1) so an explicit-price fill always prices.
      return (exchange, tradingsymbol) ->
          new InstrumentMeta(InstrumentClass.EQUITY, new BigDecimal("0.05"), 1);
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private RegistryService registry;
  @Autowired private StrategyRepository strategyRepo;
  @Autowired private SignalRepository signals;
  @Autowired private RiskService risk;
  @Autowired private JdbcTemplate jdbc;

  private UUID versionId;

  @BeforeEach
  void setUp() {
    String slug = "freshness-it-" + UUID.randomUUID().toString().substring(0, 8);
    UUID strategyId =
        (UUID)
            registry
                .create(
                    "Freshness IT " + slug,
                    null,
                    List.of("it"),
                    CONFIG.replace("id: freshness-it", "id: " + slug))
                .get("id");
    registry.publish(strategyId, null, null);
    versionId = strategyRepo.latestVersion(strategyId).orElseThrow().id();
    // Disable every entry-veto rail on the books under test so the fresh/exempt open is never vetoed
    // for an unrelated governor reason (the stale case 422s BEFORE the governor, so it needs none).
    for (String book : new String[] {GATED_BOOK, SWING_BOOK}) {
      for (String rail :
          new String[] {
            RiskService.KILL_SWITCH, RiskService.MAX_OPEN, RiskService.DAILY_LOSS,
            RiskService.DAILY_PROFIT_TARGET, RiskService.MAX_DEPLOYMENT_PCT
          }) {
        risk.update(book, rail, "{\"enabled\":false}");
      }
    }
  }

  @Test
  void staleTakeOnAGatedBookIs422SignalStale() throws Exception {
    String sym = "STALE-" + UUID.randomUUID();
    long signalId = insertSignal(sym, OffsetDateTime.now(ZoneOffset.UTC).minusHours(2)); // 120m > 60m

    mockMvc
        .perform(
            post("/api/v1/paper/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(order(signalId, sym, GATED_BOOK)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("SIGNAL_STALE"))
        .andExpect(jsonPath("$.details.book").value(GATED_BOOK))
        .andExpect(jsonPath("$.details.maxAgeMinutes").value(60));

    assertThat(openCount(GATED_BOOK, sym)).isZero();
  }

  @Test
  void freshTakeOnAGatedBookOpens() throws Exception {
    String sym = "FRESH-" + UUID.randomUUID();
    long signalId = insertSignal(sym, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5)); // 5m < 60m

    mockMvc
        .perform(
            post("/api/v1/paper/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(order(signalId, sym, GATED_BOOK)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("OPEN"));

    assertThat(openCount(GATED_BOOK, sym)).isEqualTo(1);
  }

  @Test
  void staleTakeOnASwingBookIsExemptAndOpens() throws Exception {
    String sym = "SWING-" + UUID.randomUUID();
    // 20h old — a swing signal fired at yesterday's close, taken at today's open. Normal, not stale.
    long signalId = insertSignal(sym, OffsetDateTime.now(ZoneOffset.UTC).minusHours(20));

    mockMvc
        .perform(
            post("/api/v1/paper/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(order(signalId, sym, SWING_BOOK)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("OPEN"));

    assertThat(openCount(SWING_BOOK, sym)).isEqualTo(1);
  }

  private long insertSignal(String sym, OffsetDateTime generatedAt) {
    return signals.insert(
        versionId, "NSE", sym, "1m", "ENTRY", "BUY",
        new BigDecimal("100.00"), new BigDecimal("50.00"), new BigDecimal("150.00"),
        new BigDecimal("0.80"), "{}", generatedAt, generatedAt.plusHours(48), null);
  }

  private String order(long signalId, String sym, String book) throws Exception {
    return objectMapper.writeValueAsString(
        Map.of(
            "signalId", signalId,
            "exchange", "NSE",
            "tradingsymbol", sym,
            "side", "BUY",
            "qty", 1,
            "price", "100.00",
            "book", book));
  }

  private int openCount(String book, String sym) {
    Integer c =
        jdbc.queryForObject(
            "SELECT count(*) FROM paper_positions WHERE book=? AND tradingsymbol=? AND status='OPEN'",
            Integer.class,
            book,
            sym);
    return c == null ? 0 : c;
  }
}
