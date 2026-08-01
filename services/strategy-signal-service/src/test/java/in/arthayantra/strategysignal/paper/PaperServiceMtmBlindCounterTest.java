package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.arthayantra.strategysignal.paper.PaperPositionRepository.DetailRow;
import in.arthayantra.strategysignal.paper.PaperPositionRepository.PositionRow;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * M4 (#128 batch scoping): "swing book MTM blind — non-ticking equities mark at cost." Confirmed
 * still true against {@code PaperService.positionDetail}/{@code toPositionDto}: an OPEN position
 * with no live tick (structurally every swing/funnel equity — the live feed is index/options only)
 * leaves {@code mark}/{@code unrealized} {@code null} for its ENTIRE holding period, with nothing
 * anywhere surfacing that condition.
 *
 * <p>This is a CHARACTERIZATION + VISIBILITY addition, not a fix: {@code mark}/{@code unrealized}
 * stay exactly {@code null} as before (asserted below) — only a new {@code ay_paper_mtm_blind_total}
 * counter + a loud log line are added, so the owner has real numbers on how often/how many positions
 * are "MTM blind" before deciding (per the scoping doc's own open question) whether a future
 * daily-close fallback should stay display-only or feed risk sizing. Traced separately (not
 * assumed): {@code RiskService.entryVeto}'s daily-loss/heat-cap checks read
 * {@code PaperAccountService.unrealizedTotal}, which computes its OWN independent
 * mark-or-avgEntryPrice fallback and never calls {@code PaperService}'s DTO methods at all — so
 * this counter cannot touch risk sizing either way.
 */
class PaperServiceMtmBlindCounterTest {

  private static final String COUNTER_NAME = "ay_paper_mtm_blind_total";

  private static PositionRow openRow(long id) {
    return new PositionRow(
        id, "NSE", "RELIANCE", "BUY", 10, new BigDecimal("2500.00"), BigDecimal.ZERO, "OPEN",
        null, null, null, null, null, "swing-minervini");
  }

  private static DetailRow openDetailRow(long id) {
    return new DetailRow(
        id, "NSE", "RELIANCE", "BUY", 10, new BigDecimal("2500.00"), BigDecimal.ZERO, "OPEN",
        null, null, null, null, null, "swing-minervini", null, null, null, null, null);
  }

  private static PaperService harness(SimpleMeterRegistry meters, LastTickReader lastTick) {
    PaperPositionRepository positions = mock(PaperPositionRepository.class);
    when(positions.listOpen(anyString())).thenReturn(List.of(openRow(1L)));
    when(positions.findDetail(1L)).thenReturn(Optional.of(openDetailRow(1L)));

    InstrumentMetaClient instruments = mock(InstrumentMetaClient.class);
    when(instruments.meta(anyString(), anyString()))
        .thenReturn(
            new InstrumentMetaClient.InstrumentMeta(
                in.arthayantra.strategyengine.fills.InstrumentClass.EQUITY, new BigDecimal("0.05"), 1));

    return new PaperService(
        mock(PaperOrderRepository.class), positions, new PaperFillService(), lastTick, instruments,
        mock(in.arthayantra.strategysignal.signals.SignalRepository.class),
        mock(PaperAccountService.class), mock(BookResolver.class), mock(RiskService.class),
        mock(ScalperAccountModel.class),
        mock(org.springframework.context.ApplicationEventPublisher.class),
        mock(PaperStaleTickAlerter.class), mock(PaperOrderRejectionRecorder.class),
        mock(org.springframework.transaction.PlatformTransactionManager.class),
        new BigDecimal("1.0"), 15L, 60L, meters);
  }

  private static LastTickReader noTick() {
    LastTickReader lastTick = mock(LastTickReader.class);
    when(lastTick.lastPrice(anyString(), anyString())).thenReturn(Optional.empty());
    return lastTick;
  }

  private static LastTickReader ticking(BigDecimal price) {
    LastTickReader lastTick = mock(LastTickReader.class);
    when(lastTick.lastPrice(anyString(), anyString())).thenReturn(Optional.of(price));
    return lastTick;
  }

  @Test
  void openPositionsLeavesMarkNullAndCountsTheBlindConditionWhenNoTickExists() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    PaperService paper = harness(meters, noTick());

    List<PaperService.PositionDto> open = paper.openPositions("swing-minervini");

    assertThat(open).hasSize(1);
    assertThat(open.get(0).markPrice()).as("unchanged: still null, never fabricated").isNull();
    assertThat(open.get(0).unrealizedPnl()).as("unchanged: still null, never fabricated").isNull();
    assertThat(meters.counter(COUNTER_NAME).count()).isEqualTo(1.0);
  }

  @Test
  void positionDetailLeavesMarkNullAndCountsTheBlindConditionWhenNoTickExists() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    PaperService paper = harness(meters, noTick());

    PaperService.PositionDetail detail = paper.positionDetail(1L);

    assertThat(detail.markPrice()).as("unchanged: still null, never fabricated").isNull();
    assertThat(detail.unrealizedPnl()).as("unchanged: still null, never fabricated").isNull();
    assertThat(meters.counter(COUNTER_NAME).count()).isEqualTo(1.0);
  }

  @Test
  void aTickingPositionNeverIncrementsTheBlindCounter() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    PaperService paper = harness(meters, ticking(new BigDecimal("2600.00")));

    List<PaperService.PositionDto> open = paper.openPositions("swing-minervini");
    paper.positionDetail(1L);

    assertThat(open.get(0).markPrice()).isEqualByComparingTo("2600.00");
    assertThat(open.get(0).unrealizedPnl()).as("(2600-2500)*10").isEqualByComparingTo("1000.00");
    assertThat(meters.counter(COUNTER_NAME).count())
        .as("a real tick must never trip the MTM-blind counter")
        .isZero();
  }
}
