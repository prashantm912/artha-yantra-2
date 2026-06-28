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
  @Autowired private PaperAccountService paperAccount;
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

  /** E10: a losing trade freezes only the SPECIFIC sub-account that took it; all 5 frozen ⇒ blocked. */
  @Test
  void perAccountFirstLossFreezesAllFiveDistinctAccounts() {
    insertClosedOnAccount("-1000.0000", 1);
    insertClosedOnAccount("-1000.0000", 2);
    insertClosedOnAccount("-1000.0000", 3);
    insertClosedOnAccount("-1000.0000", 4);
    assertThat(scalperAccounts.scalperEntryAllowed()).as("4 accounts frozen — one free").isTrue();
    insertClosedOnAccount("-1000.0000", 5);
    assertThat(scalperAccounts.scalperEntryAllowed()).as("all 5 accounts frozen").isFalse();
  }

  /** E10: many losses on one account freeze ONLY that account, leaving the other four free. */
  @Test
  void perAccountManyLossesOnOneAccountLeaveOthersFree() {
    insertClosedOnAccount("-1000.0000", 2);
    insertClosedOnAccount("-1000.0000", 2);
    insertClosedOnAccount("-1000.0000", 2);
    assertThat(scalperAccounts.scalperEntryAllowed()).as("only account 2 frozen").isTrue();
  }

  /**
   * E10 NULL-idx fallback: 5 NULL-idx losses would block via the legacy count, but once a single
   * ledger trade carries an idx the per-account path is active and the NULL-idx losses are invisible.
   */
  @Test
  void oneLedgerTradeMakesNullIdxLossesInvisible() {
    insertClosedTrades(0, 5); // 5 NULL-idx losses — would block via the fallback on their own
    insertClosedOnAccount("1000.0000", 1); // one ledger WIN flips to the per-account path
    assertThat(scalperAccounts.scalperEntryAllowed()).isTrue();
  }

  /**
   * E10 1%-profit-lock: a sub-account that banks ~1% of its allocated capital freezes for the day,
   * exactly like a loss. Here 4 accounts are loss-frozen and the 5th banks a win above its target —
   * all 5 frozen ⇒ blocked — with only 1 aggregate win and 4 losses (neither legacy cap fires).
   */
  @Test
  void aProfitBankedAccountFreezesLikeALoss() {
    insertClosedOnAccount("-1000.0000", 1);
    insertClosedOnAccount("-1000.0000", 2);
    insertClosedOnAccount("-1000.0000", 3);
    insertClosedOnAccount("-1000.0000", 4);
    assertThat(scalperAccounts.scalperEntryAllowed()).as("4 frozen — account 5 free").isTrue();
    // ₹10L starting capital × 0.20 × 1% = ₹2,000 target; a ₹10,000 win on account 5 clears it → banked.
    BigDecimal target =
        paperAccount.startingCapital().multiply(new BigDecimal("0.20")).multiply(new BigDecimal("0.01"));
    assertThat(target).as("sanity: target well under the staged win").isLessThan(new BigDecimal("10000"));
    insertClosedOnAccount("10000.0000", 5);
    assertThat(scalperAccounts.scalperEntryAllowed()).as("account 5 profit-banked — all 5 frozen").isFalse();
  }

  private void insertClosedTrades(int wins, int losses) {
    for (int i = 0; i < wins; i++) {
      insertClosed("1000.0000");
    }
    for (int i = 0; i < losses; i++) {
      insertClosed("-1000.0000");
    }
  }

  private void insertClosedOnAccount(String realized, int idx) {
    jdbc.update(
        """
        INSERT INTO paper_positions
          (exchange, tradingsymbol, side, qty, avg_entry_price, realized_pnl, status, opened_at, closed_at, subaccount_idx)
        VALUES ('NFO', 'SCALPTEST', 'BUY', 50, '100.0000', ?, 'CLOSED', now(), now(), ?)
        """,
        new BigDecimal(realized),
        idx);
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
