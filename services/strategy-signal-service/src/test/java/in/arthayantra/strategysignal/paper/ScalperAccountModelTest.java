package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.arthayantra.strategysignal.paper.PaperPositionRepository.SubAccountTally;
import in.arthayantra.strategysignal.paper.PaperPositionRepository.WinLoss;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit-level cover for the E10 five-account ledger gate in {@link ScalperAccountModel}: the
 * per-account first-loss freeze, its NULL-idx fallback to the legacy day-count, and the aggregate
 * 5-wins/day cap — all by mocking the repository tallies (no DB; the wired path is exercised by
 * {@code ScalperRiskIntegrationTest}).
 */
class ScalperAccountModelTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-06-25T06:00:00Z"), ZoneId.of("UTC"));

  /** ₹10L starting capital × 0.20 fraction × 1% = ₹2,000 profit-lock target per account. */
  private static final BigDecimal STARTING_CAPITAL = new BigDecimal("1000000");
  private static final BigDecimal FRACTION = new BigDecimal("0.20");
  private static final BigDecimal OVER_TARGET = new BigDecimal("2500"); // ≥ ₹2,000 → banked
  private static final BigDecimal UNDER_TARGET = new BigDecimal("500"); // < ₹2,000 → not banked

  private static ScalperAccountModel model(WinLoss wl, List<SubAccountTally> tallies) {
    PaperPositionRepository repo = mock(PaperPositionRepository.class);
    when(repo.winLossOn(any(), any())).thenReturn(wl); // model calls winLossOn(SCALPER, today)
    when(repo.subAccountTalliesOn(any())).thenReturn(tallies);
    when(repo.subAccountCapitalFractions())
        .thenReturn(Map.of(1, FRACTION, 2, FRACTION, 3, FRACTION, 4, FRACTION, 5, FRACTION));
    PaperAccountService account = mock(PaperAccountService.class);
    when(account.startingCapital("scalper")).thenReturn(STARTING_CAPITAL);
    return new ScalperAccountModel(repo, account, CLOCK);
  }

  private static SubAccountTally loss(int idx) {
    return new SubAccountTally(idx, 0, 1);
  }

  /** A profit-banked account: wins summing past the ~1% target, no loss. */
  private static SubAccountTally banked(int idx, int wins) {
    return new SubAccountTally(idx, wins, 0, OVER_TARGET);
  }

  @Test
  void freshDayAllowsEntry() {
    assertThat(model(new WinLoss(0, 0), List.of()).scalperEntryAllowed()).isTrue();
  }

  @Test
  void aggregateFiveWinsBankTheDayEvenWithLedgerTallies() {
    // the 5-wins cap is checked first and spans ALL closed trades, so it fires regardless of idx.
    assertThat(model(new WinLoss(5, 0), List.of()).scalperEntryAllowed()).isFalse();
    assertThat(model(new WinLoss(5, 0), List.of(new SubAccountTally(1, 5, 0))).scalperEntryAllowed())
        .isFalse();
  }

  @Test
  void nullIdxFallbackUsesTheLegacyLossCountWhenNoTradeCarriesAnIdx() {
    // no ledger trades today → fall back to the day-granularity count (the pre-E10 behaviour).
    assertThat(model(new WinLoss(0, 4), List.of()).scalperEntryAllowed()).as("4 losses").isTrue();
    assertThat(model(new WinLoss(0, 5), List.of()).scalperEntryAllowed()).as("5 losses").isFalse();
  }

  @Test
  void perAccountFreezeBlocksOnlyWhenAllFiveAreFrozen() {
    // one losing account → four still free → allowed.
    assertThat(model(new WinLoss(0, 1), List.of(loss(1))).scalperEntryAllowed()).isTrue();
    // four distinct losing accounts → one free → allowed.
    assertThat(model(new WinLoss(0, 4), List.of(loss(1), loss(2), loss(3), loss(4))).scalperEntryAllowed())
        .isTrue();
    // all five frozen → blocked.
    assertThat(
            model(new WinLoss(0, 5), List.of(loss(1), loss(2), loss(3), loss(4), loss(5)))
                .scalperEntryAllowed())
        .isFalse();
  }

  @Test
  void multipleLossesOnOneAccountFreezeOnlyThatAccount() {
    // an account with 3 losses is still just ONE frozen account → the other four are free → allowed.
    assertThat(model(new WinLoss(0, 3), List.of(new SubAccountTally(2, 0, 3))).scalperEntryAllowed())
        .isTrue();
  }

  @Test
  void onceALedgerTradeExistsNullIdxLossesAreInvisible() {
    // 5 NULL-idx losses would block via the fallback — but a single ledger WIN on idx 1 makes the
    // per-account path active, and the NULL-idx losses are then invisible (only idx 1 is tallied, a
    // win, not frozen) → allowed. winLossOn still reports the aggregate (1 win, 5 losses).
    assertThat(model(new WinLoss(1, 5), List.of(new SubAccountTally(1, 1, 0))).scalperEntryAllowed())
        .isTrue();
  }

  @Test
  void profitBankedAccountFreezesLikeALoss() {
    // 4 loss-frozen + 1 profit-banked (≥1% of its capital) = all 5 frozen ⇒ blocked, with only 2
    // aggregate wins (under the 5-win cap) and 4 losses (under the legacy count) — only the
    // profit-lock makes the 5th. Without it, account 5 (a win, no loss) would be free → allowed.
    assertThat(
            model(new WinLoss(2, 4), List.of(loss(1), loss(2), loss(3), loss(4), banked(5, 2)))
                .scalperEntryAllowed())
        .isFalse();
  }

  @Test
  void aWinUnderTheOnePercentTargetDoesNotFreeze() {
    // an account up on the day but below the ~1% target is NOT banked — four losses + it = 4 frozen.
    assertThat(
            model(
                    new WinLoss(1, 4),
                    List.of(loss(1), loss(2), loss(3), loss(4), new SubAccountTally(5, 1, 0, UNDER_TARGET)))
                .scalperEntryAllowed())
        .isTrue();
  }

  @Test
  void nextFreeAccountSkipsAProfitBankedAccount() {
    // account 1 banked its ~1% → routing skips it even though it carries no loss and the fewest trades.
    assertThat(model(new WinLoss(2, 0), List.of(banked(1, 2))).nextFreeAccount()).isEqualTo(2);
  }

  @Test
  void nextFreeAccountLoadBalancesOverNonFrozenAccounts() {
    // empty ledger → account 1 (lowest idx, all equal).
    assertThat(model(new WinLoss(0, 0), List.of()).nextFreeAccount()).isEqualTo(1);
    // account 1 carries a win (1 trade), the rest 0 → next is account 2 (fewest trades, lowest idx).
    assertThat(model(new WinLoss(1, 0), List.of(new SubAccountTally(1, 1, 0))).nextFreeAccount())
        .isEqualTo(2);
    // accounts 1 and 2 frozen on a loss → the next free is account 3.
    assertThat(model(new WinLoss(0, 2), List.of(loss(1), loss(2))).nextFreeAccount()).isEqualTo(3);
  }

  @Test
  void nextFreeAccountFallsBackToOneWhenAllFrozen() {
    // the entry gate normally blocks first, but a manually-taken signal can bypass it.
    assertThat(
            model(new WinLoss(0, 5), List.of(loss(1), loss(2), loss(3), loss(4), loss(5)))
                .nextFreeAccount())
        .isEqualTo(1);
  }
}
