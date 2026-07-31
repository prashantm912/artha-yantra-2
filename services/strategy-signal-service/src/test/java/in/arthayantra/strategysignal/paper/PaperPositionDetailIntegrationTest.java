package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase-2 slice B IT (mock profile): the position DETAIL pane provenance ({@code GET
 * /paper/positions/{id}}) and the manual bracket EDIT ({@code PATCH .../brackets}). The bracket-edit
 * path is HOLD-tier — it changes what the live 15s evaluator acts on — so the load-bearing trace is
 * exercised end-to-end: PATCH a new level → assert the {@code paper_admin_audit} row → drop a tick that
 * breaches the NEW level (but NOT the old) → the {@link PaperBracketEvaluator} closes it, proving the
 * evaluator reads the fresh level. Instrument-meta is stubbed; the last tick is seeded into Redis.
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
@AutoConfigureMockMvc
class PaperPositionDetailIntegrationTest extends StrategySignalIntegrationTestBase {

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
  @Autowired private PaperService paper;
  @Autowired private PaperPositionRepository positions;
  @Autowired private PaperBracketEvaluator brackets;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private StringRedisTemplate redis;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void detailExposesProvenanceOrderLegsAndRecomputedFees() throws Exception {
    String sym = "TESTOPT-" + UUID.randomUUID();
    UUID versionId = jdbc.queryForObject("SELECT id FROM strategy_versions LIMIT 1", UUID.class);
    Long signalId =
        jdbc.queryForObject(
            """
            INSERT INTO signals
              (strategy_version_id, exchange, tradingsymbol, "interval", signal_type, side,
               entry_price, composite_score, score_breakdown, scalper_detail)
            VALUES (?, 'NFO', ?, '3m', 'ENTRY', 'BUY', 100.0000, 0.7100, '{}'::jsonb,
                    '{"optionType":"CE","strike":100}'::jsonb)
            RETURNING id
            """,
            Long.class,
            versionId,
            sym);

    // A signal-linked open (explicit price, so no tick needed) with a bracket → sets opening_signal_id
    // + stop/target on the position; the fill leg lands in paper_orders with the fill-audit trail.
    long id =
        paper
            .openOrder(
                new PaperService.OrderRequest(
                    signalId, "NFO", sym, "BUY", 50, new BigDecimal("100.00"),
                    new BigDecimal("80.00"), new BigDecimal("120.00"), null, "other", null))
            .id();

    mockMvc
        .perform(get("/api/v1/paper/positions/" + id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.book").value("other"))
        .andExpect(jsonPath("$.side").value("BUY"))
        .andExpect(jsonPath("$.status").value("OPEN"))
        .andExpect(jsonPath("$.stopLoss").value("80.0000"))
        .andExpect(jsonPath("$.takeProfit").value("120.0000"))
        .andExpect(jsonPath("$.openingSignalId").value(signalId))
        .andExpect(jsonPath("$.openingSignal.signalId").value(signalId))
        .andExpect(jsonPath("$.openingSignal.scalperDetail.optionType").value("CE"))
        // one entry leg, fill-audit stamped + a recomputed statutory fee breakdown (1 option lot → ₹20)
        .andExpect(jsonPath("$.orders").isArray())
        .andExpect(jsonPath("$.orders[0].side").value("BUY"))
        .andExpect(jsonPath("$.orders[0].fillSimulator").value("ltp_slippage/v1"))
        .andExpect(jsonPath("$.orders[0].fees.brokerage").exists())
        .andExpect(jsonPath("$.orders[0].fees.total").exists());
  }

  @Test
  void patchBracketAuditsAndTheEvaluatorActsOnTheNewLevel() throws Exception {
    String sym = "TESTOPT-" + UUID.randomUUID();
    // long filled ~100, initial stop a loose 80; the LTP sits at 100 so a tighten to 95 is valid.
    long id =
        paper
            .openOrder(
                new PaperService.OrderRequest(
                    null, "NFO", sym, "BUY", 50, new BigDecimal("100.00"),
                    new BigDecimal("80.00"), new BigDecimal("120.00"), null, "manual", null))
            .id();
    seedTick(sym, "100.00");

    // PATCH the stop UP to 95 (below LTP 100 → not immediately hit → accepted).
    mockMvc
        .perform(
            patch("/api/v1/paper/positions/" + id + "/brackets")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("stopLoss", "95.00"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stopLoss").value("95.0000"))
        .andExpect(jsonPath("$.takeProfit").value("120.0000"));

    // The override is audited: previous 80 → new 95, keyed by the position id.
    Map<String, Object> audit =
        jdbc.queryForMap(
            "SELECT action, detail FROM paper_admin_audit"
                + " WHERE action='BRACKET_EDIT' AND (detail->>'positionId')::bigint=? ORDER BY id DESC LIMIT 1",
            id);
    assertThat(audit.get("action")).isEqualTo("BRACKET_EDIT");
    // Money-as-string at the DB scale — paper_positions.stop_loss is NUMERIC(18,4).
    var detail = objectMapper.readTree(audit.get("detail").toString());
    assertThat(detail.get("previousStopLoss").asText()).isEqualTo("80.0000");
    assertThat(detail.get("newStopLoss").asText()).isEqualTo("95.0000");

    // Drop the LTP to 94: BELOW the NEW stop 95 (breach) but ABOVE the old stop 80 (would NOT have
    // fired) — so a close here proves the evaluator read the freshly-PATCHed level.
    seedTick(sym, "94.00");
    brackets.evaluate();

    var closed = positions.find(id).orElseThrow();
    assertThat(closed.status()).isEqualTo("CLOSED");
    assertThat(closed.closeReason()).isEqualTo("STOP_LOSS");
  }

  @Test
  void manualCloseIsAuditedWithTheBookPriceAndRealizedPnl() throws Exception {
    // Noticed 2026-07-20 while hand-closing a position the outaged 07-17 swing batch never exited: the
    // manual close wrote NO paper_admin_audit row, so an owner override of the strategy's own exit rule
    // was indistinguishable from a routine engine/bracket close. A discretionary close is exactly the
    // kind of action the trail exists for.
    String sym = "TESTOPT-" + UUID.randomUUID();
    long id =
        paper
            .openOrder(
                new PaperService.OrderRequest(
                    null, "NFO", sym, "BUY", 50, new BigDecimal("100.00"), null, null, null,
                    "manual", null))
            .id();

    mockMvc
        .perform(
            post("/api/v1/paper/positions/" + id + "/close")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("price", "108.00"))))
        .andExpect(status().isOk());

    Map<String, Object> audit =
        jdbc.queryForMap(
            "SELECT action, book, detail FROM paper_admin_audit"
                + " WHERE action='MANUAL_CLOSE' AND (detail->>'positionId')::bigint=?"
                + " ORDER BY id DESC LIMIT 1",
            id);
    assertThat(audit.get("action")).isEqualTo("MANUAL_CLOSE");
    assertThat(audit.get("book")).as("keyed by the closed position's book").isEqualTo("manual");
    var detail = objectMapper.readTree(audit.get("detail").toString());
    assertThat(detail.get("requestedPrice").asText()).isEqualTo("108.00");
    assertThat(detail.get("realizedPnl").asText())
        .as("the P&L the settle actually booked, not a recomputation")
        .isEqualTo(positions.find(id).orElseThrow().realizedPnl().toPlainString());
  }

  @Test
  void patchRejectsALevelThatWouldFireImmediately() throws Exception {
    String sym = "TESTOPT-" + UUID.randomUUID();
    long id =
        paper
            .openOrder(
                new PaperService.OrderRequest(
                    null, "NFO", sym, "BUY", 50, new BigDecimal("100.00"),
                    new BigDecimal("80.00"), null, null, "manual", null))
            .id();
    seedTick(sym, "100.00");

    // A long stop at 105 sits ABOVE the LTP 100 → it would trigger on the very next poll → 422.
    mockMvc
        .perform(
            patch("/api/v1/paper/positions/" + id + "/brackets")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("stopLoss", "105.00"))))
        .andExpect(status().isUnprocessableEntity());
    // The stop is unchanged (rejected before persistence).
    assertThat(positions.find(id)).get().extracting(PaperPositionRepository.PositionRow::stopLoss)
        .isEqualTo(new BigDecimal("80.0000"));
  }

  @Test
  void patchOnAClosedPositionConflicts() throws Exception {
    String sym = "TESTOPT-" + UUID.randomUUID();
    long id =
        paper
            .openOrder(
                new PaperService.OrderRequest(
                    null, "NFO", sym, "SELL", 50, new BigDecimal("100.00"), null, null, null, "manual", null))
            .id();
    paper.closePosition(id, new BigDecimal("100.00"));

    mockMvc
        .perform(
            patch("/api/v1/paper/positions/" + id + "/brackets")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("takeProfit", "80.00"))))
        .andExpect(status().isConflict());
  }

  @Test
  void patchWithNoLevelIs400AndUnknownIdIs404() throws Exception {
    String sym = "TESTOPT-" + UUID.randomUUID();
    long id =
        paper
            .openOrder(
                new PaperService.OrderRequest(
                    null, "NFO", sym, "BUY", 50, new BigDecimal("100.00"), null, null, null, "manual", null))
            .id();
    mockMvc
        .perform(
            patch("/api/v1/paper/positions/" + id + "/brackets")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());

    mockMvc.perform(get("/api/v1/paper/positions/999999999")).andExpect(status().isNotFound());
  }

  private void seedTick(String sym, String price) throws Exception {
    redis
        .opsForHash()
        .put(
            "ticks:last",
            "NFO:" + sym,
            json(Map.of("exchange", "NFO", "tradingsymbol", sym, "lastPrice", price)));
  }

  private String json(Map<String, ?> body) throws Exception {
    return objectMapper.writeValueAsString(body);
  }
}
