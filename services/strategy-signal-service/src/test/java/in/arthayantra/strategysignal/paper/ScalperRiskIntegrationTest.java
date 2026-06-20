package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * §12.7 scalper 5-sub-account discipline: a fresh day allows entries; 5 losing trades freeze all 5
 * sub-accounts; 5 winning trades bank the day. EXITS are never gated by it (the gate is consulted
 * only on the scalper ENTRY path — see {@code SignalEngine.scalperEntry}). Shared-DB IT: the ledger
 * is reset per method so each starts from a clean day.
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
class ScalperRiskIntegrationTest extends StrategySignalIntegrationTestBase {

  @Autowired private ScalperAccountModel scalperAccounts;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  @AfterEach
  void reset() {
    jdbc.update("DELETE FROM paper_positions");
  }

  @Test
  void aFreshDayAllowsScalperEntry() {
    assertThat(scalperAccounts.scalperEntryAllowed()).isTrue();
  }

  @Test
  void fiveLossesFreezeAllSubAccounts() {
    insertClosedTrades(0, 4);
    assertThat(scalperAccounts.scalperEntryAllowed()).as("4 losses — one account left").isTrue();
    insertClosedTrades(0, 1);
    assertThat(scalperAccounts.scalperEntryAllowed()).as("5 losses — all frozen").isFalse();
  }

  @Test
  void fiveWinsBankTheDay() {
    insertClosedTrades(4, 0);
    assertThat(scalperAccounts.scalperEntryAllowed()).as("4 wins — under the cap").isTrue();
    insertClosedTrades(1, 0);
    assertThat(scalperAccounts.scalperEntryAllowed()).as("5 wins — banked").isFalse();
  }

  @Test
  void winsAndLossesBelowBothCapsStillAllow() {
    insertClosedTrades(4, 4);
    assertThat(scalperAccounts.scalperEntryAllowed()).isTrue();
  }

  /** A flat trade (realized P&L == 0) counts as a loss (≤ 0), freezing an account. */
  @Test
  void aFlatTradeCountsAgainstTheAccounts() {
    insertClosed("0.0000"); // flat
    insertClosedTrades(0, 4); // + 4 losses = 5 ≤0 trades
    assertThat(scalperAccounts.scalperEntryAllowed()).isFalse();
  }

  private void insertClosedTrades(int wins, int losses) {
    for (int i = 0; i < wins; i++) {
      insertClosed("1000.0000");
    }
    for (int i = 0; i < losses; i++) {
      insertClosed("-1000.0000");
    }
  }

  private void insertClosed(String realized) {
    jdbc.update(
        """
        INSERT INTO paper_positions
          (exchange, tradingsymbol, side, qty, avg_entry_price, realized_pnl, status, opened_at, closed_at)
        VALUES ('NFO', 'SCALPTEST', 'BUY', 50, '100.0000', ?, 'CLOSED', now(), now())
        """,
        new BigDecimal(realized));
  }
}
