package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategysignal.notifier.NotifierClient;
import in.arthayantra.strategysignal.paper.GraduationService.GraduationBoard;
import in.arthayantra.strategysignal.paper.GraduationService.StrategyGraduation;
import in.arthayantra.strategysignal.paper.GraduationService.Thresholds;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit coverage for the F7 promotion decision (the four-criterion bar) + the Sharpe helper. */
class GraduationPromotionServiceTest {

  private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  // pnls with a high, positive Sharpe (mean 4 / stddev 2 = 2.0 ≥ 0.5 floor)
  private static final List<BigDecimal> STRONG =
      List.of(new BigDecimal("2"), new BigDecimal("4"), new BigDecimal("6"));
  // zero-mean, high-variance → Sharpe 0.0 < floor
  private static final List<BigDecimal> WEAK =
      List.of(new BigDecimal("10"), new BigDecimal("-10"));

  private static StrategyGraduation sg(int trades, String expectancy, String maxDdPct) {
    return new StrategyGraduation(
        ID, "scalp-x", "Scalper X", "TAKE_ELIGIBLE", trades, new BigDecimal("100"),
        new BigDecimal("0.55"), new BigDecimal("1.8"), new BigDecimal(expectancy),
        new BigDecimal(maxDdPct), List.of());
  }

  private record Harness(
      GraduationPromotionService svc,
      StrategyGraduationRepository repo,
      NotifierClient notifier) {}

  private static Harness harness(StrategyGraduation row, List<BigDecimal> pnls, Set<UUID> already) {
    GraduationService grad = mock(GraduationService.class);
    StrategyGraduationRepository repo = mock(StrategyGraduationRepository.class);
    NotifierClient notifier = mock(NotifierClient.class);
    when(grad.board())
        .thenReturn(
            new GraduationBoard(
                List.of(row),
                new Thresholds(20, new BigDecimal("1.3"), BigDecimal.ZERO, new BigDecimal("25")),
                OffsetDateTime.now(ZoneOffset.UTC)));
    when(grad.closedPnls(ID)).thenReturn(pnls);
    when(repo.graduatedIds()).thenReturn(already);
    when(notifier.configured("NTFY")).thenReturn(true);
    GraduationPromotionService svc =
        new GraduationPromotionService(
            grad, repo, notifier, new ObjectMapper(), 50, new BigDecimal("0.5"), BigDecimal.ZERO,
            new BigDecimal("25"));
    return new Harness(svc, repo, notifier);
  }

  @Test
  void qualifyingNewStrategyGraduatesAndAlerts() {
    Harness h = harness(sg(60, "5", "10"), STRONG, Set.of());
    GraduationPromotionService.PromotionResult r = h.svc().evaluate();
    assertThat(r.graduated()).isEqualTo(1);
    assertThat(r.newlyGraduated()).containsExactly("scalp-x");
    verify(h.repo()).upsert(eq(ID), eq(60), any(), any(), any(), any());
    verify(h.notifier()).send(eq("NTFY"), any(), any());
  }

  @Test
  void alreadyGraduatedUpsertsButDoesNotReAlert() {
    Harness h = harness(sg(60, "5", "10"), STRONG, Set.of(ID));
    GraduationPromotionService.PromotionResult r = h.svc().evaluate();
    assertThat(r.newlyGraduated()).isEmpty();
    verify(h.repo()).upsert(eq(ID), anyInt(), any(), any(), any(), any());
    verify(h.notifier(), never()).send(any(), any(), any());
  }

  @Test
  void belowTradeCountDoesNotGraduate() {
    Harness h = harness(sg(30, "5", "10"), STRONG, Set.of());
    assertThat(h.svc().evaluate().graduated()).isZero();
    verify(h.repo(), never()).upsert(any(), anyInt(), any(), any(), any(), any());
  }

  @Test
  void negativeExpectancyDoesNotGraduate() {
    Harness h = harness(sg(60, "-1", "10"), STRONG, Set.of());
    assertThat(h.svc().evaluate().graduated()).isZero();
  }

  @Test
  void excessiveDrawdownDoesNotGraduate() {
    Harness h = harness(sg(60, "5", "40"), STRONG, Set.of()); // 40% > 25% cap
    assertThat(h.svc().evaluate().graduated()).isZero();
  }

  @Test
  void weakSharpeDoesNotGraduate() {
    Harness h = harness(sg(60, "5", "10"), WEAK, Set.of()); // Sharpe 0 < 0.5 floor
    assertThat(h.svc().evaluate().graduated()).isZero();
  }

  @Test
  void sharpeIsMeanOverSampleStddev() {
    assertThat(GraduationPromotionService.sharpe(STRONG)).isEqualByComparingTo("2.0000");
    assertThat(GraduationPromotionService.sharpe(List.of(new BigDecimal("5")))).isNull(); // n<2
    assertThat(
            GraduationPromotionService.sharpe(
                List.of(new BigDecimal("5"), new BigDecimal("5"), new BigDecimal("5"))))
        .isNull(); // zero variance
  }
}
