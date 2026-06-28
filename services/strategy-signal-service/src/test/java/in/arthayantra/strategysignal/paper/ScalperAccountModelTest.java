package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.arthayantra.strategysignal.paper.PaperPositionRepository.SubAccountTally;
import in.arthayantra.strategysignal.paper.PaperPositionRepository.WinLoss;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
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

  private static ScalperAccountModel model(WinLoss wl, List<SubAccountTally> tallies) {
    PaperPositionRepository repo = mock(PaperPositionRepository.class);
    when(repo.winLossOn(any())).thenReturn(wl);
    when(repo.subAccountTalliesOn(any())).thenReturn(tallies);
    return new ScalperAccountModel(repo, CLOCK);
  }

  private static SubAccountTally loss(int idx) {
    return new SubAccountTally(idx, 0, 1);
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
}
