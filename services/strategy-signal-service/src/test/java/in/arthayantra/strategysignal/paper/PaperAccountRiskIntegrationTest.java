package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategyengine.config.StrategyDefinition;
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
 * Phase 43A IT (mock profile): the computed equity, percent_equity suggested qty, the global
 * daily-loss trip (ENTRY gate + audit), the kill switch, and the D18-invariance of a settings flip.
 * Shared global state is reset per method for deterministic equity.
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
@AutoConfigureMockMvc
class PaperAccountRiskIntegrationTest extends StrategySignalIntegrationTestBase {

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
  @Autowired private PaperAccountService account;
  @Autowired private RiskService risk;
  @Autowired private PaperEmissionGuard guard;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper objectMapper;

  @BeforeEach
  @org.junit.jupiter.api.AfterEach
  void reset() {
    // clean global paper/risk state so leftover limits never suppress another class's emission
    risk.update("daily_loss_limit", "{\"enabled\":false}"); // clears the in-memory per-day trip dedup
    jdbc.update("DELETE FROM paper_positions");
    jdbc.update("DELETE FROM paper_orders");
    jdbc.update("DELETE FROM risk_settings");
    jdbc.update("DELETE FROM risk_audit");
    jdbc.update("UPDATE paper_account SET starting_capital=1000000, cash=1000000 WHERE id=1");
  }

  @Test
  void equityIsStartingCapitalPlusRealized() {
    insertClosed("BUY", 50, "5000.0000");
    assertThat(account.equity()).isEqualByComparingTo("1005000");
  }

  @Test
  void percentEquitySizesAgainstPaperEquityLotRounded() {
    StrategyDefinition.SizingSpec spec =
        new StrategyDefinition.SizingSpec("percent_equity", Map.of("percent", new BigDecimal("10")));
    // 10% of 1,000,000 = 100,000 budget / price 100 = 1000 units (lot 1)
    BigDecimal qty = guard.suggestedQty(spec, "NSE", "RELIANCE", new BigDecimal("100"), null);
    assertThat(qty).isEqualByComparingTo("1000");
  }

  @Test
  void gradedMultiplierScalesTheSuggestedQtyLotRoundedDown() {
    StrategyDefinition.SizingSpec spec =
        new StrategyDefinition.SizingSpec("percent_equity", Map.of("percent", new BigDecimal("10")));
    BigDecimal full = guard.suggestedQty(spec, "NSE", "RELIANCE", new BigDecimal("100"), null); // 1000
    // E8 §3.2: a null multiplier (6-arg) == the ungraded sizing (byte-identical to today's 5-arg path)
    assertThat(guard.suggestedQty(spec, "NSE", "RELIANCE", new BigDecimal("100"), null, null))
        .isEqualByComparingTo(full);
    // a 0.5 multiplier halves the qty, lot-rounded DOWN (RELIANCE lot 1 -> 1000 -> 500)
    assertThat(guard.suggestedQty(spec, "NSE", "RELIANCE", new BigDecimal("100"), null, new BigDecimal("0.5")))
        .isEqualByComparingTo("500");
    // a 1.0 multiplier leaves it unchanged
    assertThat(guard.suggestedQty(spec, "NSE", "RELIANCE", new BigDecimal("100"), null, BigDecimal.ONE))
        .isEqualByComparingTo(full);
  }

  @Test
  void dailyLossTripPausesEntryAndWritesAnAuditRow() {
    insertClosed("BUY", 50, "-100000.0000"); // a big loss today
    risk.update("daily_loss_limit", "{\"enabled\":true,\"mode\":\"inr\",\"value\":50000}");
    assertThat(risk.entryAllowed()).isFalse();
    Integer trips =
        jdbc.queryForObject("SELECT count(*) FROM risk_audit WHERE action='TRIP'", Integer.class);
    assertThat(trips).isGreaterThanOrEqualTo(1);
  }

  @Test
  void pctModeDailyLossTripsOnPercentOfEquity() {
    // E10 #1: the seeded mode the V011 migration uses — 10% of the 1,000,000 equity = 100,000 cap.
    insertClosed("BUY", 50, "-150000.0000"); // a day loss past the 10% cap
    risk.update("daily_loss_limit", "{\"enabled\":true,\"mode\":\"pct\",\"value\":10}");
    assertThat(risk.entryAllowed()).isFalse();
    // a small loss within the cap does not pause entries.
    risk.update("daily_loss_limit", "{\"enabled\":false}"); // re-arm
    jdbc.update("DELETE FROM paper_positions");
    insertClosed("BUY", 50, "-50000.0000");
    risk.update("daily_loss_limit", "{\"enabled\":true,\"mode\":\"pct\",\"value\":10}");
    assertThat(risk.entryAllowed()).isTrue();
  }

  @Test
  void dailyProfitTargetPausesEntryAndAuditsItsOwnKey() {
    // E10: a banked WIN past the daily profit target stops fresh entries for the day.
    insertClosed("BUY", 50, "50000.0000"); // +50,000 realized today
    risk.update("daily_profit_target", "{\"enabled\":true,\"mode\":\"inr\",\"value\":20000}");
    assertThat(risk.entryAllowed()).isFalse();
    Integer trips =
        jdbc.queryForObject(
            "SELECT count(*) FROM risk_audit WHERE action='TRIP' AND key='daily_profit_target'",
            Integer.class);
    assertThat(trips).isGreaterThanOrEqualTo(1);
  }

  @Test
  void profitTargetPctModeSizesAgainstEquity() {
    // mode=pct value=1.5 -> threshold = 1.5% of 1,000,000 equity = 15,000.
    insertClosed("BUY", 50, "20000.0000"); // +20,000 > 15,000 cap
    risk.update("daily_profit_target", "{\"enabled\":true,\"mode\":\"pct\",\"value\":1.5}");
    assertThat(risk.entryAllowed()).isFalse();
    // a smaller win within the 15,000 threshold does not pause entries.
    risk.update("daily_profit_target", "{\"enabled\":false}"); // re-arm
    jdbc.update("DELETE FROM paper_positions");
    insertClosed("BUY", 50, "10000.0000");
    risk.update("daily_profit_target", "{\"enabled\":true,\"mode\":\"pct\",\"value\":1.5}");
    assertThat(risk.entryAllowed()).isTrue();
  }

  @Test
  void maxDeploymentPctBlocksWhenOpenDeploymentExceedsCap() {
    // an open EQUITY position deploys its full notional (500,000); cap = 20% of 1,000,000 = 200,000.
    insertOpen("DEPLOYTEST", "BUY", 5000, "100.0000");
    risk.update("max_deployment_pct", "{\"enabled\":true,\"value\":20.0}");
    assertThat(risk.entryAllowed()).isFalse();
    Integer trips =
        jdbc.queryForObject(
            "SELECT count(*) FROM risk_audit WHERE action='TRIP' AND key='max_deployment_pct'",
            Integer.class);
    assertThat(trips).isGreaterThanOrEqualTo(1);
  }

  @Test
  void killSwitchPausesAllEntries() {
    assertThat(risk.entryAllowed()).isTrue();
    risk.update("kill_switch", "{\"enabled\":true}");
    assertThat(risk.entryAllowed()).isFalse();
  }

  @Test
  void aRiskFlipMintsNoStrategyVersion() {
    Integer before = jdbc.queryForObject("SELECT count(*) FROM strategy_versions", Integer.class);
    risk.update("kill_switch", "{\"enabled\":true}");
    Integer after = jdbc.queryForObject("SELECT count(*) FROM strategy_versions", Integer.class);
    assertThat(after).isEqualTo(before);
  }

  @Test
  void accountEndpointReturnsTheComputedEquityHeader() throws Exception {
    mockMvc
        .perform(get("/api/v1/paper/account"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.startingCapital").value("1000000.00"))
        .andExpect(jsonPath("$.equity").value("1000000.00"))
        .andExpect(jsonPath("$.usageByClass").exists())
        .andExpect(jsonPath("$.marginPercents.future").exists());
  }

  @Test
  void overSizedOrderReturnsANonBlockingBuyingPowerWarning() throws Exception {
    jdbc.update("UPDATE paper_account SET starting_capital=100, cash=100 WHERE id=1");
    String sym = "TESTOPT-" + UUID.randomUUID();
    mockMvc
        .perform(
            post("/api/v1/paper/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        Map.of(
                            "exchange", "NFO",
                            "tradingsymbol", sym,
                            "side", "BUY",
                            "qty", 50,
                            "price", "100.00"))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.buyingPowerWarning").isString());
  }

  private void insertClosed(String side, long qty, String realized) {
    jdbc.update(
        """
        INSERT INTO paper_positions
          (exchange, tradingsymbol, side, qty, avg_entry_price, realized_pnl, status, opened_at, closed_at)
        VALUES ('NSE', 'PNLTEST', ?, ?, '100.0000', ?, 'CLOSED', now(), now())
        """,
        side,
        qty,
        new BigDecimal(realized));
  }

  private void insertOpen(String tradingsymbol, String side, long qty, String avgEntry) {
    jdbc.update(
        """
        INSERT INTO paper_positions
          (exchange, tradingsymbol, side, qty, avg_entry_price, status, opened_at)
        VALUES ('NSE', ?, ?, ?, ?, 'OPEN', now())
        """,
        tradingsymbol,
        side,
        qty,
        new BigDecimal(avgEntry));
  }
}
