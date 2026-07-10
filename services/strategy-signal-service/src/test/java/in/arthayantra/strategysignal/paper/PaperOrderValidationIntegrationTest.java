package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategyengine.fills.InstrumentClass;
import in.arthayantra.strategysignal.paper.InstrumentMetaClient.InstrumentMeta;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.math.BigDecimal;
import java.util.HashMap;
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
import org.springframework.test.web.servlet.MvcResult;

/**
 * Audit V2 + V4 (app-platform audit 2026-07-10 §8) on the MANUAL order path (POST /api/v1/paper/orders):
 *
 * <ul>
 *   <li><b>V2 idempotency</b>: an optional {@code clientOrderId} makes a duplicate submission (network
 *       retry / double-click) replay the ORIGINAL position (200, original DTO) instead of a second fill.
 *       The check runs BEFORE the risk governor, so a replay after a governor trips still returns the
 *       original (not a 422). Without a {@code clientOrderId} the prior double-fill behaviour stands.
 *   <li><b>V4 lot-size multiple</b>: a hand ticket for an F&amp;O instrument whose qty is not a whole
 *       multiple of the contract lot is 400 VALIDATION_FAILED (Upstox UDAPI1104 precedent); equity
 *       (lot 1) passes any qty.
 * </ul>
 *
 * <p>Uses a DEDICATED book ('a2val', unseeded → ungoverned) with UUID symbols / clientOrderIds and its
 * own per-method cleanup, so it never collides with A1's 'manual'-book governor IT on the singleton DB
 * (nor with surefire reruns). Engine disabled so no live components spin up.
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
@AutoConfigureMockMvc
class PaperOrderValidationIntegrationTest extends StrategySignalIntegrationTestBase {

  private static final String BOOK = "a2val";

  @TestConfiguration
  static class StubInstruments {
    @Bean
    @Primary
    InstrumentMetaClient stubInstrumentMetaClient() {
      // 'LOT75-…' symbols resolve as a 75-lot option (an F&O lot); everything else is plain equity (lot 1).
      return (exchange, tradingsymbol) ->
          tradingsymbol != null && tradingsymbol.startsWith("LOT75-")
              ? new InstrumentMeta(InstrumentClass.OPTION, new BigDecimal("0.05"), 75)
              : new InstrumentMeta(InstrumentClass.EQUITY, new BigDecimal("0.05"), 1);
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private RiskService risk;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper objectMapper;

  @BeforeEach
  @org.junit.jupiter.api.AfterEach
  void reset() {
    // wipe this book's ledger + any governor row so each method starts from a clean, ungoverned slate.
    jdbc.update("DELETE FROM paper_orders WHERE book=?", BOOK);
    jdbc.update("DELETE FROM paper_positions WHERE book=?", BOOK);
    jdbc.update("DELETE FROM risk_settings WHERE book=?", BOOK);
    jdbc.update("DELETE FROM risk_audit WHERE book=?", BOOK);
  }

  // ─── V2 idempotency ──────────────────────────────────────────────────────────────────────────────

  @Test
  void sameClientOrderIdTwiceReturnsTheOriginalAndFillsOnce() throws Exception {
    String sym = "EQ-" + UUID.randomUUID();
    String clientOrderId = "coid-" + UUID.randomUUID();

    MvcResult first =
        mockMvc
            .perform(post("/api/v1/paper/orders").contentType(MediaType.APPLICATION_JSON).content(order(sym, clientOrderId, 50)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("OPEN"))
            .andReturn();
    long originalId = idOf(first);

    // the SAME clientOrderId → 200 whose body is the ORIGINAL position (same id, still OPEN), not a re-fill.
    MvcResult replay =
        mockMvc
            .perform(post("/api/v1/paper/orders").contentType(MediaType.APPLICATION_JSON).content(order(sym, clientOrderId, 50)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("OPEN"))
            .andReturn();
    assertThat(idOf(replay)).isEqualTo(originalId);

    assertThat(openCount(sym)).isEqualTo(1);
    assertThat(orderCount(sym)).isEqualTo(1); // the replay inserted no second fill
    assertThat(qtyOf(sym)).isEqualTo(50L); // qty NOT doubled
  }

  @Test
  void duplicateReplayAfterTheGovernorTripsStillReturnsTheOriginal() throws Exception {
    String sym = "EQ-" + UUID.randomUUID();
    String clientOrderId = "coid-" + UUID.randomUUID();

    MvcResult first =
        mockMvc
            .perform(post("/api/v1/paper/orders").contentType(MediaType.APPLICATION_JSON).content(order(sym, clientOrderId, 50)))
            .andExpect(status().isCreated())
            .andReturn();
    long originalId = idOf(first);

    // trip the kill switch AFTER the original filled.
    risk.update(BOOK, RiskService.KILL_SWITCH, "{\"enabled\":true}");

    // a FRESH order on the now-tripped book is vetoed (proves the governor is genuinely active) …
    mockMvc
        .perform(post("/api/v1/paper/orders").contentType(MediaType.APPLICATION_JSON).content(order("EQ-" + UUID.randomUUID(), null, 50)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("RISK_ENTRY_BLOCKED"));

    // … yet the duplicate replay still returns the original (idempotency check runs BEFORE the governor).
    MvcResult replay =
        mockMvc
            .perform(post("/api/v1/paper/orders").contentType(MediaType.APPLICATION_JSON).content(order(sym, clientOrderId, 50)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("OPEN"))
            .andReturn();
    assertThat(idOf(replay)).isEqualTo(originalId);

    assertThat(openCount(sym)).isEqualTo(1);
  }

  @Test
  void withNoClientOrderIdARetryDoubleFills() throws Exception {
    // Current (pre-V2) behaviour preserved: with no idempotency key nothing dedups, so a retry doubles
    // qty into the averaged same-key position (audit §8: "a network retry doubles qty into the averaged
    // position") and leaves two order rows.
    String sym = "EQ-" + UUID.randomUUID();

    mockMvc
        .perform(post("/api/v1/paper/orders").contentType(MediaType.APPLICATION_JSON).content(order(sym, null, 50)))
        .andExpect(status().isCreated());
    mockMvc
        .perform(post("/api/v1/paper/orders").contentType(MediaType.APPLICATION_JSON).content(order(sym, null, 50)))
        .andExpect(status().isCreated());

    assertThat(openCount(sym)).isEqualTo(1); // same key averages into one position …
    assertThat(qtyOf(sym)).isEqualTo(100L); // … but qty DOUBLED (the double-fill V2 fixes)
    assertThat(orderCount(sym)).isEqualTo(2);
  }

  // ─── V4 lot-size multiple ────────────────────────────────────────────────────────────────────────

  @Test
  void fnoQtyNotALotMultipleIs400NamingTheLotSize() throws Exception {
    String sym = "LOT75-" + UUID.randomUUID();

    mockMvc
        .perform(post("/api/v1/paper/orders").contentType(MediaType.APPLICATION_JSON).content(order(sym, null, 50)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.details.lotSize").value(75));

    assertThat(openCount(sym)).isZero(); // rejected before any fill
  }

  @Test
  void fnoQtyThatIsALotMultiplePasses() throws Exception {
    String sym = "LOT75-" + UUID.randomUUID();

    mockMvc
        .perform(post("/api/v1/paper/orders").contentType(MediaType.APPLICATION_JSON).content(order(sym, null, 150)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("OPEN"));

    assertThat(openCount(sym)).isEqualTo(1);
  }

  @Test
  void equityAnyQtyPassesUnaffectedByLotValidation() throws Exception {
    String sym = "EQ-" + UUID.randomUUID();

    mockMvc
        .perform(post("/api/v1/paper/orders").contentType(MediaType.APPLICATION_JSON).content(order(sym, null, 37)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("OPEN"));

    assertThat(openCount(sym)).isEqualTo(1);
  }

  // ─── helpers ─────────────────────────────────────────────────────────────────────────────────────

  private String order(String sym, String clientOrderId, long qty) throws Exception {
    Map<String, Object> body = new HashMap<>();
    body.put("exchange", "NSE");
    body.put("tradingsymbol", sym);
    body.put("side", "BUY");
    body.put("qty", qty);
    body.put("price", "100.00");
    body.put("book", BOOK);
    if (clientOrderId != null) {
      body.put("clientOrderId", clientOrderId);
    }
    return objectMapper.writeValueAsString(body);
  }

  private long idOf(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
  }

  private int openCount(String sym) {
    Integer c =
        jdbc.queryForObject(
            "SELECT count(*) FROM paper_positions WHERE book=? AND tradingsymbol=? AND status='OPEN'",
            Integer.class,
            BOOK,
            sym);
    return c == null ? 0 : c;
  }

  private int orderCount(String sym) {
    Integer c =
        jdbc.queryForObject(
            "SELECT count(*) FROM paper_orders WHERE book=? AND tradingsymbol=?", Integer.class, BOOK, sym);
    return c == null ? 0 : c;
  }

  private long qtyOf(String sym) {
    Long q =
        jdbc.queryForObject(
            "SELECT qty FROM paper_positions WHERE book=? AND tradingsymbol=? AND status='OPEN'",
            Long.class,
            BOOK,
            sym);
    return q == null ? 0 : q;
  }
}
