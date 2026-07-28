package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.error.ApiException;
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
      // every test symbol resolves as a plain equity (lot 1) so an explicit-price fill always prices —
      // EXCEPT the "OPT" prefix, which resolves as an OPTION so a SELL exercises the margin-valued
      // path. Existing methods use non-OPT symbols and are unaffected.
      return (exchange, tradingsymbol) ->
          tradingsymbol.startsWith("OPT")
              ? new InstrumentMeta(InstrumentClass.OPTION, new BigDecimal("0.05"), 1)
              : new InstrumentMeta(InstrumentClass.EQUITY, new BigDecimal("0.05"), 1);
    }

    @Bean
    @Primary
    MarginServiceClient stubMarginServiceClient() {
      // No SPAN: forces PaperAccountService onto its deterministic flat margin-pct fallback, so the
      // short-option assertion below pins a fixed number instead of whatever a live basket returns.
      return (exchange, tradingsymbol, side, price, qty) -> java.util.Optional.empty();
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
    // The deployment rail too: these ITs share the singleton DB with no per-method cleanup, so a rail
    // left armed by the projection tests below would silently veto a sibling method's open.
    risk.update(BOOK, RiskService.MAX_DEPLOYMENT_PCT, "{\"enabled\":false}");
    jdbc.update("DELETE FROM paper_positions WHERE book=?", BOOK);
    jdbc.update("DELETE FROM paper_orders WHERE book=?", BOOK);
    jdbc.update("DELETE FROM paper_order_rejections WHERE book=?", BOOK);
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

  @Test
  void theOrderThatCrossesTheDeploymentCapIsRefusedAtTheWriter() {
    // #1075 Critical 3. The old rail compared USED against the cap, so it could only refuse once the
    // book was ALREADY at or over it — the order that CROSSED was admitted in full. Cap 10% of the
    // ₹150,000 book = ₹15,000; this fill projects 50 × ₹400 = ₹20,000, which crosses from zero used.
    // It is refused on the TAKEN path (openOrder), which the emission-time gate cannot reach because
    // neither the leg nor the size exists yet when it runs.
    risk.update(BOOK, RiskService.MAX_DEPLOYMENT_PCT, "{\"enabled\":true,\"value\":10}");
    String sym = "GOVDEPLOY-" + UUID.randomUUID();

    assertThatThrownBy(
            () ->
                paper.openOrder(
                    new PaperService.OrderRequest(
                        null, "NSE", sym, "BUY", 50, new BigDecimal("400.00"), null, null)))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining(RiskService.MAX_DEPLOYMENT_PCT);

    assertThat(openCount(sym)).isZero();
    // The refusal is durable despite the rollback the throw causes (REQUIRES_NEW, like DATA_STALE).
    assertThat(rejectionCount(sym, "DEPLOYMENT_BLOCKED")).isEqualTo(1);
  }

  @Test
  void deploymentProjectionAccumulatesAcrossSequentialOpens() {
    // Cumulative projection at the writer: each open is projected against a capitalUsed that already
    // includes the committed ones before it. Cap 10% = 15,000; each open costs 50 x 200 = 10,000, so
    // the first fits (10,000) and the second crosses (20,000).
    //
    // NOTE: these are two INDEPENDENT orders, not a straddle. A straddle must never end up like this —
    // see theStraddlePairIsAtomicSoARefusedSecondLegLeavesNoNakedPosition below.
    risk.update(BOOK, RiskService.MAX_DEPLOYMENT_PCT, "{\"enabled\":true,\"value\":10}");
    String a = "GOVSEQ-A-" + UUID.randomUUID();
    String b = "GOVSEQ-B-" + UUID.randomUUID();

    paper.openOrder(
        new PaperService.OrderRequest(null, "NSE", a, "BUY", 50, new BigDecimal("200.00"), null, null));

    assertThatThrownBy(
            () ->
                paper.openOrder(
                    new PaperService.OrderRequest(
                        null, "NSE", b, "BUY", 50, new BigDecimal("200.00"), null, null)))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining(RiskService.MAX_DEPLOYMENT_PCT);

    assertThat(openCount(a)).isEqualTo(1);
    assertThat(openCount(b)).isZero();
  }

  @Test
  void theStraddlePairIsAtomicSoARefusedSecondLegLeavesNoNakedPosition() {
    // #1075 cross-vendor round 3, the Critical my own fix introduced. Once a capital cap can refuse
    // the SECOND leg, opening the legs independently leaves the FIRST one open — a delta-neutral
    // straddle silently becomes a NAKED directional position. That is strictly worse than the cap
    // breach the refusal prevents: a breach is a sizing error, a naked leg is a different strategy.
    //
    // openPair runs both legs in ONE transaction, so the refusal rolls leg 1 back: both or neither.
    // Cap 10% = 15,000; each leg costs 50 x 200 = 10,000, so the pair (20,000) cannot fit.
    risk.update(BOOK, RiskService.MAX_DEPLOYMENT_PCT, "{\"enabled\":true,\"value\":10}");
    String ce = "GOVPAIR-CE-" + UUID.randomUUID();
    String pe = "GOVPAIR-PE-" + UUID.randomUUID();

    assertThatThrownBy(
            () ->
                paper.openPair(
                    new PaperService.OrderRequest(
                        null, "NSE", ce, "BUY", 50, new BigDecimal("200.00"), null, null),
                    new PaperService.OrderRequest(
                        null, "NSE", pe, "BUY", 50, new BigDecimal("200.00"), null, null)))
        .isInstanceOf(ApiException.class);

    // NEITHER leg survives — this is the whole point.
    assertThat(openCount(ce)).isZero();
    assertThat(openCount(pe)).isZero();
  }

  @Test
  void aStraddlePairThatFitsOpensBothLegs() {
    // The other half of atomicity: when the pair fits, both legs open. Cap 10% = 15,000; each leg
    // costs 50 x 100 = 5,000, so the pair is 10,000 and fits.
    risk.update(BOOK, RiskService.MAX_DEPLOYMENT_PCT, "{\"enabled\":true,\"value\":10}");
    String ce = "GOVPAIROK-CE-" + UUID.randomUUID();
    String pe = "GOVPAIROK-PE-" + UUID.randomUUID();

    paper.openPair(
        new PaperService.OrderRequest(
            null, "NSE", ce, "BUY", 50, new BigDecimal("100.00"), null, null),
        new PaperService.OrderRequest(
            null, "NSE", pe, "BUY", 50, new BigDecimal("100.00"), null, null));

    assertThat(openCount(ce)).isEqualTo(1);
    assertThat(openCount(pe)).isEqualTo(1);
  }

  @Test
  void anAddOntoAnOpenKeyIsChargedToTheExistingRowsSubAccountNotTheRequestedOne() {
    // #1075 Major 4. upsertPosition averages an add into the open row and KEEPS its original
    // subaccount_idx, so validating the REQUESTED idx checks an account the money never reaches. Open
    // on account 1, then add to the same key asking for account 4: the ceiling must be evaluated
    // against 1 (where the capital actually lands), and the row must still be charged to 1.
    // Sub-accounts are indexed 1..5 (scalper_subaccount, FK-enforced) — NOT 0-based.
    String sym = "GOVSUBACCT-" + UUID.randomUUID();
    paper.openOrder(
        new PaperService.OrderRequest(
            null, "NSE", sym, "BUY", 10, new BigDecimal("100.00"), null, null, 1));

    paper.openOrder(
        new PaperService.OrderRequest(
            null, "NSE", sym, "BUY", 10, new BigDecimal("100.00"), null, null, 4));

    assertThat(openCount(sym)).isEqualTo(1);
    Integer idx =
        jdbc.queryForObject(
            "SELECT subaccount_idx FROM paper_positions WHERE book=? AND tradingsymbol=? AND status='OPEN'",
            Integer.class,
            BOOK,
            sym);
    assertThat(idx).isEqualTo(1);
  }

  @Test
  void theSubAccountCeilingBindsOnTheExistingRowsAccountNotTheRequestedOne() {
    // #1075 Major 4, the DECISIVE case. The sibling test above only pins that upsertPosition keeps the
    // original idx — that was already true, so it passes with or without the ceiling. This one fails
    // unless the ceiling reads the EFFECTIVE account.
    //
    // Account 1 allocation = 150,000 x 0.20 = 30,000. Open 25,000 on account 1, then add 10,000 asking
    // for account 4. Against the EFFECTIVE account (1) that is 35,000 > 30,000 and must be refused.
    // Against the REQUESTED account (4, deployed 0) it is 10,000 and would be admitted — which is
    // exactly the hole: the money lands on 1 either way, because the add averages into 1's row.
    String sym = "GOVSUBCAP-" + UUID.randomUUID();
    paper.openOrder(
        new PaperService.OrderRequest(
            null, "NSE", sym, "BUY", 250, new BigDecimal("100.00"), null, null, 1));

    assertThatThrownBy(
            () ->
                paper.openOrder(
                    new PaperService.OrderRequest(
                        null, "NSE", sym, "BUY", 100, new BigDecimal("100.00"), null, null, 4)))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("sub_account_allocation");

    // the refused add left the original position untouched at its first-open quantity.
    Long qty =
        jdbc.queryForObject(
            "SELECT qty FROM paper_positions WHERE book=? AND tradingsymbol=? AND status='OPEN'",
            Long.class,
            BOOK,
            sym);
    assertThat(qty).isEqualTo(250L);
  }

  @Test
  void aShortOptionCandidateIsValuedAsMarginNotFullNotional() {
    // The deployment candidate must be valued the SAME way capitalUsed values an open position:
    // PaperAccountService.usageFor charges a SHORT option its margin approximation (notional x 0.12),
    // not the full notional. Raw `price x qty` compares a notional candidate against a margin-based
    // sum and refuses orders that fit easily.
    //
    // Cap 10% of the 150,000 book = 15,000. SELL 100 @ 1,000 = 100,000 notional, but 12,000 of usage.
    // Correct valuation ADMITS it (12,000 < 15,000); raw notional REFUSES it (100,000 > 15,000).
    // The OPT prefix makes the stub resolve an OPTION; the stubbed margin client returns empty so the
    // flat-pct fallback is deterministic.
    risk.update(BOOK, RiskService.MAX_DEPLOYMENT_PCT, "{\"enabled\":true,\"value\":10}");
    String sym = "OPTSHORT-" + UUID.randomUUID();

    PaperService.PositionDto opened =
        paper.openOrder(
            new PaperService.OrderRequest(
                null, "NFO", sym, "SELL", 100, new BigDecimal("1000.00"), null, null));

    assertThat(opened.status()).isEqualTo("OPEN");
    assertThat(openCount(sym)).isEqualTo(1);
    assertThat(rejectionCount(sym, "DEPLOYMENT_BLOCKED")).isZero();
  }

  @Test
  void aScalperEntryAssignsItsSubAccountUnderTheBookLockNotBeforeIt() {
    // #1075 cross-vendor round 4. The ceiling was enforced under the lock but the PICK still happened
    // outside it, so two concurrent takes both read account 1 as idle, both chose it, and the (now
    // correctly serialized) writer REFUSED the second against account 1's allocation instead of
    // routing it to idle account 2. A capital-aware picker only helps if it reads capital nobody else
    // is about to claim.
    //
    // openScalperOrder assigns inside the same lock+transaction that validates and writes, so the
    // second entry sees the first one's capital and picks a DIFFERENT account. Uses the SCALPER book
    // because that is the book the assignment reads; cleaned up here since the shared reset() owns
    // only the manual book.
    String a = "GOVASSIGN-A-" + UUID.randomUUID();
    String b = "GOVASSIGN-B-" + UUID.randomUUID();
    jdbc.update("DELETE FROM paper_positions WHERE book=?", BookResolver.SCALPER);
    try {
      paper.openScalperOrder(
          new PaperService.OrderRequest(
              null, "NSE", a, "BUY", 100, new BigDecimal("100.00"), null, null, null,
              BookResolver.SCALPER));
      paper.openScalperOrder(
          new PaperService.OrderRequest(
              null, "NSE", b, "BUY", 100, new BigDecimal("100.00"), null, null, null,
              BookResolver.SCALPER));

      Integer first = subAccountOf(a);
      Integer second = subAccountOf(b);
      assertThat(first).isNotNull();
      assertThat(second).isNotNull();
      assertThat(second).isNotEqualTo(first);
    } finally {
      jdbc.update("DELETE FROM paper_orders WHERE book=?", BookResolver.SCALPER);
      jdbc.update("DELETE FROM paper_positions WHERE book=?", BookResolver.SCALPER);
    }
  }

  private Integer subAccountOf(String sym) {
    return jdbc.queryForObject(
        "SELECT subaccount_idx FROM paper_positions WHERE tradingsymbol=? AND status='OPEN'",
        Integer.class,
        sym);
  }

  private int rejectionCount(String sym, String reason) {
    Integer c =
        jdbc.queryForObject(
            "SELECT count(*) FROM paper_order_rejections WHERE book=? AND tradingsymbol=? AND reason=?",
            Integer.class,
            BOOK,
            sym,
            reason);
    return c == null ? 0 : c;
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
