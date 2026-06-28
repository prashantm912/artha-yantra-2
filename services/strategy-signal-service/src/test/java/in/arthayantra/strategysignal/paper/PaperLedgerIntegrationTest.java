package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategyengine.fills.InstrumentClass;
import in.arthayantra.strategysignal.paper.InstrumentMetaClient.InstrumentMeta;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase 43 IT (mock profile): the paper ledger fills through the shared engine JAR (fill-audit
 * stamped, id {@code ltp_slippage/v1}), averages onto the §F.6 partial-unique open key, realizes
 * P&amp;L at close, and opens a position from a TAKEN signal. The instrument-meta lookup is stubbed
 * (no market-data running in this service's IT); the last tick is seeded into Redis directly.
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
@AutoConfigureMockMvc
class PaperLedgerIntegrationTest extends StrategySignalIntegrationTestBase {

  @TestConfiguration
  static class StubInstruments {
    @Bean
    @Primary
    InstrumentMetaClient stubInstrumentMetaClient() {
      return (exchange, tradingsymbol) ->
          tradingsymbol.startsWith("TESTOPT")
              ? new InstrumentMeta(InstrumentClass.OPTION, new BigDecimal("0.05"), 50)
              : new InstrumentMeta(InstrumentClass.EQUITY, new BigDecimal("0.05"), 1);
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private PaperPositionRepository positions;
  @Autowired private PaperService paper;
  @Autowired private PaperBracketEvaluator brackets;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private StringRedisTemplate redis;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void stopLossBracketAutoClosesOnBreach() throws Exception {
    String sym = "TESTOPT-" + UUID.randomUUID();
    // long with SL 90 / TP 120, filled ~100 — the bracket levels are stored + surfaced
    String body =
        mockMvc
            .perform(
                post("/api/v1/paper/orders")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json(
                            Map.of(
                                "exchange", "NFO", "tradingsymbol", sym, "side", "BUY", "qty", 50,
                                "price", "100.00", "stopLoss", "90.00", "takeProfit", "120.00"))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.stopLoss").value("90.0000"))
            .andExpect(jsonPath("$.takeProfit").value("120.0000"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    long id = objectMapper.readTree(body).get("id").asLong();

    // the LTP drops below the stop → the evaluator auto-closes with close_reason STOP_LOSS
    redis
        .opsForHash()
        .put(
            "ticks:last",
            "NFO:" + sym,
            json(Map.of("exchange", "NFO", "tradingsymbol", sym, "lastPrice", "88.00")));
    assertThat(brackets.evaluate()).isEqualTo(1);

    assertThat(positions.find(id)).get().extracting(p -> p.status()).isEqualTo("CLOSED");
    assertThat(positions.find(id)).get().extracting(p -> p.closeReason()).isEqualTo("STOP_LOSS");
    assertThat(positions.findOpen("NFO", sym, "BUY")).isEmpty();
  }

  @Test
  void openAveragesOntoTheOpenKeyAndStampsTheFillAudit() throws Exception {
    String sym = "TESTOPT-" + UUID.randomUUID();
    order(sym, "SELL", 50, "100.00")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.qty").value(50))
        .andExpect(jsonPath("$.avgEntryPrice").value("99.9500"));

    Map<String, Object> auditRow =
        jdbc.queryForMap(
            "SELECT fill_simulator, slippage_applied FROM paper_orders WHERE tradingsymbol=? ORDER BY id DESC LIMIT 1",
            sym);
    assertThat(auditRow.get("fill_simulator")).isEqualTo("ltp_slippage/v1");
    assertThat(auditRow.get("slippage_applied").toString()).isEqualTo("0.0500");

    // a second open on the same key averages into the SAME open position (one row, qty 100)
    order(sym, "SELL", 50, "100.00").andExpect(status().isCreated()).andExpect(jsonPath("$.qty").value(100));
    assertThat(positions.findOpen("NFO", sym, "SELL")).isPresent();
    assertThat(positions.listOpen().stream().filter(p -> p.tradingsymbol().equals(sym)).count())
        .isEqualTo(1);
  }

  @Test
  void closeRealizesPnlAndReleasesTheOpenKey() throws Exception {
    String sym = "TESTOPT-" + UUID.randomUUID();
    String body = order(sym, "SELL", 50, "120.00").andReturn().getResponse().getContentAsString();
    long id = objectMapper.readTree(body).get("id").asLong();

    mockMvc
        .perform(
            post("/api/v1/paper/positions/" + id + "/close")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("price", "100.00"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.realizedPnl").exists());

    // a short sold at ~120 and bought back at ~100 is profitable, and the position is CLOSED
    assertThat(positions.find(id)).get().extracting(p -> p.status()).isEqualTo("CLOSED");
    assertThat(positions.find(id)).get().extracting(p -> p.realizedPnl().signum()).isEqualTo(1);
    assertThat(positions.findOpen("NFO", sym, "SELL")).isEmpty(); // key released

    mockMvc
        .perform(get("/api/v1/paper/trades"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray());
  }

  @Test
  void partialUniqueOpenKeyRejectsASecondRawOpen() {
    String sym = "TESTOPT-" + UUID.randomUUID();
    positions.insertOpen("NFO", sym, "SELL", 50, new BigDecimal("99.95"), null, null);
    assertThatThrownBy(() -> positions.insertOpen("NFO", sym, "SELL", 25, new BigDecimal("99.90"), null, null))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void takenSignalWithQtyOpensAPaperPosition() throws Exception {
    UUID versionId = jdbc.queryForObject("SELECT id FROM strategy_versions LIMIT 1", UUID.class);
    String sym = "PAPEREQ" + UUID.randomUUID().toString().substring(0, 8);
    Long signalId =
        jdbc.queryForObject(
            """
            INSERT INTO signals
              (strategy_version_id, exchange, tradingsymbol, "interval", signal_type, side,
               entry_price, composite_score, score_breakdown)
            VALUES (?, 'NSE', ?, '1m', 'ENTRY', 'BUY', 100.0000, 0.7000, '{}'::jsonb)
            RETURNING id
            """,
            Long.class,
            versionId,
            sym);
    redis
        .opsForHash()
        .put(
            "ticks:last",
            "NSE:" + sym,
            objectMapper.writeValueAsString(
                Map.of("exchange", "NSE", "tradingsymbol", sym, "lastPrice", "105.00")));

    mockMvc
        .perform(
            post("/api/v1/signals/" + signalId + "/taken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("qty", 10))))
        .andExpect(status().isOk());

    assertThat(positions.findOpen("NSE", sym, "BUY")).isPresent();
  }

  @Test
  void aStampedOrderChargesThePositionToItsSubAccount() {
    // E10: openOrder threads OrderRequest.subaccountIdx through to insertOpen's subaccount_idx column.
    String sym = "TESTOPT-" + UUID.randomUUID();
    paper.openOrder(
        new PaperService.OrderRequest(null, "NFO", sym, "BUY", 50, new BigDecimal("100.00"), null, null, 2));
    Integer idx =
        jdbc.queryForObject(
            "SELECT subaccount_idx FROM paper_positions WHERE tradingsymbol=? AND status='OPEN'",
            Integer.class,
            sym);
    assertThat(idx).isEqualTo(2);
  }

  @Test
  void resetRequiresConfirmThenWipesTheLedger() throws Exception {
    String sym = "TESTOPT-" + UUID.randomUUID();
    paper.openOrder(new PaperService.OrderRequest(null, "NFO", sym, "SELL", 10, new BigDecimal("100.00"), null, null));

    mockMvc
        .perform(
            post("/api/v1/paper/reset")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("confirm", false))))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            post("/api/v1/paper/reset")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("confirm", true))))
        .andExpect(status().isNoContent());

    assertThat(positions.listOpen()).isEmpty();
  }

  private org.springframework.test.web.servlet.ResultActions order(
      String sym, String side, int qty, String price) throws Exception {
    return mockMvc.perform(
        post("/api/v1/paper/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                json(
                    Map.of(
                        "exchange", "NFO",
                        "tradingsymbol", sym,
                        "side", side,
                        "qty", qty,
                        "price", price))));
  }

  private String json(Map<String, ?> body) throws Exception {
    return objectMapper.writeValueAsString(body);
  }
}
