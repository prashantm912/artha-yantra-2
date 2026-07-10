package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.util.Map;
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
 * Audit §7.1/§7.2.5 (app-platform audit 2026-07-10): the two DESTRUCTIVE paper-book admin actions —
 * a book RESET (POST /api/v1/paper/reset) and a CAPITAL_CHANGE (PUT /api/v1/paper/account) — left ZERO
 * trace before this. These ITs run against the real Timescale container with the real
 * {@code deploy/flyway} strategy lineage (so V029 runs exactly as in prod) and pin: the {@code
 * paper_admin_audit} table exists post-Flyway; a reset writes a RESET row carrying the deleted
 * position/order counts; a capital edit writes a CAPITAL_CHANGE row carrying previous → new.
 *
 * <p>Shared singleton DB with NO per-method cleanup — each method uses dedicated stable identifiers
 * (its own book / paper_account id) and delete-firsts them so a sibling IT or surefire rerun cannot
 * leak in. Engine disabled so no live components spin up.
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
@AutoConfigureMockMvc
class PaperAdminAuditIntegrationTest extends StrategySignalIntegrationTestBase {

  private static final String RESET_BOOK = "a9-reset-book";
  private static final String CAP_BOOK = "a9-cap-book";
  private static final int CAP_ACCOUNT_ID = 9001; // dedicated SMALLINT id, delete-firsted per run

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper mapper;

  @BeforeEach
  @AfterEach
  void clean() {
    jdbc.update("DELETE FROM paper_positions WHERE book=?", RESET_BOOK);
    jdbc.update("DELETE FROM paper_orders WHERE book=?", RESET_BOOK);
    jdbc.update("DELETE FROM paper_admin_audit WHERE book IN (?, ?)", RESET_BOOK, CAP_BOOK);
    jdbc.update("DELETE FROM paper_account WHERE id=? OR book=?", CAP_ACCOUNT_ID, CAP_BOOK);
  }

  @Test
  void tableExistsAfterFlyway() {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.tables "
                + "WHERE table_schema = 'strategy' AND table_name = 'paper_admin_audit'",
            Integer.class);
    assertThat(n).isEqualTo(1);
  }

  @Test
  void resetWritesAResetAuditRowWithTheDeletedCounts() throws Exception {
    seedOpenPosition("A9RESETPOS");
    seedOrder("A9RESETORD");

    mockMvc
        .perform(
            post("/api/v1/paper/reset")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("confirm", true, "book", RESET_BOOK))))
        .andExpect(status().isNoContent());

    // the ledger is wiped ...
    assertThat(countPositions(RESET_BOOK)).isZero();
    assertThat(countOrders(RESET_BOOK)).isZero();

    // ... and a RESET audit row records the destructive action with its deleted counts.
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT action, book, detail->>'positionsDeleted' AS pos, detail->>'ordersDeleted' AS ord,"
                + " created_at FROM paper_admin_audit WHERE action='RESET' AND book=?",
            RESET_BOOK);
    assertThat(row.get("book")).isEqualTo(RESET_BOOK);
    assertThat(row.get("pos")).isEqualTo("1");
    assertThat(row.get("ord")).isEqualTo("1");
    assertThat(row.get("created_at")).isNotNull();
  }

  @Test
  void capitalChangeWritesACapitalChangeAuditRowWithPreviousAndNew() throws Exception {
    jdbc.update(
        "INSERT INTO paper_account (id, book, starting_capital, cash) VALUES (?, ?, 150000.00, 150000.00)",
        CAP_ACCOUNT_ID,
        CAP_BOOK);

    mockMvc
        .perform(
            put("/api/v1/paper/account")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("startingCapital", 200000, "book", CAP_BOOK))))
        .andExpect(status().isOk());

    // the capital actually changed ...
    assertThat(
            jdbc.queryForObject(
                "SELECT starting_capital FROM paper_account WHERE book=?",
                java.math.BigDecimal.class,
                CAP_BOOK))
        .isEqualByComparingTo("200000");

    // ... and a CAPITAL_CHANGE audit row records previous → new.
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT action, book, detail->>'previous' AS previous, detail->>'new' AS updated, created_at "
                + "FROM paper_admin_audit WHERE action='CAPITAL_CHANGE' AND book=?",
            CAP_BOOK);
    assertThat(row.get("book")).isEqualTo(CAP_BOOK);
    assertThat((String) row.get("previous")).startsWith("150000");
    assertThat(row.get("updated")).isEqualTo("200000");
    assertThat(row.get("created_at")).isNotNull();
  }

  private void seedOpenPosition(String symbol) {
    jdbc.update(
        """
        INSERT INTO paper_positions
          (exchange, tradingsymbol, side, qty, avg_entry_price, status, opened_at, book)
        VALUES ('NSE', ?, 'BUY', 50, '100.0000', 'OPEN', now(), ?)
        """,
        symbol,
        RESET_BOOK);
  }

  private void seedOrder(String symbol) {
    jdbc.update(
        "INSERT INTO paper_orders (exchange, tradingsymbol, side, qty, book) VALUES ('NSE', ?, 'BUY', 50, ?)",
        symbol,
        RESET_BOOK);
  }

  private int countPositions(String book) {
    Integer c =
        jdbc.queryForObject("SELECT count(*) FROM paper_positions WHERE book=?", Integer.class, book);
    return c == null ? 0 : c;
  }

  private int countOrders(String book) {
    Integer c =
        jdbc.queryForObject("SELECT count(*) FROM paper_orders WHERE book=?", Integer.class, book);
    return c == null ? 0 : c;
  }
}
