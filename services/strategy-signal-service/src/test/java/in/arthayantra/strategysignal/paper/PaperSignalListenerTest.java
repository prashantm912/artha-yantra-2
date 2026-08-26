package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.strategysignal.signals.SignalRepository;
import in.arthayantra.strategysignal.signals.SignalTaken;
import in.arthayantra.strategysignal.signals.SwingPaperEffectRepository;
import in.arthayantra.strategysignal.swing.SwingBatchRefusalRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** The E10 stamping seam: a scalper take charges the paper open to a round-robin sub-account. */
class PaperSignalListenerTest {

  /** A signal store with no straddle detail → the single-leg open path (the legacy behaviour). */
  private static SignalRepository noStraddle() {
    SignalRepository signals = mock(SignalRepository.class);
    when(signals.find(anyLong())).thenReturn(Optional.empty());
    return signals;
  }

  @Test
  void aStraddleTakeOpensBothLegsThroughTheAtomicAssigningPair() {
    // #1075 cross-vendor round 5. Every atomicity/assignment test so far called PaperService directly,
    // and every listener test used a no-straddle repository — so the PRODUCTION seam (parse the
    // scalper_detail legs[], then route to openScalperPair) was never executed. That branch could have
    // bypassed atomicity or locked assignment with the whole suite green.
    //
    // Production-shaped NEUTRAL detail with both ATM legs. Asserts ONE openScalperPair call (not two
    // openOrder calls), CE and PE both present, and both legs at the SAME combined-premium quantity.
    PaperService paper = mock(PaperService.class);
    ScalperAccountModel accounts = mock(ScalperAccountModel.class);
    SignalRepository signals = mock(SignalRepository.class);
    InstrumentMetaClient instruments = mock(InstrumentMetaClient.class);
    when(instruments.meta(anyString(), anyString()))
        .thenReturn(
            new InstrumentMetaClient.InstrumentMeta(
                in.arthayantra.strategyengine.fills.InstrumentClass.OPTION, new BigDecimal("0.05"), 75));
    when(signals.find(anyLong())).thenReturn(Optional.of(straddleRow()));

    new PaperSignalListener(paper, accounts, signals, null, instruments)
        .onSignalTaken(new SignalTaken(7L, 75, new BigDecimal("25000"), true));

    ArgumentCaptor<PaperService.OrderRequest> ce =
        ArgumentCaptor.forClass(PaperService.OrderRequest.class);
    ArgumentCaptor<PaperService.OrderRequest> pe =
        ArgumentCaptor.forClass(PaperService.OrderRequest.class);
    verify(paper).openScalperPair(ce.capture(), pe.capture());
    verify(paper, never()).openOrder(any());
    verify(paper, never()).openScalperOrder(any());
    assertThat(ce.getValue().tradingsymbol()).isEqualTo("NIFTY26JUL24000CE");
    assertThat(pe.getValue().tradingsymbol()).isEqualTo("NIFTY26JUL24000PE");
    // both legs carry the SAME combined-premium quantity, and a whole number of 75-lots
    assertThat(pe.getValue().qty()).isEqualTo(ce.getValue().qty());
    assertThat(ce.getValue().qty() % 75).isZero();
    assertThat(ce.getValue().qty()).isPositive();
    // the listener leaves the sub-account NULL — openScalperPair assigns it under the book lock
    assertThat(ce.getValue().subaccountIdx()).isNull();
    assertThat(pe.getValue().subaccountIdx()).isNull();
  }

  /** A production-shaped NEUTRAL straddle row: both ATM legs in {@code scalper_detail.legs[]}. */
  private static SignalRepository.SignalRow straddleRow() {
    com.fasterxml.jackson.databind.JsonNode detail;
    try {
      detail =
          new com.fasterxml.jackson.databind.ObjectMapper()
              .readTree(
                  "{\"side\":\"NEUTRAL\",\"legs\":["
                      + "{\"exchange\":\"NFO\",\"tradingsymbol\":\"NIFTY26JUL24000CE\","
                      + "\"option_type\":\"CE\",\"option_ltp\":\"100.00\"},"
                      + "{\"exchange\":\"NFO\",\"tradingsymbol\":\"NIFTY26JUL24000PE\","
                      + "\"option_type\":\"PE\",\"option_ltp\":\"100.00\"}]}");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    return new SignalRepository.SignalRow(
        7L, java.util.UUID.randomUUID(), "NFO", "NIFTY26JULFUT", "3m", "ENTRY", "BUY",
        new BigDecimal("25000"), new BigDecimal("24800"), new BigDecimal("25400"),
        new BigDecimal("0.8"), null, "TAKEN", null, null, new BigDecimal("75"),
        "NFO", "NIFTY26JUL24000CE", detail, null, null, null);
  }

  private static ArgumentCaptor<PaperService.OrderRequest> openedWith(
      PaperService paper, ScalperAccountModel accounts, SignalTaken event) {
    new PaperSignalListener(paper, accounts, noStraddle()).onSignalTaken(event);
    ArgumentCaptor<PaperService.OrderRequest> req =
        ArgumentCaptor.forClass(PaperService.OrderRequest.class);
    // A scalper entry routes through openScalperOrder, which picks its sub-account under the book
    // lock that also validates and writes it; every other take opens through openOrder unchanged.
    if (event.scalper()) {
      verify(paper).openScalperOrder(req.capture());
    } else {
      verify(paper).openOrder(req.capture());
    }
    return req;
  }

  @Test
  void aScalperTakeRoutesThroughTheAssigningOpenAndDoesNotPickTheAccountItself() {
    // The listener no longer picks the sub-account. Picking out here read capital that a concurrent
    // take was about to claim, so both chose account 1 and the second was refused at its ceiling
    // instead of routed to an idle account (cross-vendor round 4). Assignment now happens inside
    // PaperService.openScalperOrder, under the same book lock that validates and writes it — so the
    // request leaves here with a NULL key and the listener never calls nextFreeAccount.
    PaperService paper = mock(PaperService.class);
    ScalperAccountModel accounts = mock(ScalperAccountModel.class);
    ArgumentCaptor<PaperService.OrderRequest> req =
        openedWith(paper, accounts, new SignalTaken(7L, 50, new BigDecimal("100"), true));
    assertThat(req.getValue().subaccountIdx()).isNull();
    verify(accounts, never()).nextFreeAccount();
  }

  @Test
  void aNonScalperTakeLeavesTheSubAccountUnstamped() {
    PaperService paper = mock(PaperService.class);
    ScalperAccountModel accounts = mock(ScalperAccountModel.class);
    ArgumentCaptor<PaperService.OrderRequest> req =
        openedWith(paper, accounts, new SignalTaken(7L, 50, new BigDecimal("100"), false));
    assertThat(req.getValue().subaccountIdx()).isNull();
    verify(accounts, never()).nextFreeAccount();
  }

  @Test
  void noQtyOpensNothing() {
    PaperService paper = mock(PaperService.class);
    ScalperAccountModel accounts = mock(ScalperAccountModel.class);
    new PaperSignalListener(paper, accounts, noStraddle()).onSignalTaken(new SignalTaken(7L, null, null, true));
    verify(paper, never()).openOrder(any());
  }

  @Test
  void aRetryAfterAnOpenWasAppliedDoesNotAverageThePositionAgain() {
    PaperService paper = mock(PaperService.class);
    ScalperAccountModel accounts = mock(ScalperAccountModel.class);
    SwingPaperEffectRepository effects = mock(SwingPaperEffectRepository.class);
    SwingPaperEffectRepository.Effect effect = mock(SwingPaperEffectRepository.Effect.class);
    when(effect.id()).thenReturn(99L);
    when(effect.expectedQty()).thenReturn(5L);
    when(effect.quantityBefore()).thenReturn(10L);
    when(effect.decision()).thenReturn("REQUIRED");
    when(effects.findOpenBySignal(7L)).thenReturn(Optional.of(effect));
    when(effects.claimOpen(eq(99L), anyLong(), anyInt())).thenReturn(Optional.of(effect));
    when(paper.openQuantityForSignal(7L)).thenReturn(10L, 10L, 20L);
    when(paper.openOrder(any())).thenThrow(new RuntimeException("failure after the fill commit"));

    PaperSignalListener listener = new PaperSignalListener(paper, accounts, noStraddle(), effects);
    SignalTaken event = new SignalTaken(7L, 5, new BigDecimal("100"), false);
    listener.onSignalTaken(event);
    listener.onSignalTaken(event); // stale-claim repair sees the already-applied size

    verify(paper).openOrder(any());
    verify(effects).confirm(99L);
  }

  // ---------------------------------------------------------------------------------------------
  // H22: a PERMANENT risk-governor refusal is not a transient fault.
  //
  // Measured live 2026-08-17 (manas-arora / 2026-08-13): swing_paper_effects id=19
  // (ENTRY:SALSTEEL, expected_qty=206, signal_id=193) sat CLAIMED with attempts=2 and no
  // paper_positions row because the F9 pyramid_risk_cap rail refused the fill -- correctly. The
  // catch treated it like a DB blip, so the session never reached DONE, every sweep re-alerted
  // PAPER EFFECTS UNCONFIRMED, and the attempt budget marched toward an "UNRECOVERABLE" page.
  // ---------------------------------------------------------------------------------------------

  /** The live shape: a governor verdict closes the lease terminally AND leaves durable evidence. */
  @Test
  void aGovernorRefusalResolvesTheEffectTerminallyWithDurableEvidence() {
    PaperService paper = mock(PaperService.class);
    ScalperAccountModel accounts = mock(ScalperAccountModel.class);
    SwingPaperEffectRepository effects = claimingRepository();
    SwingBatchRefusalRepository refusals = mock(SwingBatchRefusalRepository.class);
    when(paper.openQuantityForSignal(SALSTEEL_SIGNAL)).thenReturn(0L);
    when(paper.openOrder(any())).thenThrow(governorRefusal("pyramid_risk_cap"));

    new PaperSignalListener(paper, accounts, noStraddle(), effects, null, refusals)
        .onSignalTaken(new SignalTaken(SALSTEEL_SIGNAL, 206, new BigDecimal("69.38"), false));

    // The reason survives the retry loop that no longer runs: rail-qualified, so two different
    // rails on the same symbol stay separable in swing_batch_refusals.
    verify(refusals)
        .record(
            "manas-arora",
            LocalDate.of(2026, 8, 13),
            "SALSTEEL",
            "RISK_ENTRY_BLOCKED:pyramid_risk_cap");
    verify(effects).refuseEntry(SALSTEEL_EFFECT);
    // no money effect happened, so it must NOT be confirmed as though the entry landed
    verify(effects, never()).confirm(anyLong());
  }

  /** A transient fault keeps today's behaviour EXACTLY: still CLAIMED, still repairable. */
  @Test
  void aTransientFailureIsStillLeftClaimedForTheCatchUpToRepair() {
    PaperService paper = mock(PaperService.class);
    ScalperAccountModel accounts = mock(ScalperAccountModel.class);
    SwingPaperEffectRepository effects = claimingRepository();
    SwingBatchRefusalRepository refusals = mock(SwingBatchRefusalRepository.class);
    when(paper.openQuantityForSignal(SALSTEEL_SIGNAL)).thenReturn(0L);
    when(paper.openOrder(any())).thenThrow(new RuntimeException("connection reset"));

    new PaperSignalListener(paper, accounts, noStraddle(), effects, null, refusals)
        .onSignalTaken(new SignalTaken(SALSTEEL_SIGNAL, 206, new BigDecimal("69.38"), false));

    verify(effects, never()).refuseEntry(anyLong());
    verify(refusals, never()).record(anyString(), any(), anyString(), anyString());
    verify(effects, never()).confirm(anyLong());
  }

  /**
   * The narrow gate, and the reason this keys on the CODE rather than on {@code ApiException}:
   * a stale-tick refusal is a data condition a later replay can genuinely clear, so it must stay
   * repairable even though it arrives as the same exception type from the same call.
   */
  @Test
  void aNonGovernorApiExceptionIsStillTreatedAsTransient() {
    PaperService paper = mock(PaperService.class);
    ScalperAccountModel accounts = mock(ScalperAccountModel.class);
    SwingPaperEffectRepository effects = claimingRepository();
    SwingBatchRefusalRepository refusals = mock(SwingBatchRefusalRepository.class);
    when(paper.openQuantityForSignal(SALSTEEL_SIGNAL)).thenReturn(0L);
    when(paper.openOrder(any()))
        .thenThrow(new ApiException(422, ErrorCodes.DATA_STALE, "tick 41s old"));

    new PaperSignalListener(paper, accounts, noStraddle(), effects, null, refusals)
        .onSignalTaken(new SignalTaken(SALSTEEL_SIGNAL, 206, new BigDecimal("69.38"), false));

    verify(effects, never()).refuseEntry(anyLong());
    verify(refusals, never()).record(anyString(), any(), anyString(), anyString());
  }

  /**
   * The ONE {@code RISK_ENTRY_BLOCKED} rail that stays TRANSIENT (Architect audit, 2026-08-25).
   *
   * <p>{@code manas_risk_uncomputable} does not mean "refused", it means "could not be calculated"
   * — an unsupported side, an undefined governing stop, or non-positive equity. An inability to
   * DECIDE is a fault, not a verdict, and an undefined governing stop is curable by a later replay
   * once the governing-stop cache warms. Wrongly terminal here is a SILENT permanent forfeiture of a
   * real entry; wrongly transient is a loud page — {@code SwingBatchCatchUp:755-756} settled the
   * same trade-off with "A loud unrecoverable beats a silent one."
   *
   * <p>BOTH {@code never()}s are load-bearing: a half-applied change — refusal row written, lease
   * left open — is the worst of both, durable evidence asserting a permanent verdict on a session
   * that still never completes.
   */
  @Test
  void anUncomputableRiskRailStaysTransientAndWritesNoRefusalRow() {
    PaperService paper = mock(PaperService.class);
    ScalperAccountModel accounts = mock(ScalperAccountModel.class);
    SwingPaperEffectRepository effects = claimingRepository();
    SwingBatchRefusalRepository refusals = mock(SwingBatchRefusalRepository.class);
    when(paper.openQuantityForSignal(SALSTEEL_SIGNAL)).thenReturn(0L);
    // The real constant, not a literal: a rename must break this test, not silently widen the set.
    when(paper.openOrder(any()))
        .thenThrow(governorRefusal(RiskService.MANAS_RISK_UNCOMPUTABLE));

    new PaperSignalListener(paper, accounts, noStraddle(), effects, null, refusals)
        .onSignalTaken(new SignalTaken(SALSTEEL_SIGNAL, 206, new BigDecimal("69.38"), false));

    verify(refusals, never()).record(anyString(), any(), anyString(), anyString());
    verify(effects, never()).refuseEntry(anyLong());
    verify(effects, never()).confirm(anyLong());
  }

  /**
   * A RAIL-LESS refusal stays TRANSIENT (Architect audit, 2026-08-25) - shape 1, a details map
   * carrying other keys but no {@code rail}.
   *
   * <p>Every throw site today supplies a rail, so this can only arrive from a FUTURE site added
   * without the detail map. The argument is NOT that closing it would be silent - it would write a
   * real {@code swing_batch_refusals} row. It is that the row would be attributed to nothing
   * reviewable, spending a live entry on the fact that nobody could tell whether this was a policy
   * verdict or an inability. Transient ends at the catch-up's ABANDONED page, and a human is what
   * an unclassified refusal needs.
   */
  @Test
  void aRefusalWithNoRailKeyStaysTransientAndWritesNoRefusalRow() {
    assertTransient(
        new ApiException(
            422,
            ErrorCodes.RISK_ENTRY_BLOCKED,
            "entry blocked by risk governor on book manas-arora",
            Map.of("book", "manas-arora")));
  }

  /**
   * Shape 2: the 3-arg {@link ApiException} constructor, which normalizes absent details to an
   * EMPTY map. Both shapes are reachable; {@code Map.of("rail", null)} is not, because
   * {@code Map.of} rejects null values outright - a mutable map holding a null would take the same
   * branch anyway, since the gate reads {@code get("rail") == null}.
   */
  @Test
  void aRefusalWithNoDetailsAtAllStaysTransientAndWritesNoRefusalRow() {
    assertTransient(
        new ApiException(422, ErrorCodes.RISK_ENTRY_BLOCKED, "entry blocked by risk governor"));
  }

  /**
   * Runs one refusal through the live seam and asserts the TRANSIENT contract: no durable refusal
   * row, no terminal resolve, no confirm. Deliberately asserts rather than arranges - it forces
   * nothing, so a change that made the refusal terminal reddens here.
   */
  private static void assertTransient(ApiException refusal) {
    PaperService paper = mock(PaperService.class);
    ScalperAccountModel accounts = mock(ScalperAccountModel.class);
    SwingPaperEffectRepository effects = claimingRepository();
    SwingBatchRefusalRepository refusals = mock(SwingBatchRefusalRepository.class);
    when(paper.openQuantityForSignal(SALSTEEL_SIGNAL)).thenReturn(0L);
    when(paper.openOrder(any())).thenThrow(refusal);

    new PaperSignalListener(paper, accounts, noStraddle(), effects, null, refusals)
        .onSignalTaken(new SignalTaken(SALSTEEL_SIGNAL, 206, new BigDecimal("69.38"), false));

    verify(refusals, never()).record(anyString(), any(), anyString(), anyString());
    verify(effects, never()).refuseEntry(anyLong());
    verify(effects, never()).confirm(anyLong());
  }

  /**
   * Evidence FIRST. If the refusal cannot be persisted there is nowhere for the reason to live, so
   * closing the row would erase it -- fall back to the transient handling instead.
   */
  @Test
  void aRefusalThatCannotBePersistedLeavesTheEffectClaimed() {
    PaperService paper = mock(PaperService.class);
    ScalperAccountModel accounts = mock(ScalperAccountModel.class);
    SwingPaperEffectRepository effects = claimingRepository();
    SwingBatchRefusalRepository refusals = mock(SwingBatchRefusalRepository.class);
    when(paper.openQuantityForSignal(SALSTEEL_SIGNAL)).thenReturn(0L);
    when(paper.openOrder(any())).thenThrow(governorRefusal("pyramid_risk_cap"));
    org.mockito.Mockito.doThrow(new RuntimeException("ledger write failed"))
        .when(refusals)
        .record(anyString(), any(), anyString(), anyString());

    new PaperSignalListener(paper, accounts, noStraddle(), effects, null, refusals)
        .onSignalTaken(new SignalTaken(SALSTEEL_SIGNAL, 206, new BigDecimal("69.38"), false));

    verify(effects, never()).refuseEntry(anyLong());
    verify(effects, never()).confirm(anyLong());
  }


  // ---------------------------------------------------------------------------------------------
  // H43: a swallowed paper-open failure used to STRAND the TAKEN anchor forever.
  //
  // The ACTIVE->TAKEN CAS commits one frame up, so any throw caught by onSignalTaken left the
  // signal TAKEN with no order and no position. SignalRepository.activeEntry anchors
  // status IN ('ACTIVE','TAKEN'), so that row suppressed re-entry on the instrument for that
  // version, and TakenSignalResolver (the only TAKEN->EXPIRED writer) fires only on
  // PaperPositionClosed -- which can never arrive for a position that was never opened.
  // Measured: signals 20/23/26 (minervini, 2026-07-03) and 193 (SALSTEEL, manas-arora-vcp,
  // 2026-08-13) -- zero orders, zero positions, zero rejections, empty paper_admin_audit.
  //
  // The compensation is deliberately ONE-SIDED. PaperService.openPosition AVERAGES into an open
  // position instead of rejecting it, so releasing an anchor whose open PARTIALLY succeeded invites
  // a silent double-open. A stranded anchor is a suppressed slot; a double-open is real money.
  // ---------------------------------------------------------------------------------------------

  /** A clean nothing-was-opened ledger: no pending effect, no filled order, no open quantity. */
  private static SwingPaperEffectRepository cleanLedger() {
    SwingPaperEffectRepository effects = mock(SwingPaperEffectRepository.class);
    when(effects.findOpenBySignal(anyLong())).thenReturn(Optional.empty());
    when(effects.pendingEntry(anyLong())).thenReturn(false);
    when(effects.entryConfirmedByPaper(anyLong())).thenReturn(false);
    return effects;
  }

  private static PaperSignalListener listener(
      PaperService paper,
      SignalRepository signals,
      SwingPaperEffectRepository effects,
      SimpleMeterRegistry meters) {
    return listener(paper, signals, effects, meters, mock(PlatformTransactionManager.class));
  }

  /**
   * The listener with an observable transaction manager. A plain Mockito {@link
   * PlatformTransactionManager} is enough for {@link
   * org.springframework.transaction.support.TransactionTemplate}: it calls {@code getTransaction},
   * runs the callback, then {@code commit} - so the mock records the real ORDER of those calls
   * against the repository calls, which is exactly what the Critical is about.
   */
  private static PaperSignalListener listener(
      PaperService paper,
      SignalRepository signals,
      SwingPaperEffectRepository effects,
      SimpleMeterRegistry meters,
      PlatformTransactionManager transactionManager) {
    return new PaperSignalListener(
        paper, mock(ScalperAccountModel.class), signals, effects, null, null, meters,
        transactionManager);
  }

  private static double counter(SimpleMeterRegistry meters, String name, String... tags) {
    var found = meters.find(name).tags(tags).counter();
    return found == null ? 0d : found.count();
  }

  /** The defect, closed: a failed open releases the anchor so the instrument can be re-entered. */
  @Test
  void aFailedOpenReleasesTheStrandedTakenAnchor() {
    PaperService paper = mock(PaperService.class);
    SignalRepository signals = noStraddle();
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    when(paper.openQuantityForSignal(193L)).thenReturn(0L);
    when(paper.openOrder(any()))
        .thenThrow(new ApiException(422, ErrorCodes.DATA_STALE, "last tick is 41s old"));
    when(signals.transitionIf(193L, "TAKEN", "EXPIRED")).thenReturn(true);

    listener(paper, signals, cleanLedger(), meters)
        .onSignalTaken(new SignalTaken(193L, 206, new BigDecimal("69.38"), false));

    // EXPIRED, never ACTIVE: released from activeEntry's ('ACTIVE','TAKEN') set without inviting an
    // immediate retry into the same failing condition.
    verify(signals).transitionIf(193L, "TAKEN", "EXPIRED");
    verify(signals, never()).transitionIf(anyLong(), eq("TAKEN"), eq("ACTIVE"));
    assertThat(counter(meters, PaperSignalListener.COMPENSATED_METRIC)).isEqualTo(1d);
    assertThat(meters.find(PaperSignalListener.COMPENSATION_REFUSED_METRIC).counters()).isEmpty();
  }

  /**
   * THE HAZARD. An open that partially succeeded -- a filled order whose position has since been
   * closed, so the position table reads EMPTY -- must NOT be released. Compensating here would let
   * the next entry average into a live book on a signal that already spent its money.
   *
   * <p>This is the arm the position-table read alone cannot see, which is why the proof consults
   * the durable paper_orders record FIRST.
   */
  @Test
  void aPartiallySucceededOpenIsNeverReverted() {
    PaperService paper = mock(PaperService.class);
    SignalRepository signals = noStraddle();
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    SwingPaperEffectRepository effects = cleanLedger();
    // leg 1 filled, then the confirm/second leg threw; the position table is empty by the time we look
    when(effects.entryConfirmedByPaper(193L)).thenReturn(true);
    when(paper.openQuantityForSignal(193L)).thenReturn(0L);
    when(paper.openOrder(any())).thenThrow(new RuntimeException("failure after the fill commit"));

    listener(paper, signals, effects, meters)
        .onSignalTaken(new SignalTaken(193L, 206, new BigDecimal("69.38"), false));

    verify(signals, never()).transitionIf(anyLong(), eq("TAKEN"), anyString());
    assertThat(counter(meters, PaperSignalListener.COMPENSATED_METRIC)).isZero();
    assertThat(
            counter(
                meters, PaperSignalListener.COMPENSATION_REFUSED_METRIC, "reason", "filled_order"))
        .isEqualTo(1d);
  }

  /** The same one-sidedness against a still-OPEN leg. */
  @Test
  void anOpenPositionOnTheSignalBlocksCompensation() {
    PaperService paper = mock(PaperService.class);
    SignalRepository signals = noStraddle();
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    when(paper.openQuantityForSignal(193L)).thenReturn(206L);
    when(paper.openOrder(any())).thenThrow(new RuntimeException("failure after the fill commit"));

    listener(paper, signals, cleanLedger(), meters)
        .onSignalTaken(new SignalTaken(193L, 206, new BigDecimal("69.38"), false));

    verify(signals, never()).transitionIf(anyLong(), eq("TAKEN"), anyString());
    assertThat(
            counter(
                meters, PaperSignalListener.COMPENSATION_REFUSED_METRIC, "reason", "open_position"))
        .isEqualTo(1d);
  }

  /** An unconfirmed durable ENTRY decision means recovery owns the anchor, not this listener. */
  @Test
  void aPendingSwingEffectDecisionBlocksCompensation() {
    PaperService paper = mock(PaperService.class);
    SignalRepository signals = noStraddle();
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    SwingPaperEffectRepository effects = cleanLedger();
    when(effects.pendingEntry(193L)).thenReturn(true);
    when(paper.openQuantityForSignal(193L)).thenReturn(0L);
    when(paper.openOrder(any())).thenThrow(new RuntimeException("connection reset"));

    listener(paper, signals, effects, meters)
        .onSignalTaken(new SignalTaken(193L, 206, new BigDecimal("69.38"), false));

    verify(signals, never()).transitionIf(anyLong(), eq("TAKEN"), anyString());
    assertThat(
            counter(
                meters,
                PaperSignalListener.COMPENSATION_REFUSED_METRIC,
                "reason",
                "swing_effect_pending"))
        .isEqualTo(1d);
  }

  /**
   * A proof that cannot be taken is not a proof. The probe throwing leaves the anchor TAKEN --
   * failing in the direction of a suppressed slot rather than a doubled position.
   */
  @Test
  void aFailedProofLeavesTheAnchorTaken() {
    PaperService paper = mock(PaperService.class);
    SignalRepository signals = noStraddle();
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    SwingPaperEffectRepository effects = cleanLedger();
    when(effects.pendingEntry(193L)).thenThrow(new RuntimeException("ledger read failed"));
    when(paper.openOrder(any())).thenThrow(new RuntimeException("connection reset"));

    listener(paper, signals, effects, meters)
        .onSignalTaken(new SignalTaken(193L, 206, new BigDecimal("69.38"), false));

    verify(signals, never()).transitionIf(anyLong(), eq("TAKEN"), anyString());
    assertThat(
            counter(
                meters, PaperSignalListener.COMPENSATION_REFUSED_METRIC, "reason", "probe_failed"))
        .isEqualTo(1d);
  }

  /** A SUCCESSFUL open leaves the anchor TAKEN -- it holds a real position the engine exits through. */
  @Test
  void aSuccessfulOpenLeavesTheAnchorTaken() {
    PaperService paper = mock(PaperService.class);
    SignalRepository signals = noStraddle();
    SimpleMeterRegistry meters = new SimpleMeterRegistry();

    listener(paper, signals, cleanLedger(), meters)
        .onSignalTaken(new SignalTaken(193L, 206, new BigDecimal("69.38"), false));

    verify(paper).openOrder(any());
    verify(signals, never()).transitionIf(anyLong(), eq("TAKEN"), anyString());
    assertThat(meters.find(PaperSignalListener.COMPENSATED_METRIC).counters()).isEmpty();
    assertThat(meters.find(PaperSignalListener.COMPENSATION_REFUSED_METRIC).counters()).isEmpty();
  }

  /**
   * The swing-effect lease path is EXCLUDED, and not merely as a scoping nicety: the lease is
   * durable (a transient fault stays CLAIMED for the catch-up to replay), and PaperService.openOrder
   * REFUSES to fill against an EXPIRED signal -- so expiring the anchor would permanently break the
   * very replay that recovers it.
   */
  @Test
  void theSwingEffectLeasePathIsNeverCompensated() {
    PaperService paper = mock(PaperService.class);
    SignalRepository signals = noStraddle();
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    SwingPaperEffectRepository effects = claimingRepository();
    // A throw from INSIDE the REQUIRED branch but ABOVE openSwingEffect's own try — the only way
    // this path reaches the outer catch at all. Nothing was opened and the position table is
    // empty, so WITHOUT the swing-path exclusion every other arm of the proof would pass and the
    // anchor would be EXPIRED out from under a live lease.
    when(paper.openQuantityForSignal(SALSTEEL_SIGNAL)).thenReturn(0L);
    when(effects.claimOpen(anyLong(), anyLong(), anyInt()))
        .thenThrow(new RuntimeException("lease claim failed"));

    listener(paper, signals, effects, meters)
        .onSignalTaken(new SignalTaken(SALSTEEL_SIGNAL, 206, new BigDecimal("69.38"), false));

    verify(signals, never()).transitionIf(anyLong(), eq("TAKEN"), anyString());
    assertThat(counter(meters, PaperSignalListener.COMPENSATED_METRIC)).isZero();
    assertThat(
            counter(
                meters,
                PaperSignalListener.COMPENSATION_REFUSED_METRIC,
                "reason",
                "swing_effect_path"))
        .isEqualTo(1d);
  }

  /**
   * A lost CAS is BENIGN and must stay off the refused metric (cross-vendor round 1, Major).
   * Another writer already moved the anchor off TAKEN, so nothing is stranded and nothing needs an
   * operator - counting it as a refusal put routine noise on the counter alerts key off, and
   * logging it "LEFT TAKEN" said the opposite of what had happened.
   */
  @Test
  void anAnchorAlreadyMovedOffTakenIsBenignAndNotARefusal() {
    PaperService paper = mock(PaperService.class);
    SignalRepository signals = noStraddle();
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    when(paper.openQuantityForSignal(193L)).thenReturn(0L);
    when(paper.openOrder(any())).thenThrow(new RuntimeException("connection reset"));
    when(signals.transitionIf(193L, "TAKEN", "EXPIRED")).thenReturn(false);

    listener(paper, signals, cleanLedger(), meters)
        .onSignalTaken(new SignalTaken(193L, 206, new BigDecimal("69.38"), false));

    assertThat(counter(meters, PaperSignalListener.COMPENSATED_METRIC)).isZero();
    assertThat(counter(meters, PaperSignalListener.ALREADY_MOVED_METRIC)).isEqualTo(1d);
    // the alertable metric stays CLEAN
    assertThat(meters.find(PaperSignalListener.COMPENSATION_REFUSED_METRIC).counters()).isEmpty();
  }

  /**
   * THE CRITICAL (cross-vendor round 1). The probes and the CAS used to run as separate autocommit
   * statements with no per-anchor lock, so a concurrent signal-linked open could read TAKEN, create
   * an UNCOMMITTED fill, let our ladder see nothing committed, and then commit that fill AFTER we
   * expired the anchor - an OPEN position under an EXPIRED anchor, which the next entry AVERAGES
   * into. The unique index cannot save that.
   *
   * <p>Asserted as strict ORDER across ALL the collaborating mocks, not just one: the transaction
   * must OPEN, the anchor lock must be taken FIRST inside it, every probe and the CAS must run
   * after the lock, and only then may the transaction commit. An InOrder over a single mock would
   * be blind to the other's call landing in the gap it forbids.
   */
  @Test
  void theProbesAndTheCasRunInOneTransactionUnderTheAnchorLock() {
    PaperService paper = mock(PaperService.class);
    SignalRepository signals = noStraddle();
    SwingPaperEffectRepository effects = cleanLedger();
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    when(paper.openQuantityForSignal(193L)).thenReturn(0L);
    when(paper.openOrder(any()))
        .thenThrow(new ApiException(422, ErrorCodes.DATA_STALE, "last tick is 41s old"));
    when(signals.transitionIf(193L, "TAKEN", "EXPIRED")).thenReturn(true);

    listener(paper, signals, effects, meters, transactionManager)
        .onSignalTaken(new SignalTaken(193L, 206, new BigDecimal("69.38"), false));

    InOrder order = org.mockito.Mockito.inOrder(transactionManager, signals, effects, paper);
    order.verify(transactionManager).getTransaction(any());
    order.verify(signals).lockAnchors(List.of(193L));
    order.verify(effects).pendingEntry(193L);
    order.verify(effects).entryConfirmedByPaper(193L);
    order.verify(paper).openQuantityForSignal(193L);
    order.verify(signals).transitionIf(193L, "TAKEN", "EXPIRED");
    order.verify(transactionManager).commit(any());
    assertThat(counter(meters, PaperSignalListener.COMPENSATED_METRIC)).isEqualTo(1d);
  }

  /**
   * The success counter hangs off the COMMIT, not off the CAS returning true (cross-vendor round 1,
   * follow-on). A commit that fails must leave NO counter claiming a compensation that never
   * happened - the success-shaped-nothing case.
   */
  @Test
  void aFailedCommitLeavesNoSuccessCounter() {
    PaperService paper = mock(PaperService.class);
    SignalRepository signals = noStraddle();
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    org.mockito.Mockito.doThrow(new RuntimeException("commit failed"))
        .when(transactionManager)
        .commit(org.mockito.ArgumentMatchers.<TransactionStatus>any());
    when(paper.openQuantityForSignal(193L)).thenReturn(0L);
    when(paper.openOrder(any())).thenThrow(new RuntimeException("connection reset"));
    when(signals.transitionIf(193L, "TAKEN", "EXPIRED")).thenReturn(true);

    listener(paper, signals, cleanLedger(), meters, transactionManager)
        .onSignalTaken(new SignalTaken(193L, 206, new BigDecimal("69.38"), false));

    assertThat(counter(meters, PaperSignalListener.COMPENSATED_METRIC)).isZero();
    assertThat(
            counter(
                meters,
                PaperSignalListener.COMPENSATION_REFUSED_METRIC,
                "reason",
                "transaction_failed"))
        .isEqualTo(1d);
  }

  /** A throwing CAS is counted under its OWN bounded reason instead of escaping uncounted. */
  @Test
  void aThrowingTransitionIsCountedAsTransitionFailed() {
    PaperService paper = mock(PaperService.class);
    SignalRepository signals = noStraddle();
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    when(paper.openQuantityForSignal(193L)).thenReturn(0L);
    when(paper.openOrder(any())).thenThrow(new RuntimeException("connection reset"));
    when(signals.transitionIf(193L, "TAKEN", "EXPIRED"))
        .thenThrow(new RuntimeException("deadlock detected"));

    listener(paper, signals, cleanLedger(), meters)
        .onSignalTaken(new SignalTaken(193L, 206, new BigDecimal("69.38"), false));

    assertThat(counter(meters, PaperSignalListener.COMPENSATED_METRIC)).isZero();
    assertThat(
            counter(
                meters,
                PaperSignalListener.COMPENSATION_REFUSED_METRIC,
                "reason",
                "transition_failed"))
        .isEqualTo(1d);
  }

  /**
   * An ambient transaction is a machine-checked precondition, not an assumption. Joining a caller's
   * transaction would break both halves of the guarantee - a rollback-only caller silently discards
   * the CAS while the counter still fires, and a caller already holding anchor lock 4801 would make
   * a REQUIRES_NEW variant self-deadlock. No publisher is transactional today; this refuses loudly
   * if one ever becomes so.
   */
  @Test
  void anAmbientTransactionRefusesInsteadOfJoiningIt() {
    PaperService paper = mock(PaperService.class);
    SignalRepository signals = noStraddle();
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    when(paper.openQuantityForSignal(193L)).thenReturn(0L);
    when(paper.openOrder(any())).thenThrow(new RuntimeException("connection reset"));

    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      listener(paper, signals, cleanLedger(), meters)
          .onSignalTaken(new SignalTaken(193L, 206, new BigDecimal("69.38"), false));
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    verify(signals, never()).lockAnchors(any());
    verify(signals, never()).transitionIf(anyLong(), eq("TAKEN"), anyString());
    assertThat(
            counter(
                meters,
                PaperSignalListener.COMPENSATION_REFUSED_METRIC,
                "reason",
                "ambient_transaction"))
        .isEqualTo(1d);
  }

  private static final long SALSTEEL_SIGNAL = 193L;
  private static final long SALSTEEL_EFFECT = 19L;

  /** The live incident's rail, with the details map every governor throw site in PaperService carries. */
  private static ApiException governorRefusal(String rail) {
    return new ApiException(
        422,
        ErrorCodes.RISK_ENTRY_BLOCKED,
        "entry blocked by risk governor (" + rail + ") on book manas-arora",
        Map.of("book", "manas-arora", "rail", rail));
  }

  /** An effect ledger that hands out the live incident's REQUIRED lease. */
  private static SwingPaperEffectRepository claimingRepository() {
    SwingPaperEffectRepository effects = mock(SwingPaperEffectRepository.class);
    SwingPaperEffectRepository.Effect effect =
        new SwingPaperEffectRepository.Effect(
            SALSTEEL_EFFECT,
            "manas-arora",
            LocalDate.of(2026, 8, 13),
            "ENTRY:SALSTEEL",
            "ENTRY",
            "SALSTEEL",
            SALSTEEL_SIGNAL,
            null,
            null,
            null,
            null,
            206L,
            0L,
            "CLAIMED",
            "REQUIRED",
            List.of());
    when(effects.findOpenBySignal(SALSTEEL_SIGNAL)).thenReturn(Optional.of(effect));
    when(effects.claimOpen(eq(SALSTEEL_EFFECT), anyLong(), anyInt())).thenReturn(Optional.of(effect));
    return effects;
  }

  /** A directional scalper take opens the PICKED OPTION at its captured premium (audit P0-3). */
  @Test
  void aScalperTakeOpensTheTradeableOptionAtItsCapturedPremiumWithNoFutureBasisBrackets()
      throws Exception {
    PaperService paper = mock(PaperService.class);
    ScalperAccountModel accounts = mock(ScalperAccountModel.class);
    when(accounts.nextFreeAccount()).thenReturn(1);
    SignalRepository signals = mock(SignalRepository.class);
    var detail =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree("{\"side\":\"LONG_PE\",\"option_ltp\":\"82.50\"}");
    when(signals.find(7L))
        .thenReturn(
            Optional.of(
                new SignalRepository.SignalRow(
                    7L, java.util.UUID.randomUUID(), "NFO", "NIFTY26JULFUT", "3m", "ENTRY", "BUY",
                    new BigDecimal("25000"), new BigDecimal("24800"), new BigDecimal("25400"),
                    new BigDecimal("0.8"), null, "TAKEN", null, null, new BigDecimal("75"),
                    "NFO", "NIFTY26JUL24900PE", detail, null, null, null)));

    // The auto-take's fillPrice is the FUTURE entry price — the option leg must ignore it.
    new PaperSignalListener(paper, accounts, signals)
        .onSignalTaken(new SignalTaken(7L, 75, new BigDecimal("25000"), true));

    ArgumentCaptor<PaperService.OrderRequest> req =
        ArgumentCaptor.forClass(PaperService.OrderRequest.class);
    verify(paper).openScalperOrder(req.capture()); // scalper take routes through the assigning open
    assertThat(req.getValue().exchange()).isEqualTo("NFO");
    assertThat(req.getValue().tradingsymbol()).isEqualTo("NIFTY26JUL24900PE");
    assertThat(req.getValue().side()).isEqualTo("BUY");
    assertThat(req.getValue().price()).isEqualByComparingTo("82.50");
    // index-future SL/TP must NOT ride the option leg (wrong basis = instant bracket close)
    assertThat(req.getValue().stopLoss()).isNull();
    assertThat(req.getValue().takeProfit()).isNull();
  }

  /** The YAML's premium_pct rules become option-premium bracket levels on the option leg (P1-8). */
  @Test
  void aScalperTakeDerivesPremiumBasisBracketsFromTheYamlExitRules() throws Exception {
    PaperService paper = mock(PaperService.class);
    ScalperAccountModel accounts = mock(ScalperAccountModel.class);
    when(accounts.nextFreeAccount()).thenReturn(1);
    SignalRepository signals = mock(SignalRepository.class);
    var om = new com.fasterxml.jackson.databind.ObjectMapper();
    var detail = om.readTree("{\"side\":\"LONG_CE\",\"option_ltp\":\"82.50\"}");
    java.util.UUID versionId = java.util.UUID.randomUUID();
    when(signals.find(7L))
        .thenReturn(
            Optional.of(
                new SignalRepository.SignalRow(
                    7L, versionId, "NFO", "NIFTY26JULFUT", "3m", "ENTRY", "BUY",
                    new BigDecimal("25000"), new BigDecimal("24800"), new BigDecimal("25400"),
                    new BigDecimal("0.8"), null, "TAKEN", null, null, new BigDecimal("75"),
                    "NFO", "NIFTY26JUL25100CE", detail, null, null, null)));
    when(signals.versionConfig(versionId))
        .thenReturn(
            Optional.of(
                om.readTree(
                    "{\"exit_rules\":["
                        + "{\"type\":\"stop_loss\",\"params\":{\"basis\":\"premium_pct\",\"value\":50}},"
                        + "{\"type\":\"take_profit\",\"params\":{\"basis\":\"premium_pct\",\"value\":35}},"
                        + "{\"type\":\"time_stop\",\"params\":{\"max_bars\":16}}]}")));

    new PaperSignalListener(paper, accounts, signals)
        .onSignalTaken(new SignalTaken(7L, 75, new BigDecimal("25000"), true));

    ArgumentCaptor<PaperService.OrderRequest> req =
        ArgumentCaptor.forClass(PaperService.OrderRequest.class);
    verify(paper).openScalperOrder(req.capture()); // scalper take routes through the assigning open
    // 82.50 × (1−0.50) and 82.50 × (1+0.35) — premium basis, enforceable by PaperBracketEvaluator
    assertThat(req.getValue().stopLoss()).isEqualByComparingTo("41.25");
    assertThat(req.getValue().takeProfit()).isEqualByComparingTo("111.38");
  }

  /** A non-scalper take keeps the primary leg and carries the signal's same-basis brackets. */
  @Test
  void aNonScalperTakeCarriesTheSignalBracketsOnThePrimaryLeg() {
    PaperService paper = mock(PaperService.class);
    ScalperAccountModel accounts = mock(ScalperAccountModel.class);
    SignalRepository signals = mock(SignalRepository.class);
    when(signals.find(7L))
        .thenReturn(
            Optional.of(
                new SignalRepository.SignalRow(
                    7L, java.util.UUID.randomUUID(), "NSE", "RELIANCE", "1m", "ENTRY", "BUY",
                    new BigDecimal("2500"), new BigDecimal("2450"), new BigDecimal("2600"),
                    new BigDecimal("0.8"), null, "TAKEN", null, null, new BigDecimal("10"),
                    null, null, null, null, null, null)));

    new PaperSignalListener(paper, accounts, signals)
        .onSignalTaken(new SignalTaken(7L, 10, new BigDecimal("2500"), false));

    ArgumentCaptor<PaperService.OrderRequest> req =
        ArgumentCaptor.forClass(PaperService.OrderRequest.class);
    verify(paper).openOrder(req.capture());
    assertThat(req.getValue().tradingsymbol()).isNull(); // primary-leg fallback in openOrder
    assertThat(req.getValue().stopLoss()).isEqualByComparingTo("2450");
    assertThat(req.getValue().takeProfit()).isEqualByComparingTo("2600");
  }
}
