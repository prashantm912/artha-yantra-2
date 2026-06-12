package in.arthayantra.marketdata.screener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.marketdata.candles.Candle;
import in.arthayantra.marketdata.candles.CandleRepository;
import in.arthayantra.marketdata.instruments.InstrumentSyncService;
import in.arthayantra.marketdata.kite.HistoricalCandleGateway;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase-17 IT: watchlist CRUD lifecycle (cascade, 409 name, idempotent dup item, unknown
 * instrument never passes toward Kite), screener presets over SEEDED caggs with ZERO gateway-port
 * invocations (incl. oi_buildup quadrants, rs_rank percentile fixture and 1w windows from
 * candles_1w), and the small market surface.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
@AutoConfigureMockMvc
class WatchlistScreenerIntegrationTest extends MarketDataIntegrationTestBase {

  private static final AtomicInteger GATEWAY_CALLS = new AtomicInteger();

  @TestConfiguration
  static class CountingGatewayConfig {
    @Bean
    @Primary
    HistoricalCandleGateway countingHistoricalGateway() {
      return (InstrumentKey key, String interval, Instant from, Instant to) -> {
        GATEWAY_CALLS.incrementAndGet();
        return List.of();
      };
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private InstrumentSyncService syncService;
  @Autowired private CandleRepository candles;
  @Autowired private ScreenerService screener;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper objectMapper;

  private static final List<String> SEEDED =
      List.of(
          "RELIANCE", "TCS", "HDFCBANK", "NIFTY 50",
          "NIFTY26JUNFUT", "NIFTY26JULFUT", "NIFTY26AUGFUT", "BANKNIFTY26JUNFUT");
  private static final String[] DAYS = {
    "2026-06-01", "2026-06-02", "2026-06-03", "2026-06-04", "2026-06-05", "2026-06-08"
  };

  private void seedDailySeries(String exchange, String symbol, double first, double last) {
    double step = (last - first) / (DAYS.length - 1);
    for (int i = 0; i < DAYS.length; i++) {
      BigDecimal close = BigDecimal.valueOf(first + step * i).setScale(2, java.math.RoundingMode.HALF_UP);
      candles.upsert(
          new Candle(
              exchange, symbol, "1m",
              OffsetDateTime.parse(DAYS[i] + "T09:15:00+05:30"),
              close, close, close, close, 10, null, "MOCK"));
    }
  }

  private void seedFut(String symbol, double close1, long oi1, double close2, long oi2) {
    candles.upsert(
        new Candle(
            "NFO", symbol, "1m", OffsetDateTime.parse("2026-06-04T09:15:00+05:30"),
            BigDecimal.valueOf(close1), BigDecimal.valueOf(close1),
            BigDecimal.valueOf(close1), BigDecimal.valueOf(close1), 10, oi1, "MOCK"));
    candles.upsert(
        new Candle(
            "NFO", symbol, "1m", OffsetDateTime.parse("2026-06-05T09:15:00+05:30"),
            BigDecimal.valueOf(close2), BigDecimal.valueOf(close2),
            BigDecimal.valueOf(close2), BigDecimal.valueOf(close2), 10, oi2, "MOCK"));
  }

  @BeforeEach
  void seed() {
    syncService.runSync();
    jdbc.update("DELETE FROM watchlists");
    for (String symbol : SEEDED) {
      candles.purgeSymbol(symbol.contains("FUT") ? "NFO" : "NSE", symbol);
    }
    seedDailySeries("NSE", "RELIANCE", 100, 110); // +10%
    seedDailySeries("NSE", "TCS", 100, 105); // +5%
    seedDailySeries("NSE", "HDFCBANK", 100, 98); // −2%
    seedDailySeries("NSE", "NIFTY 50", 100, 104); // benchmark +4%
    seedFut("NIFTY26JUNFUT", 100, 1_000, 105, 1_500); // ΔP+ ΔOI+ → LONG_BUILDUP
    seedFut("NIFTY26JULFUT", 100, 1_000, 95, 1_500); // ΔP− ΔOI+ → SHORT_BUILDUP
    seedFut("NIFTY26AUGFUT", 100, 1_000, 95, 600); // ΔP− ΔOI− → LONG_UNWINDING
    seedFut("BANKNIFTY26JUNFUT", 100, 1_000, 105, 600); // ΔP+ ΔOI− → SHORT_COVERING
    jdbc.execute("CALL public.refresh_continuous_aggregate('candles_1d', NULL, NULL)");
    jdbc.execute("CALL public.refresh_continuous_aggregate('candles_1w', NULL, NULL)");
  }

  @Test
  void watchlistCrudLifecycle() throws Exception {
    String created =
        mockMvc
            .perform(
                post("/api/v1/watchlists")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"core\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String id = objectMapper.readTree(created).path("id").asText();

    // duplicate name → 409 envelope
    mockMvc
        .perform(
            post("/api/v1/watchlists")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"core\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONFLICT_WATCHLIST_NAME"));

    // unknown instrument never passes toward Kite
    mockMvc
        .perform(
            post("/api/v1/watchlists/" + id + "/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"exchange\":\"NSE\",\"tradingsymbol\":\"NOPE\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND_INSTRUMENT"));

    // add twice — idempotent
    for (int i = 0; i < 2; i++) {
      mockMvc
          .perform(
              post("/api/v1/watchlists/" + id + "/items")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"exchange\":\"NSE\",\"tradingsymbol\":\"RELIANCE\"}"))
          .andExpect(status().isOk());
    }
    mockMvc
        .perform(get("/api/v1/watchlists/" + id))
        .andExpect(jsonPath("$.items.length()").value(1));

    // cascade delete
    mockMvc.perform(delete("/api/v1/watchlists/" + id)).andExpect(status().isNoContent());
    mockMvc
        .perform(get("/api/v1/watchlists/" + id))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND_WATCHLIST"));
    Integer orphanItems =
        jdbc.queryForObject(
            "SELECT count(*) FROM watchlist_items WHERE watchlist_id = ?::uuid", Integer.class, id);
    assertThat(orphanItems).isZero();
  }

  @Test
  void momentumRanksSeededReturnsWithoutTouchingAnyKitePort() {
    int before = GATEWAY_CALLS.get();

    List<ScreenerService.Row> rows = screener.run("momentum", "1d", 5, 10);

    List<String> seededOrder =
        rows.stream()
            .map(ScreenerService.Row::tradingsymbol)
            .filter(s -> List.of("RELIANCE", "TCS", "HDFCBANK").contains(s))
            .toList();
    assertThat(seededOrder).containsExactly("RELIANCE", "TCS", "HDFCBANK");
    ScreenerService.Row reliance =
        rows.stream().filter(r -> r.tradingsymbol().equals("RELIANCE")).findFirst().orElseThrow();
    assertThat(reliance.value()).isEqualByComparingTo("0.100000");
    assertThat(GATEWAY_CALLS.get() - before)
        .as("the screener NEVER touches a Kite port")
        .isZero();
  }

  @Test
  void oiBuildupClassifiesAllFourQuadrants() {
    int before = GATEWAY_CALLS.get();

    List<ScreenerService.Row> rows = screener.run("oi_buildup", "1d", null, 50);

    assertThat(rows)
        .extracting(ScreenerService.Row::tradingsymbol, ScreenerService.Row::label)
        .contains(
            org.assertj.core.groups.Tuple.tuple("NIFTY26JUNFUT", "LONG_BUILDUP"),
            org.assertj.core.groups.Tuple.tuple("NIFTY26JULFUT", "SHORT_BUILDUP"),
            org.assertj.core.groups.Tuple.tuple("NIFTY26AUGFUT", "LONG_UNWINDING"),
            org.assertj.core.groups.Tuple.tuple("BANKNIFTY26JUNFUT", "SHORT_COVERING"));
    assertThat(GATEWAY_CALLS.get() - before).isZero();
  }

  @Test
  void rsRankPercentileMatchesTheHandComputedFixture() {
    List<ScreenerService.Row> rows = screener.run("rs_rank", "1d", 5, 10);

    var bySymbol = new java.util.HashMap<String, BigDecimal>();
    rows.forEach(r -> bySymbol.put(r.tradingsymbol(), r.value()));
    // rs vs the +4% benchmark: RELIANCE +6% → top percentile; HDFCBANK −6% → bottom
    assertThat(bySymbol.get("RELIANCE")).isEqualByComparingTo("1.0000");
    assertThat(bySymbol.get("HDFCBANK")).isEqualByComparingTo("0.0000");
    assertThat(bySymbol.get("TCS")).isEqualByComparingTo("0.5000");
  }

  @Test
  void weeklyWindowAnswersFromTheWeeklyAggregate() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/market/screener")
                .param("preset", "momentum")
                .param("window", "1w")
                .param("lookback", "1"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath(
                "$.items[?(@.tradingsymbol == 'RELIANCE')]", org.hamcrest.Matchers.hasSize(1)));
  }

  @Test
  void unknownPresetIs422() throws Exception {
    mockMvc
        .perform(get("/api/v1/market/screener").param("preset", "moonshot"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  @Test
  void marketSurfaceServesStatusAndLatestTicks() throws Exception {
    mockMvc
        .perform(get("/api/v1/market/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sessionOpen").value("09:15"))
        .andExpect(jsonPath("$.tradingDay").isBoolean());
    mockMvc
        .perform(get("/api/v1/market/ticks/latest"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray());
  }
}
