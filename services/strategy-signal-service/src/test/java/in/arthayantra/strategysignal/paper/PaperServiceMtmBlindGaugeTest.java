package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import in.arthayantra.strategysignal.paper.PaperPositionRepository.DetailRow;
import in.arthayantra.strategysignal.paper.PaperPositionRepository.PositionRow;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * M4 (#128 batch scoping): "swing book MTM blind — non-ticking equities mark at cost." Confirmed
 * still true against {@code PaperService.positionDetail}/{@code toPositionDto}: an OPEN position
 * with no live tick (structurally every swing/funnel equity — the live feed is index/options only)
 * leaves {@code mark}/{@code unrealized} {@code null} for its ENTIRE holding period, with nothing
 * anywhere surfacing that condition.
 *
 * <p>This is a CHARACTERIZATION + VISIBILITY addition, not a fix: {@code mark}/{@code unrealized}
 * stay exactly {@code null} as before (asserted below).
 *
 * <p><b>Corrected 2026-08-02 (cross-vendor review) from an earlier Counter-based design</b>: {@code
 * positionDetail}/{@code openPositions} are read paths the UI polls every 5 seconds
 * (frontend-react/src/api/paper.ts, {@code MTM_REFETCH_MS}), so a per-read {@code Counter} measured
 * POLL FREQUENCY, not the thing observed — one blind position produced ~720 increments/hour with a
 * tab open and zero with it closed. {@code ay_paper_mtm_blind_positions} is now a GAUGE over a
 * transition-tracked {@code Set} of position ids (the {@code
 * PaperReconciliationService.deadAnchorOrphanGauge} idiom for persistent paper-ledger state): its
 * size is "how many positions are blind RIGHT NOW", mutated only when a position's blind status
 * actually CHANGES (first blind / resolves a tick / closes) — {@link
 * #repeatedPollingOfTheSameBlindPositionNeverInflatesTheGauge} is the test that pins this directly.
 *
 * <p>Traced separately (not assumed): {@code RiskService.entryVeto}'s daily-loss/heat-cap checks
 * read {@code PaperAccountService.unrealizedTotal}, which computes its OWN independent
 * mark-or-avgEntryPrice fallback and never calls {@code PaperService}'s DTO methods at all — so
 * this gauge cannot touch risk sizing either way.
 */
class PaperServiceMtmBlindGaugeTest {

  private static final String GAUGE_NAME = "ay_paper_mtm_blind_positions";

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

  private static PaperPositionRepository positionsRepo() {
    PaperPositionRepository positions = mock(PaperPositionRepository.class);
    when(positions.listOpen(anyString())).thenReturn(List.of(openRow(1L)));
    when(positions.findDetail(1L)).thenReturn(Optional.of(openDetailRow(1L)));
    return positions;
  }

  private static PaperService harness(
      SimpleMeterRegistry meters, LastTickReader lastTick, PaperPositionRepository positions) {
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

  private static PaperService harness(SimpleMeterRegistry meters, LastTickReader lastTick) {
    return harness(meters, lastTick, positionsRepo());
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

  private static double gaugeValue(SimpleMeterRegistry meters) {
    return meters.get(GAUGE_NAME).gauge().value();
  }

  @Test
  void openPositionsLeavesMarkNullAndTracksTheBlindPositionWhenNoTickExists() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    PaperService paper = harness(meters, noTick());

    List<PaperService.PositionDto> open = paper.openPositions("swing-minervini");

    assertThat(open).hasSize(1);
    assertThat(open.get(0).markPrice()).as("unchanged: still null, never fabricated").isNull();
    assertThat(open.get(0).unrealizedPnl()).as("unchanged: still null, never fabricated").isNull();
    assertThat(gaugeValue(meters)).isEqualTo(1.0);
  }

  @Test
  void positionDetailLeavesMarkNullAndTracksTheBlindPositionWhenNoTickExists() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    PaperService paper = harness(meters, noTick());

    PaperService.PositionDetail detail = paper.positionDetail(1L);

    assertThat(detail.markPrice()).as("unchanged: still null, never fabricated").isNull();
    assertThat(detail.unrealizedPnl()).as("unchanged: still null, never fabricated").isNull();
    assertThat(gaugeValue(meters)).isEqualTo(1.0);
  }

  /**
   * The core fix under review: 5-second UI polling of an already-known-blind position must not
   * inflate the gauge NOR spam the log. Simulates 6 poll cycles (list + detail, matching the two
   * endpoints frontend-react polls on the same {@code MTM_REFETCH_MS} interval) — the gauge must
   * read exactly 1 throughout (never grow to 6 or 12), and the WARN must fire exactly ONCE (the
   * transition into blind), not once per poll (12 calls total across both endpoints).
   */
  @Test
  void repeatedPollingOfTheSameBlindPositionNeverInflatesTheGaugeOrSpamsTheLog() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    PaperService paper = harness(meters, noTick());
    Logger paperLog = (Logger) LoggerFactory.getLogger(PaperService.class);
    ListAppender<ILoggingEvent> logs = new ListAppender<>();
    logs.start();
    paperLog.addAppender(logs);

    try {
      for (int poll = 0; poll < 6; poll++) {
        paper.openPositions("swing-minervini");
        paper.positionDetail(1L);
      }
    } finally {
      paperLog.detachAppender(logs);
    }

    assertThat(gaugeValue(meters))
        .as("6 poll cycles of the SAME blind position must still read as exactly one blind position")
        .isEqualTo(1.0);
    long mtmBlindWarns =
        logs.list.stream()
            .filter(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains("MTM blind"))
            .count();
    assertThat(mtmBlindWarns)
        .as("12 read calls (6 list + 6 detail) on an already-known-blind position must log ONCE,"
            + " on the transition, not once per read")
        .isEqualTo(1);
  }

  @Test
  void aTickingPositionNeverAppearsInTheGauge() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    PaperService paper = harness(meters, ticking(new BigDecimal("2600.00")));

    List<PaperService.PositionDto> open = paper.openPositions("swing-minervini");
    paper.positionDetail(1L);

    assertThat(open.get(0).markPrice()).isEqualByComparingTo("2600.00");
    assertThat(open.get(0).unrealizedPnl()).as("(2600-2500)*10").isEqualByComparingTo("1000.00");
    assertThat(gaugeValue(meters))
        .as("a real tick must never register in the MTM-blind gauge")
        .isZero();
  }

  @Test
  void aPositionThatResolvesATickIsRemovedFromTheGauge() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    LastTickReader lastTick = mock(LastTickReader.class);
    // first read: no tick yet (blind); second read: a real tick has since arrived (resolved).
    when(lastTick.lastPrice(anyString(), anyString()))
        .thenReturn(Optional.empty(), Optional.of(new BigDecimal("2600.00")));
    PaperService paper = harness(meters, lastTick);

    paper.positionDetail(1L);
    assertThat(gaugeValue(meters)).as("blind on the first read").isEqualTo(1.0);

    paper.positionDetail(1L);
    assertThat(gaugeValue(meters)).as("resolved on the second read — removed from the gauge").isZero();
  }

  @Test
  void closingAPreviouslyBlindPositionRemovesItFromTheGauge() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    PaperPositionRepository positions = positionsRepo();
    when(positions.close(anyLong(), any(), anyString())).thenReturn(1); // wins the CAS
    PaperService paper = harness(meters, noTick(), positions);

    paper.positionDetail(1L);
    assertThat(gaugeValue(meters)).as("blind while OPEN").isEqualTo(1.0);

    paper.settle(openRow(1L), new BigDecimal("2600.00"), "TEST_CLOSE");

    assertThat(gaugeValue(meters))
        .as("a closed position can never be MTM-blind again — must not linger in the gauge")
        .isZero();
  }
}
