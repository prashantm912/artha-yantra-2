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
 * Audit V1 (app-platform audit 2026-07-10 §8): manual paper-order tickets (POST /api/v1/paper/orders)
 * must clear the SAME per-book risk governor the engine-emitted entry clears at emission. Before the
 * fix, {@code PaperService.openOrder} filled a hand ticket with the kill switch ON. These ITs pin:
 * a kill-switched / max-open / daily-loss book VETOES a manual open with a 422 naming the blocking
 * rail and opens no position; a CLOSE on a kill-switched book still succeeds (governors gate NEW
 * exposure only); a healthy book opens unchanged; and the signal-taken path ({@code openOrder}) stays
 * ungated by design (it is gated at emission, so gating it here too would double-charge the entry).
 *
 * <p>Uses the 'manual' book (V021-seeded, ₹1.5 L) and cleans only that book's state so it never
 * clobbers a sibling IT sharing the singleton DB. Engine disabled so no live components spin up.
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
@AutoConfigureMockMvc
class PaperManualOrderGovernorIntegrationTest extends StrategySignalIntegrationTestBase {

  private static final String BOOK = "manual";

  @TestConfiguration
  static class StubInstruments {
    @Bean
    @Primary
    InstrumentMetaClient stubInstrumentMetaClient() {
      // every test symbol resolves as a plain equity (lot 1) so an explicit-price fill always prices.
      return (exchange, tradingsymbol) -> new InstrumentMeta(InstrumentClass.EQUITY, new BigDecimal("0.05"), 1);
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private PaperService paper;
  @Autowired private RiskService risk;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper objectMapper;

  @BeforeEach
  @org.junit.jupiter.api.AfterEach
  void reset() {
    // disable every governor on the manual book (also clears each cap's in-memory per-day trip dedup),
    // then wipe this book's ledger + audit so each method starts from a clean, ungoverned slate.
    risk.update(BOOK, RiskService.KILL_SWITCH, "{\"enabled\":false}");
    risk.update(BOOK, RiskService.MAX_OPEN, "{\"enabled\":false}");
    risk.update(BOOK, RiskService.DAILY_LOSS, "{\"enabled\":false}");
    jdbc.update("DELETE FROM paper_positions WHERE book=?", BOOK);
    jdbc.update("DELETE FROM paper_orders WHERE book=?", BOOK);
    jdbc.update("DELETE FROM risk_audit WHERE book=?", BOOK);
    jdbc.update("UPDATE paper_account SET starting_capital=150000, cash=150000 WHERE book=?", BOOK);
  }

  @Test
  void manualOpenOnKillSwitchedBookIsBlockedAndOpensNoPosition() throws Exception {
    String sym = "GOVKILL-" + UUID.randomUUID();
    risk.update(BOOK, RiskService.KILL_SWITCH, "{\"enabled\":true}");

    mockMvc
        .perform(post("/api/v1/paper/orders").contentType(MediaType.APPLICATION_JSON).content(order(sym)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("RISK_ENTRY_BLOCKED"))
        .andExpect(jsonPath("$.details.rail").value("kill_switch"))
        .andExpect(jsonPath("$.details.book").value(BOOK));

    assertThat(openCount(sym)).isZero();
  }

  @Test
  void manualOpenExceedingMaxOpenIsBlocked() throws Exception {
    insertOpen("MAXOPEN-" + UUID.randomUUID()); // one open already
    risk.update(BOOK, RiskService.MAX_OPEN, "{\"enabled\":true,\"value\":1}");

    mockMvc
        .perform(
            post("/api/v1/paper/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(order("GOVMAX-" + UUID.randomUUID())))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("RISK_ENTRY_BLOCKED"))
        .andExpect(jsonPath("$.details.rail").value("max_open_paper_positions"));

    // the veto opened nothing: the one pre-existing open position is still the only one on the book.
    Integer open =
        jdbc.queryForObject(
            "SELECT count(*) FROM paper_positions WHERE book=? AND status='OPEN'", Integer.class, BOOK);
    assertThat(open).isEqualTo(1);
  }

  @Test
  void manualOpenOnDailyLossHitBookIsBlockedAndWritesAnAuditRow() throws Exception {
    insertClosedLoss("-100000.0000");
    risk.update(BOOK, RiskService.DAILY_LOSS, "{\"enabled\":true,\"mode\":\"inr\",\"value\":1000}");
    String sym = "GOVLOSS-" + UUID.randomUUID();

    mockMvc
        .perform(post("/api/v1/paper/orders").contentType(MediaType.APPLICATION_JSON).content(order(sym)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("RISK_ENTRY_BLOCKED"))
        .andExpect(jsonPath("$.details.rail").value("daily_loss_limit"));

    // the trip audit survives the veto-throw (it is written OUTSIDE the fill txn, matching emission).
    Integer trips =
        jdbc.queryForObject(
            "SELECT count(*) FROM risk_audit WHERE book=? AND action='TRIP' AND key='daily_loss_limit'",
            Integer.class,
            BOOK);
    assertThat(trips).isGreaterThanOrEqualTo(1);
    assertThat(openCount(sym)).isZero();
  }

  @Test
  void manualCloseOnKillSwitchedBookSucceeds() throws Exception {
    // governors gate NEW exposure only — a kill-switched book must still allow closing an open position.
    String sym = "GOVCLOSE-" + UUID.randomUUID();
    insertOpen(sym);
    long id =
        jdbc.queryForObject(
            "SELECT id FROM paper_positions WHERE book=? AND tradingsymbol=? AND status='OPEN'",
            Long.class,
            BOOK,
            sym);
    risk.update(BOOK, RiskService.KILL_SWITCH, "{\"enabled\":true}");

    mockMvc
        .perform(
            post("/api/v1/paper/positions/" + id + "/close")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"price\":105.00}"))
        .andExpect(status().isOk());

    String stateOf =
        jdbc.queryForObject("SELECT status FROM paper_positions WHERE id=?", String.class, id);
    assertThat(stateOf).isEqualTo("CLOSED");
  }

  @Test
  void healthyBookManualOpenOpensThePositionUnchanged() throws Exception {
    String sym = "GOVOK-" + UUID.randomUUID();

    mockMvc
        .perform(post("/api/v1/paper/orders").contentType(MediaType.APPLICATION_JSON).content(order(sym)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("OPEN"))
        .andExpect(jsonPath("$.tradingsymbol").value(sym));

    assertThat(openCount(sym)).isEqualTo(1);
  }

  @Test
  void takenPathOpenOrderIsUngatedByDesign() {
    // The engine-emitted (taken) path gates the governor ONCE at emission, then opens via openOrder.
    // openOrder must therefore NOT re-consult the governor, or a taken entry would be double-charged.
    // Proof: with the kill switch ON, a direct openOrder still opens (the manual gate is openManualOrder).
    risk.update(BOOK, RiskService.KILL_SWITCH, "{\"enabled\":true}");
    String sym = "GOVTAKEN-" + UUID.randomUUID();

    PaperService.PositionDto opened =
        paper.openOrder(
            new PaperService.OrderRequest(null, "NSE", sym, "BUY", 50, new BigDecimal("100.00"), null, null));

    assertThat(opened.status()).isEqualTo("OPEN");
    assertThat(openCount(sym)).isEqualTo(1);
  }

  private String order(String sym) throws Exception {
    return objectMapper.writeValueAsString(
        Map.of(
            "exchange", "NSE",
            "tradingsymbol", sym,
            "side", "BUY",
            "qty", 50,
            "price", "100.00",
            "book", BOOK));
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

  private void insertOpen(String sym) {
    jdbc.update(
        """
        INSERT INTO paper_positions
          (exchange, tradingsymbol, side, qty, avg_entry_price, status, opened_at, book)
        VALUES ('NSE', ?, 'BUY', 50, '100.0000', 'OPEN', now(), ?)
        """,
        sym,
        BOOK);
  }

  private void insertClosedLoss(String realized) {
    jdbc.update(
        """
        INSERT INTO paper_positions
          (exchange, tradingsymbol, side, qty, avg_entry_price, realized_pnl, status, opened_at, closed_at, book)
        VALUES ('NSE', 'PNLLOSS', 'BUY', 50, '100.0000', ?, 'CLOSED', now(), now(), ?)
        """,
        new BigDecimal(realized),
        BOOK);
  }
}
