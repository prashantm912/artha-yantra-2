package in.arthayantra.marketdata.screener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.marketdata.testsupport.FixtureShape;
import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The DEEP_SWING internal endpoint over a REAL (small, seeded) daily panel (audit P0-3): {@code POST
 * /api/v1/market/screener/deep-swing/run} runs the actual deep-sim synchronously and returns the
 * typed variant report + per-trade rows. Pins: the wire shape round-trips over HTTP (LocalDate JSON
 * binding — the exact call the backtest-service worker makes), the trades list is CONSISTENT with the
 * report's own totalTrades for that variant (the job numbers ARE the /compare numbers), validation
 * (unknown family / unknown variant / missing from → 422), and the shared single-permit heap gate
 * (409 while held — the job route's actual concurrency bound). Uses a SMALL fixture panel with a
 * unique symbol prefix (singleton DB, no per-method cleanup), never the full universe.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
@AutoConfigureMockMvc
class DeepSwingEndpointIntegrationTest extends MarketDataIntegrationTestBase {

  private static final String PREFIX = "DSEP";
  private static final int SYMS = 25; // small panel — the sim must stay IT-fast (never full-universe)
  private static final int DAYS = 600; // > MIN_SERIES(260) + warmup so every symbol is scanned
  private static final LocalDate END = LocalDate.of(2025, 12, 31);
  private static final LocalDate FROM = LocalDate.of(2025, 6, 1);

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper mapper;
  @Autowired private SwingBacktestGate gate;

  private static List<String> symbols() {
    List<String> out = new ArrayList<>(SYMS);
    for (int i = 0; i < SYMS; i++) {
      out.add(PREFIX + String.format("%02d", i));
    }
    return out;
  }

  private void purge() {
    for (String s : symbols()) {
      jdbc.update("DELETE FROM candles WHERE tradingsymbol=?", s);
      jdbc.update("DELETE FROM instruments WHERE tradingsymbol=?", s);
    }
  }

  @BeforeEach
  void seed() {
    purge();
    List<String> syms = symbols();
    for (int k = 0; k < syms.size(); k++) {
      String s = syms.get(k);
      jdbc.update(
          "INSERT INTO instruments(exchange,tradingsymbol,name,instrument_type,is_active)"
              + " VALUES('NSE',?,?, 'EQ', true) ON CONFLICT DO NOTHING",
          s, s);
      jdbc.batchUpdate(
          "INSERT INTO candles(exchange,tradingsymbol,interval,bucket,open,high,low,close,volume,source)"
              + " VALUES('NSE',?, '1d', ?,?,?,?,?,?, 'BACKFILL') ON CONFLICT DO NOTHING",
          FixtureShape.variedUptrend(s, k, DAYS, END));
    }
  }

  @AfterEach
  void tearDown() {
    purge();
  }

  @Test
  void runsTheRealSimAndReturnsAVariantConsistentTypedResult() throws Exception {
    // variant 'technical' relaxes the RS gate, so the small panel's thin cross-section cannot
    // silently zero the trade flow; the manas family exercises the full compute path.
    String body =
        mockMvc
            .perform(
                post("/api/v1/market/screener/deep-swing/run")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"family\":\"manas\",\"from\":\"" + FROM + "\",\"variant\":\"technical\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.result.family").value("manas"))
            .andExpect(jsonPath("$.result.variant").value("technical"))
            .andExpect(jsonPath("$.result.fromDate").value(FROM.toString()))
            .andExpect(jsonPath("$.result.report.status").value("completed"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    JsonNode root = mapper.readTree(body);
    JsonNode result = root.path("result");
    assertThat(result.path("symbolsScanned").asInt())
        .as("all seeded symbols cleared MIN_SERIES and were scanned")
        .isGreaterThanOrEqualTo(SYMS);
    // the trades list IS the report's variant trade set — the job numbers are the /compare numbers.
    assertThat(result.path("trades").size())
        .isEqualTo(result.path("report").path("totalTrades").asInt());
    // every trade row carries the lineage fields the worker maps into backtest_trades.
    for (JsonNode t : result.path("trades")) {
      assertThat(t.path("symbol").asText()).isNotBlank();
      assertThat(t.path("entryDate").asText()).isNotBlank();
      assertThat(t.path("exitDate").asText()).isNotBlank();
      assertThat(t.path("exitReason").asText()).isNotBlank();
    }
    // engine identity fields exist on the wire (null here — the test jar has no git.properties).
    assertThat(root.has("engineSha")).isTrue();
    assertThat(root.has("engineImage")).isTrue();
  }

  @Test
  void unknownFamilyVariantAndMissingFromAreRefused() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/market/screener/deep-swing/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"family\":\"weinstein\",\"from\":\"2025-06-01\"}"))
        .andExpect(status().is(422))
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    mockMvc
        .perform(
            post("/api/v1/market/screener/deep-swing/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"family\":\"manas\",\"from\":\"2025-06-01\",\"variant\":\"nope\"}"))
        .andExpect(status().is(422));
    mockMvc
        .perform(
            post("/api/v1/market/screener/deep-swing/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"family\":\"manas\"}"))
        .andExpect(status().is(422));
  }

  @Test
  void busyGateRefusesWith409AndRecovers() throws Exception {
    assertThat(gate.tryAcquire()).isTrue(); // simulate an owner-triggered sim holding the permit
    try {
      mockMvc
          .perform(
              post("/api/v1/market/screener/deep-swing/run")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"family\":\"minervini\",\"from\":\"2025-06-01\"}"))
          .andExpect(status().is(409));
    } finally {
      gate.release();
    }
    // the refusal must not strand the permit — a follow-up run acquires it and completes.
    mockMvc
        .perform(
            post("/api/v1/market/screener/deep-swing/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"family\":\"minervini\",\"from\":\"" + FROM + "\",\"variant\":\"technical\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.family").value("minervini"))
        .andExpect(jsonPath("$.result.variant").value("technical"));
  }

  @Test
  void nextOpenVariantsFlowThroughBothDeepSwingFamilies() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/market/screener/deep-swing/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"family\":\"manas\",\"from\":\""
                        + FROM
                        + "\",\"variant\":\"rs-turnover-nopyramid-nextopen\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.family").value("manas"))
        .andExpect(jsonPath("$.result.variant").value("rs-turnover-nopyramid-nextopen"));

    mockMvc
        .perform(
            post("/api/v1/market/screener/deep-swing/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"family\":\"minervini\",\"from\":\""
                        + FROM
                        + "\",\"variant\":\"rs-turnover-nextopen\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.family").value("minervini"))
        .andExpect(jsonPath("$.result.variant").value("rs-turnover-nextopen"));
  }
}
