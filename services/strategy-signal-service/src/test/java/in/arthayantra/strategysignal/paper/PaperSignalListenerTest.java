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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
