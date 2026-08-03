package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
 * anywhere surfacing that condition. {@code mark}/{@code unrealized} stay exactly {@code null} as
 * before (asserted below) — this is CHARACTERIZATION + VISIBILITY, not a fix.
 *
 * <p><b>Design history (two review rounds, both against the SAME metric):</b>
 * <ul>
 *   <li>Round 1 found a per-read {@code Counter} measured UI poll frequency (5s intervals,
 *       frontend-react's {@code MTM_REFETCH_MS}), not the blind-position count.
 *   <li>Round 2 found the round-1 FIX (a transition-tracked {@code Set<Long>}) could permanently
 *       retain a closed position (a close racing a stale DTO read could re-add an id AFTER the
 *       close's own removal — thread-safe is not the same as correctly ORDERED), was never purged
 *       on {@link PaperService#reset}, and started EMPTY after a restart (under-reporting until
 *       every position was re-observed).
 * </ul>
 *
 * <p>The gauge is now DERIVED, not tracked: {@code PaperService.countMtmBlindPositions()} queries
 * {@code positions.listOpen()} + {@code lastTick.lastPrice} directly on every read, with NO
 * intermediate state to race, purge, or rebuild. The three tests below pin exactly the three holes
 * round 2 found, proving the derivation is immune to each: {@link
 * #aCloseRacingAStaleDetailReadNeverCorruptsTheGauge}, {@link
 * #resetNeedsNoExplicitGaugeCleanupBecauseTheGaugeFollowsTheRepository}, and {@link
 * #aFreshInstanceAfterARestartReportsCorrectlyOnItsVeryFirstRead}.
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
    stubListOpen(positions, List.of(openRow(1L)));
    when(positions.findDetail(1L)).thenReturn(Optional.of(openDetailRow(1L)));
    return positions;
  }

  /**
   * {@code listOpen()} (used by the gauge) and {@code listOpen(String)} (used by {@code
   * openPositions}) are separate overloads on the mock — production delegates one to the other,
   * but a mock does not, so both must be stubbed to the SAME state to keep a test internally
   * consistent.
   */
  private static void stubListOpen(PaperPositionRepository positions, List<PositionRow> rows) {
    when(positions.listOpen()).thenReturn(rows);
    when(positions.listOpen(anyString())).thenReturn(rows);
  }

  private static PaperService harness(
      SimpleMeterRegistry meters, LastTickReader lastTick, PaperPositionRepository positions) {
    InstrumentMetaClient instruments = mock(InstrumentMetaClient.class);
    when(instruments.meta(anyString(), anyString()))
        .thenReturn(
            new InstrumentMetaClient.InstrumentMeta(
                in.arthayantra.strategyengine.fills.InstrumentClass.EQUITY, new BigDecimal("0.05"), 1));

    return new PaperService(
        mock(PaperOrderRepository.class), mock(PaperPositionLotRepository.class), positions,
        new PaperFillService(), lastTick, instruments,
        mock(in.arthayantra.strategysignal.signals.SignalRepository.class),
        mock(PaperAccountService.class), mock(BookResolver.class), mock(RiskService.class),
        mock(ScalperAccountModel.class),
        mock(org.springframework.context.ApplicationEventPublisher.class),
        mock(PaperStaleTickAlerter.class), mock(PaperOrderRejectionRecorder.class),
        new ManasGoverningStopCache(),
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
  void openPositionsLeavesMarkNullAndTheGaugeSeesTheBlindPositionWhenNoTickExists() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    PaperService paper = harness(meters, noTick());

    List<PaperService.PositionDto> open = paper.openPositions("swing-minervini");

    assertThat(open).hasSize(1);
    assertThat(open.get(0).markPrice()).as("unchanged: still null, never fabricated").isNull();
    assertThat(open.get(0).unrealizedPnl()).as("unchanged: still null, never fabricated").isNull();
    assertThat(gaugeValue(meters)).isEqualTo(1.0);
  }

  @Test
  void positionDetailLeavesMarkNullAndTheGaugeSeesTheBlindPositionWhenNoTickExists() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    PaperService paper = harness(meters, noTick());

    PaperService.PositionDetail detail = paper.positionDetail(1L);

    assertThat(detail.markPrice()).as("unchanged: still null, never fabricated").isNull();
    assertThat(detail.unrealizedPnl()).as("unchanged: still null, never fabricated").isNull();
    assertThat(gaugeValue(meters)).isEqualTo(1.0);
  }

  /**
   * Repeated 5-second-interval polling (the original round-1 defect) cannot inflate a DERIVED
   * value by construction — there is no accumulator to increment. Kept as an explicit regression
   * pin rather than relying on that being "obviously true".
   */
  @Test
  void repeatedPollingOfTheSameBlindPositionNeverMovesTheGauge() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    PaperService paper = harness(meters, noTick());

    for (int poll = 0; poll < 6; poll++) {
      paper.openPositions("swing-minervini");
      paper.positionDetail(1L);
    }

    assertThat(gaugeValue(meters))
        .as("6 poll cycles of the SAME blind position must still read as exactly one blind position")
        .isEqualTo(1.0);
  }

  @Test
  void aTickingPositionNeverAppearsInTheGauge() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    PaperService paper = harness(meters, ticking(new BigDecimal("2600.00")));

    List<PaperService.PositionDto> open = paper.openPositions("swing-minervini");
    paper.positionDetail(1L);

    assertThat(open.get(0).markPrice()).isEqualByComparingTo("2600.00");
    assertThat(open.get(0).unrealizedPnl()).as("(2600-2500)*10").isEqualByComparingTo("1000.00");
    assertThat(gaugeValue(meters)).as("a real tick must never register in the MTM-blind gauge").isZero();
  }

  @Test
  void aPositionThatResolvesATickNoLongerReadsAsBlind() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    PaperPositionRepository positions = mock(PaperPositionRepository.class);
    LastTickReader lastTick = mock(LastTickReader.class);
    // first the tick is missing (blind); then a real tick has since arrived (resolved) — the
    // gauge's OWN query (via listOpen + lastTick) picks this up on its very next read, with no
    // stored state to update.
    when(lastTick.lastPrice(anyString(), anyString()))
        .thenReturn(Optional.empty(), Optional.of(new BigDecimal("2600.00")));
    when(positions.listOpen()).thenReturn(List.of(openRow(1L)));
    PaperService paper = harness(meters, lastTick, positions);

    assertThat(gaugeValue(meters)).as("blind on the first gauge read").isEqualTo(1.0);
    assertThat(gaugeValue(meters)).as("resolved on the second gauge read").isZero();
  }

  /**
   * Round-2 finding 1 (the race): {@code positionDetail} can observe a STALE snapshot — OPEN and
   * blind — an instant before a concurrent settlement actually closes the position. Under the
   * OLD (Set-tracked) design that stale observation would re-add the id to shared state AFTER the
   * close's own removal, permanently stranding it. Under the DERIVED design there is no shared
   * state for the stale read to corrupt: {@code positionDetail} (via {@code findDetail}) and the
   * gauge (via {@code listOpen}) are two INDEPENDENT queries. This test drives exactly that
   * ordering — the stale, still-OPEN {@code positionDetail} read happens FIRST, then the gauge is
   * read against a repository that ALREADY reflects the close ({@code listOpen} returns empty) —
   * and proves the earlier stale read left no trace.
   */
  @Test
  void aCloseRacingAStaleDetailReadNeverCorruptsTheGauge() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    PaperPositionRepository positions = mock(PaperPositionRepository.class);
    // findDetail keeps returning the STALE, still-OPEN snapshot (as if the read started just
    // before the close committed); listOpen already reflects the close (empty) — the two queries
    // are allowed to disagree for an instant, which is exactly the race.
    when(positions.findDetail(1L)).thenReturn(Optional.of(openDetailRow(1L)));
    when(positions.listOpen()).thenReturn(List.of());
    PaperService paper = harness(meters, noTick(), positions);

    PaperService.PositionDetail detail = paper.positionDetail(1L);
    assertThat(detail.markPrice()).as("the stale read still correctly reports blind").isNull();

    assertThat(gaugeValue(meters))
        .as("the gauge is queried independently from listOpen — the stale positionDetail read"
            + " a moment earlier left it at zero, exactly matching the repository's CURRENT state")
        .isZero();
  }

  /**
   * Round-2 finding 2 (reset): {@code PaperPositionRepository.deleteAll} has no matching cleanup
   * for any PaperService-side bookkeeping — because the derived design keeps none, none is needed.
   * Calling {@link PaperService#reset} and then re-querying the gauge must show zero as soon as
   * the repository itself reflects the deletion, with no explicit reconciliation step in between.
   */
  @Test
  void resetNeedsNoExplicitGaugeCleanupBecauseTheGaugeFollowsTheRepository() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    PaperPositionRepository positions = positionsRepo();
    PaperService paper = harness(meters, noTick(), positions);
    assertThat(gaugeValue(meters)).as("blind before reset").isEqualTo(1.0);

    paper.reset(null, true);
    // Mocks have no real backing store, so this re-stub stands in for what deleteAll() actually
    // does in production: the SAME repository, queried again, now reflects zero open positions.
    // No PaperService method needs to be told about the reset for the gauge to catch up.
    when(positions.listOpen()).thenReturn(List.of());

    assertThat(gaugeValue(meters)).as("zero immediately after reset, no cleanup call needed").isZero();
  }

  /**
   * Round-2 finding 3 (restart): a transition-tracked Set starts EMPTY on every fresh instance,
   * under-reporting until each open position was freshly re-observed. A derived value has no such
   * cold-start gap — the VERY FIRST gauge read after construction (no prior {@code
   * positionDetail}/{@code openPositions} call at all, simulating a freshly restarted process
   * whose DB rows and Redis tick state both survived the restart) must already be correct.
   */
  @Test
  void aFreshInstanceAfterARestartReportsCorrectlyOnItsVeryFirstRead() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    // A position that was ALREADY open and blind before the (simulated) restart — nothing in this
    // test ever calls positionDetail/openPositions to "warm" any state.
    PaperService paper = harness(meters, noTick());

    assertThat(gaugeValue(meters))
        .as("the first-ever gauge read on a fresh instance must already reflect DB truth")
        .isEqualTo(1.0);
  }

  @Test
  void closingAPreviouslyBlindPositionRemovesItFromTheGauge() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    PaperPositionRepository positions = positionsRepo();
    when(positions.close(anyLong(), any(), anyString())).thenReturn(1); // wins the CAS
    PaperService paper = harness(meters, noTick(), positions);

    assertThat(gaugeValue(meters)).as("blind while OPEN").isEqualTo(1.0);

    paper.settle(openRow(1L), new BigDecimal("2600.00"), "TEST_CLOSE");
    // Mirrors the reset test: settle() calls positions.close(), which in production also removes
    // the row from listOpen()'s result set. The mock re-stub stands in for that real DB effect.
    when(positions.listOpen()).thenReturn(List.of());

    assertThat(gaugeValue(meters)).as("a closed position can never be MTM-blind again").isZero();
  }
}
