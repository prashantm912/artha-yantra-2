package in.arthayantra.strategysignal.paper;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.strategysignal.signals.SignalRepository;
import in.arthayantra.strategysignal.signals.SignalTaken;
import in.arthayantra.strategysignal.signals.SwingPaperEffectRepository;
import in.arthayantra.strategysignal.signals.SwingPaperEffectRetry;
import in.arthayantra.strategysignal.swing.SwingBatchRefusalRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Opens a paper position when a TAKEN signal carries a qty (§F.6 "optionally opens a paper position").
 * Synchronous {@code @EventListener} so the position is visible by the time the {@code /taken} response
 * returns; a paper-open failure is logged, never propagated to the caller.
 *
 * <p>#11 long straddle (E11): a NEUTRAL straddle take carries BOTH ATM legs in its {@code scalper_detail}
 * {@code legs[]}, so it opens TWO positions (the ATM CE + PE), each sized to the COMBINED-premium lot
 * count ({@link StraddleLegs#combinedQty}) so the pair together spends the strategy budget — both legs
 * link to the parent signal. Every directional / non-straddle take is unchanged (the single primary leg).
 */
@Component
public class PaperSignalListener {

  private static final Logger log = LoggerFactory.getLogger(PaperSignalListener.class);

  /** {@code swing_batch_refusals.reason} prefix for a permanent risk-governor entry refusal (H22). */
  private static final String GOVERNOR_REFUSAL_REASON = "RISK_ENTRY_BLOCKED:";

  /** H43: a stranded {@code TAKEN} anchor released back out of the re-entry-suppressing set. */
  static final String COMPENSATED_METRIC = "ay_signal_taken_anchor_compensated_total";

  /**
   * H43: a swallowed paper-open failure whose anchor was deliberately LEFT {@code TAKEN}. Tagged
   * {@code reason} so the SAFE refusals (a real position exists) stay separable from the ones that
   * mean a human should look ({@code probe_failed} / {@code ambient_transaction}). Every reason on
   * this metric is a NON-EVENT worth an operator's attention; the benign "someone else already
   * resolved it" outcome has its OWN metric below so this one stays alertable as-is.
   */
  static final String COMPENSATION_REFUSED_METRIC =
      "ay_signal_taken_anchor_compensation_refused_total";

  /**
   * H43: the anchor had already left {@code TAKEN} by the time the CAS ran (a concurrent resolve or
   * a replay got there first). BENIGN and deliberately NOT on the refused metric - nothing is
   * stranded, nothing needs looking at, and folding it in would put routine noise on the counter an
   * operator alerts on (cross-vendor round 1, Major).
   */
  static final String ALREADY_MOVED_METRIC = "ay_signal_taken_anchor_already_moved_total";

  private final PaperService paper;
  private final ScalperAccountModel scalperAccounts;
  private final SignalRepository signals;
  private final SwingPaperEffectRepository paperEffects;
  private final InstrumentMetaClient instruments;
  private final SwingBatchRefusalRepository refusals;
  private final MeterRegistry meters;
  private final TransactionTemplate compensationTx;

  /**
   * Wires the ledger service, the 5-account sub-ledger, the signal store, the instrument master +
   * the durable refusal sink a permanently-refused swing entry is resolved against (H22).
   */
  @Autowired
  public PaperSignalListener(
      PaperService paper,
      ScalperAccountModel scalperAccounts,
      SignalRepository signals,
      SwingPaperEffectRepository paperEffects,
      InstrumentMetaClient instruments,
      SwingBatchRefusalRepository refusals,
      MeterRegistry meters,
      PlatformTransactionManager transactionManager) {
    this.paper = paper;
    this.scalperAccounts = scalperAccounts;
    this.signals = signals;
    this.paperEffects = paperEffects;
    this.instruments = instruments;
    this.refusals = refusals;
    this.meters = meters;
    // Default propagation (REQUIRED). Compensation refuses outright when a transaction is already
    // active (see compensateStrandedAnchor), so this template always starts a REAL one - which is
    // what gives `execute` a genuine commit boundary to hang the success counter off.
    this.compensationTx =
        transactionManager == null ? null : new TransactionTemplate(transactionManager);
  }

  /** Backwards-compatible constructor for focused paper-listener tests. */
  public PaperSignalListener(
      PaperService paper,
      ScalperAccountModel scalperAccounts,
      SignalRepository signals,
      SwingPaperEffectRepository paperEffects,
      InstrumentMetaClient instruments,
      SwingBatchRefusalRepository refusals,
      MeterRegistry meters) {
    this(paper, scalperAccounts, signals, paperEffects, instruments, refusals, meters, null);
  }

  /** Backwards-compatible constructor for focused paper-listener tests. */
  public PaperSignalListener(
      PaperService paper,
      ScalperAccountModel scalperAccounts,
      SignalRepository signals,
      SwingPaperEffectRepository paperEffects,
      InstrumentMetaClient instruments,
      SwingBatchRefusalRepository refusals) {
    this(paper, scalperAccounts, signals, paperEffects, instruments, refusals, new SimpleMeterRegistry());
  }

  /** Backwards-compatible constructor for focused paper-listener tests. */
  public PaperSignalListener(
      PaperService paper,
      ScalperAccountModel scalperAccounts,
      SignalRepository signals,
      SwingPaperEffectRepository paperEffects,
      InstrumentMetaClient instruments) {
    this(paper, scalperAccounts, signals, paperEffects, instruments, null);
  }

  /** Backwards-compatible constructor for focused paper-listener tests. */
  public PaperSignalListener(
      PaperService paper,
      ScalperAccountModel scalperAccounts,
      SignalRepository signals,
      SwingPaperEffectRepository paperEffects) {
    this(paper, scalperAccounts, signals, paperEffects, null, null);
  }

  /** Backwards-compatible constructor for focused paper-listener tests. */
  public PaperSignalListener(
      PaperService paper, ScalperAccountModel scalperAccounts, SignalRepository signals) {
    this(paper, scalperAccounts, signals, null, null, null);
  }

  /**
   * Opens a position (or, for a straddle, both legs) from the signal when a qty was supplied.
   *
   * <p>The {@code ACTIVE->TAKEN} CAS has ALREADY committed one frame up (in {@code
   * SignalsController#taken} or {@link AutoPaperListener}), so a throw swallowed here used to leave
   * the anchor {@code TAKEN} with no order, no position and no rejection row - and {@code
   * SignalRepository.activeEntry} reads {@code status IN ('ACTIVE','TAKEN')}, so that anchor
   * suppressed re-entry on the instrument for that version FOREVER ({@link TakenSignalResolver}
   * fires only on {@code PaperPositionClosed}, which can never arrive for a position that was never
   * opened). H43 measured four such rows. {@link #compensateStrandedAnchor} releases the anchor -
   * but only when it can PROVE nothing was opened; see that method for why the asymmetry runs the
   * way it does.
   */
  @EventListener
  public void onSignalTaken(SignalTaken event) {
    if (event.qty() == null || event.qty() <= 0) {
      return;
    }
    // Set BEFORE the call it guards: the swing-effect lease owns its own recovery, so a throw from
    // under it must never be compensated here (see compensateStrandedAnchor).
    boolean swingEffectPath = false;
    try {
      Optional<SwingPaperEffectRepository.Effect> swingEffect =
          paperEffects == null ? Optional.empty() : paperEffects.findOpenBySignal(event.signalId());
      if (swingEffect.isPresent()) {
        String decision = swingEffect.get().decision();
        if ("REQUIRED".equals(decision)) {
          swingEffectPath = true;
          openSwingEffect(event, swingEffect.get());
          return;
        }
        if (!"SKIPPED".equals(decision)) {
          // An unresolved effect has no durable decision. Never turn an ambiguous ledger row into a
          // paper open merely because a retry/take event arrived.
          return;
        }
        // SKIPPED means auto-paper did not claim this emission; an explicit manual take still uses
        // the ordinary open path below.
      }
      // E10: a scalper take is charged to a round-robin sub-account (the per-account first-loss freeze
      // reads it); a non-scalper / manual take leaves the ledger key NULL. A straddle's TWO legs share
      // the SAME sub-account (one position). The PICK itself happens in PaperService, under the book
      // lock that also validates and writes it — picking out here raced the ceiling (round 4).
      Optional<StraddleLegs.Pair> straddle =
          event.scalper()
              ? signals
                  .find(event.signalId())
                  .map(SignalRepository.SignalRow::scalperDetail)
                  .flatMap(StraddleLegs::parse)
              : Optional.empty();
      if (straddle.isPresent()) {
        openStraddle(event, straddle.get(), event.scalper());
      } else {
        openSingle(event, event.scalper());
      }
      if (paperEffects != null) {
        paperEffects.confirmEntry(event.signalId());
      }
    } catch (Exception e) {
      log.warn("paper position not opened for taken signal {}: {}", event.signalId(), e.getMessage());
      compensateStrandedAnchor(event.signalId(), swingEffectPath, e);
    }
  }

  /**
   * H43 - releases a {@code TAKEN} anchor whose paper open failed, so it stops suppressing re-entry.
   *
   * <p>{@code TAKEN->EXPIRED}, not {@code TAKEN->ACTIVE}, and the choice is deliberate. {@code
   * ACTIVE} would put the SAME signal back in the takeable feed at a price and a bar that have both
   * moved on, and invite an immediate retry straight back into the condition that just refused - a
   * stale tick and a deployment cap both persist for minutes. {@code EXPIRED} only removes the
   * anchor from {@code activeEntry}'s {@code ('ACTIVE','TAKEN')} set: the engine is then free to
   * emit a FRESH entry on the next qualifying bar, priced off that bar, through its own admission
   * gate. It is also the state {@link TakenSignalResolver} already writes for the ordinary "this
   * anchor no longer holds a position" case, so nothing downstream sees a new one.
   *
   * <p>THE ASYMMETRY. {@code PaperService.openPosition} AVERAGES into an existing open position
   * rather than rejecting it ({@code uq_paper_positions_open} guards the ROW, not the quantity), so
   * releasing an anchor whose open PARTIALLY succeeded invites a second entry that silently doubles
   * a live position. A stranded anchor is a suppressed slot; a double-open is real money. So this
   * compensates ONLY on positive proof that nothing was opened, and every other outcome - including
   * "the proof itself failed" - leaves the anchor {@code TAKEN} and logs ERROR.
   *
   * <p>The proof is settled against the DURABLE decision records first, exactly as the swing ledger
   * was built to be ({@code requireEntry} / {@code confirmEntry} / {@code skipEntry}), and only then
   * against the position table - a position can be opened and closed again between the throw and
   * this read, whereas a {@code FILLED} {@code paper_orders} row cannot be un-written.
   */
  private void compensateStrandedAnchor(long signalId, boolean swingEffectPath, Exception failure) {
    if (swingEffectPath) {
      // Not this method's anchor to release. The lease is durable: a transient fault stays CLAIMED
      // for the swing catch-up to replay, and a governor verdict is closed terminally by
      // resolveGovernorRefusal. Expiring the anchor would ALSO break that replay outright -
      // PaperService.openOrder refuses to fill against an EXPIRED signal.
      refuseToCompensate(signalId, "swing_effect_path", failure, null);
      return;
    }
    if (compensationTx == null) {
      refuseToCompensate(signalId, "no_transaction_manager", failure, null);
      return;
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      // Machine-checked precondition, not an assumption. Joining a caller's transaction would break
      // BOTH halves of the guarantee: a rollback-only caller silently discards the CAS while the
      // success counter still fires, and a caller already holding anchor lock 4801 would make a
      // REQUIRES_NEW variant self-deadlock. No publisher is transactional today; if one ever
      // becomes so this refuses LOUDLY instead of degrading into either failure.
      refuseToCompensate(signalId, "ambient_transaction", failure, null);
      return;
    }
    String outcome;
    try {
      outcome = compensationTx.execute(status -> decideUnderAnchorLock(signalId));
    } catch (CompensationAborted aborted) {
      refuseToCompensate(signalId, aborted.reason, failure, aborted);
      return;
    } catch (RuntimeException txFailure) {
      // The lock acquisition, the commit itself, or anything else in the transaction machinery.
      refuseToCompensate(signalId, "transaction_failed", failure, txFailure);
      return;
    }
    // Past this line the transaction has COMMITTED. Incrementing inside the callback would have
    // let a rollback leave a counter claiming a compensation that never happened.
    if (ALREADY_MOVED.equals(outcome)) {
      meters.counter(ALREADY_MOVED_METRIC).increment();
      log.info(
          "taken signal {} needed no release - another writer had already moved it off TAKEN before"
              + " the compensating transition ran",
          signalId);
      return;
    }
    if (outcome != null) {
      refuseToCompensate(signalId, outcome, failure, null);
      return;
    }
    meters.counter(COMPENSATED_METRIC).increment();
    log.warn(
        "taken signal {} released TAKEN->EXPIRED: its paper open failed and nothing was opened, so"
            + " the anchor was suppressing re-entry on that instrument with no way to resolve: {}",
        signalId, failure.getMessage());
  }

  /** The {@code already_moved} sentinel - a lost CAS, which is benign, not a refusal. */
  private static final String ALREADY_MOVED = "already_moved";

  /** A classified abort from inside the compensation transaction; carries its bounded reason tag. */
  private static final class CompensationAborted extends RuntimeException {
    private final String reason;

    CompensationAborted(String reason, RuntimeException cause) {
      super(reason, cause);
      this.reason = reason;
    }
  }

  /**
   * The whole decision - lock, probe, CAS - inside ONE transaction. Returns {@code null} when the
   * anchor was released, {@link #ALREADY_MOVED} when it had already left {@code TAKEN}, otherwise
   * the refusal reason.
   *
   * <p>⚠️ THE LOCK IS THE POINT (cross-vendor round 1, Critical). Probing and then transitioning as
   * separate autocommit statements let a concurrent signal-linked open interleave: it reads
   * {@code TAKEN}, creates an UNCOMMITTED fill, our ladder sees no committed order and no position,
   * we expire the anchor, and then its fill commits - an OPEN position under an EXPIRED anchor,
   * which the next entry then AVERAGES into. Reading and deciding in the same transaction under the
   * same per-anchor advisory lock every signal-linked open already takes ({@code
   * PaperService.openOrder} -> {@code SignalRepository.lockAnchors}) is what makes the evidence
   * mean anything: whichever side commits first, the loser observes committed state.
   *
   * <p>⚠️ ANCHOR LOCK ONLY. {@code ANCHOR_LOCK_NAMESPACE=4801} is taken BEFORE
   * {@code BOOK_CAPITAL_LOCK_NAMESPACE=4802} by every entry path
   * ({@code PaperService.lockAnchorsBeforeBook}), and this repo has already deadlocked two money
   * -path opens by inverting that order. Compensation takes 4801 and NOTHING else - no book lock,
   * so no order to invert. Nothing added here may take 4802.
   */
  private String decideUnderAnchorLock(long signalId) {
    signals.lockAnchors(List.of(signalId));
    String refusal;
    try {
      refusal = whyNotCompensable(signalId);
    } catch (RuntimeException probeFailure) {
      throw new CompensationAborted("probe_failed", probeFailure);
    }
    if (refusal != null) {
      return refusal;
    }
    try {
      return signals.transitionIf(signalId, "TAKEN", "EXPIRED") ? null : ALREADY_MOVED;
    } catch (RuntimeException transitionFailure) {
      throw new CompensationAborted("transition_failed", transitionFailure);
    }
  }

  /**
   * The reason this anchor must stay {@code TAKEN}, or {@code null} when nothing was opened.
   *
   * <p>Ordered strongest-evidence-first, and every arm fails CLOSED: with no ledger to consult at
   * all there is no proof, so there is no compensation.
   */
  private String whyNotCompensable(long signalId) {
    if (paperEffects == null) {
      return "no_ledger";
    }
    if (paperEffects.pendingEntry(signalId)) {
      // An unconfirmed durable ENTRY decision exists for this signal. Recovery owns it.
      return "swing_effect_pending";
    }
    if (paperEffects.entryConfirmedByPaper(signalId)) {
      // A FILLED paper_orders row for this signal: money moved, even if the position has since
      // closed. This is the arm that catches a fill-then-throw and a partly-opened multi-leg take.
      return "filled_order";
    }
    if (paper.openQuantityForSignal(signalId) > 0) {
      return "open_position";
    }
    return null;
  }

  /** Counts and pages the refusal - a stranded anchor must be LOUD, never a silent annoyance. */
  private void refuseToCompensate(
      long signalId, String reason, Exception failure, RuntimeException compensationFailure) {
    meters.counter(COMPENSATION_REFUSED_METRIC, "reason", reason).increment();
    if (compensationFailure != null) {
      log.error(
          "taken signal {} LEFT TAKEN after a swallowed paper-open failure ({}) [{}] - the"
              + " compensation itself could not complete, and releasing an anchor whose open may"
              + " have partially succeeded would double-open on the next entry",
          signalId, failure.getMessage(), reason, compensationFailure);
      return;
    }
    log.error(
        "taken signal {} LEFT TAKEN after a swallowed paper-open failure [{}]: {}",
        signalId, reason, failure.getMessage());
  }

  /** Replays a previously claimed swing ENTRY only after the catch-up verified no filled order exists. */
  @EventListener
  public void onSwingPaperEffectRetry(SwingPaperEffectRetry retry) {
    if (retry.kind() != SwingPaperEffectRetry.Kind.ENTRY) {
      return;
    }
    onSignalTaken(new SignalTaken(retry.signalId(), retry.qty(), retry.fillPrice(), retry.scalper()));
  }

  /** Claims before the open and confirms only after a quantity read-back. */
  private void openSwingEffect(
      SignalTaken event, SwingPaperEffectRepository.Effect effect) {
    // A repair event can arrive after the original SignalTaken publication was lost. Re-establish
    // the same ACTIVE->TAKEN anchor before any paper open; the effect lease still gates the money path.
    signals.transitionIf(event.signalId(), "ACTIVE", "TAKEN");
    long before = paper.openQuantityForSignal(event.signalId());
    long expected = effect.expectedQty() > 0 ? effect.expectedQty() : event.qty();
    if (before >= Math.addExact(effect.quantityBefore(), expected)) {
      paperEffects.confirm(effect.id());
      return;
    }
    Optional<SwingPaperEffectRepository.Effect> claimed =
        paperEffects.claimOpen(effect.id(), before, event.qty());
    if (claimed.isEmpty()) {
      return;
    }
    SwingPaperEffectRepository.Effect lease = claimed.get();
    long afterClaim = paper.openQuantityForSignal(event.signalId());
    long target = Math.addExact(lease.quantityBefore(), expected);
    if (afterClaim >= target) {
      paperEffects.confirm(lease.id());
      return;
    }
    try {
      openPaperPosition(event);
      if (paper.openQuantityForSignal(event.signalId()) >= target) {
        paperEffects.confirm(lease.id());
      }
    } catch (Exception e) {
      String rail = governorRail(e);
      if (rail == null) {
        // Leave CLAIMED. The next catch-up repair can reclaim the stale lease after read-back.
        log.warn("paper swing effect {} failed: {}", lease.id(), e.getMessage());
        return;
      }
      resolveGovernorRefusal(lease, rail, e);
    }
  }

  /**
   * The governor rail behind a PERMANENT refusal, or {@code null} when the failure should keep the
   * transient handling - the ONE place this listener decides "verdict" vs "fault". Keyed on
   * {@link ErrorCodes#RISK_ENTRY_BLOCKED}, the marker EVERY governor throw site in
   * {@link PaperService} carries, never on the message text.
   *
   * <p>⚠️ {@link RiskService#MANAS_RISK_UNCOMPUTABLE} is deliberately EXCLUDED, and it is the only
   * {@code RISK_ENTRY_BLOCKED} rail that is. It does not mean "refused", it means "could not be
   * calculated" - {@link PaperService}'s {@code uncomputableRiskRefusal} scopes it to an unsupported
   * side, an undefined governing stop, or non-positive equity. H22's thesis is verdict-vs-fault, and
   * an inability to DECIDE is a fault: the other four rails are policy readings of live book state
   * that bind HARDER as the book fills, while an undefined governing stop is curable by a later
   * replay once {@code ManasGoverningStopCache} warms.
   *
   * <p>The failure directions are asymmetric and this repo has already settled the same trade-off in
   * the sibling case - wrongly terminal is a SILENT permanent forfeiture of a real entry; wrongly
   * transient is a loud page about a book that genuinely needs attention. See
   * {@code SwingBatchCatchUp:755-756}: "A loud unrecoverable beats a silent one."
   *
   * <p>⚠️ A RAIL-LESS refusal is transient for the SAME reason, one step removed. Every throw site
   * today carries a {@code rail}, so this can only arrive from a FUTURE site added without the
   * detail map - and at that moment nobody knows whether it is a policy verdict or an inability.
   * Note the argument is NOT that closing it would be silent: it would write a real
   * {@code swing_batch_refusals} row. It is that the row would be attributed to nothing
   * reviewable, spending a live entry on that ignorance. Left transient it ends at the catch-up's
   * ABANDONED page, which is exactly "a human should look at this" - and a human is what an
   * UNCLASSIFIED refusal needs.
   */
  private static String governorRail(Exception e) {
    if (!(e instanceof ApiException api) || !ErrorCodes.RISK_ENTRY_BLOCKED.equals(api.code())) {
      return null;
    }
    // Covers every rail-less shape identically: the 3-arg ApiException ctor (details normalized to
    // an empty map), a details map carrying other keys but no 'rail', and a mutable map holding a
    // null value - Map.of rejects null values, so only the first two are reachable from PaperService.
    Object rail = api.details().get("rail");
    if (rail == null) {
      return null;
    }
    String label = rail.toString();
    return RiskService.MANAS_RISK_UNCOMPUTABLE.equals(label) ? null : label;
  }

  /**
   * Resolves a permanently-refused swing entry TERMINALLY, with durable evidence first (H22).
   *
   * <p>Measured live 2026-08-17: a {@code pyramid_risk_cap} refusal - the F9 governor doing exactly
   * its job - was handled as a transient fault, so the effect stayed CLAIMED, its session never
   * reached DONE, every sweep re-alerted PAPER EFFECTS UNCONFIRMED, and the attempt budget marched
   * toward an ABANDONED alert calling the session "UNRECOVERABLE". Replay cannot help: the rails
   * read live book state and bind HARDER as the book fills.
   *
   * <p>The refusal row is written BEFORE the effect is closed. Without a durable sink there is
   * nowhere for the reason to survive - {@code swing_batch_refusals} exists precisely so retries
   * cannot erase it (V050) - so a missing or failing sink falls back to the transient handling and
   * leaves the row visible rather than closing it blind.
   */
  private void resolveGovernorRefusal(
      SwingPaperEffectRepository.Effect lease, String rail, Exception refusal) {
    if (refusals == null) {
      log.warn("paper swing effect {} failed: {}", lease.id(), refusal.getMessage());
      return;
    }
    try {
      refusals.record(
          lease.batch(), lease.sessionDate(), lease.tradingsymbol(), GOVERNOR_REFUSAL_REASON + rail);
    } catch (RuntimeException persistFailure) {
      log.error(
          "paper swing effect {} was refused by the risk governor ({}) but the refusal could not be"
              + " persisted - leaving it CLAIMED rather than closing it without the reason",
          lease.id(), rail, persistFailure);
      return;
    }
    paperEffects.refuseEntry(lease.id());
    log.info(
        "paper swing effect {} ({}) refused by the risk governor ({}) - no money effect, resolved"
            + " terminally so the session can complete: {}",
        lease.id(), lease.tradingsymbol(), rail, refusal.getMessage());
  }

  private void openPaperPosition(SignalTaken event) {
    // E10 sub-account: assigned inside PaperService under the book lock (see openScalperOrder).
    Optional<StraddleLegs.Pair> straddle =
        event.scalper()
            ? signals
                .find(event.signalId())
                .map(SignalRepository.SignalRow::scalperDetail)
                .flatMap(StraddleLegs::parse)
            : Optional.empty();
    if (straddle.isPresent()) {
      openStraddle(event, straddle.get(), event.scalper());
    } else {
      openSingle(event, event.scalper());
    }
  }

  /**
   * The single-leg open. A directional scalper take opens the PICKED OPTION (the {@code tradeable_*}
   * side-channel) BUY at its captured {@code option_ltp} — the instrument the strategy actually
   * trades, matching {@code LiveOrderService} (paper booked the index future before: PE scalps were
   * sign-inverted and hero-zero qtys landed on ~₹1cr future notional — audit P0-3). The persisted
   * SL/TP are index-future levels, so they are NOT passed as brackets on the option leg (wrong
   * basis — the instant-close kind of wrong); instead the YAML's {@code premium_pct} exit rules are
   * resolved against the option's own entry premium into bracket levels (P1-8 — §2.4's "premium
   * exits work on BOTH paths" made true live: {@code PaperBracketEvaluator} enforces them against
   * the option LTP, mirroring the backtest's {@code PremiumExitEvaluator}). The engine's structural
   * stop / time stop still close it via SignalExited — whichever fires first wins.
   * A non-scalper/manual take is unchanged: the signal's primary leg, with its same-basis SL/TP as
   * bracket levels so {@code PaperBracketEvaluator} backstops the position (audit P0-2).
   */
  private void openSingle(SignalTaken event, boolean scalper) {
    Optional<SignalRepository.SignalRow> row = signals.find(event.signalId());
    if (row.isPresent()
        && row.get().tradeableTradingsymbol() != null
        && row.get().scalperDetail() != null) {
      SignalRepository.SignalRow r = row.get();
      // Prefer the captured option premium: the auto-take's fillPrice is the FUTURE entry price
      // (AutoPaperListener mirrors a manual take), which is the wrong scale for the option leg.
      java.math.BigDecimal optionLtp = decimal(r.scalperDetail(), "option_ltp");
      PremiumBrackets brackets =
          optionLtp == null
              ? PremiumBrackets.NONE
              : premiumBrackets(r.strategyVersionId(), optionLtp);
      open(
          scalper,
          new PaperService.OrderRequest(
              event.signalId(), r.tradeableExchange(), r.tradeableTradingsymbol(), "BUY",
              event.qty(), optionLtp != null ? optionLtp : event.fillPrice(),
              brackets.stopLoss(), brackets.takeProfit(), null));
      return;
    }
    open(
        scalper,
        new PaperService.OrderRequest(
            event.signalId(), null, null, null, event.qty(), event.fillPrice(),
            row.map(SignalRepository.SignalRow::stopLoss).orElse(null),
            row.map(SignalRepository.SignalRow::target).orElse(null),
            null));
  }

  /**
   * A scalper entry routes through {@code openScalperOrder} so its sub-account is PICKED under the
   * same book lock that validates and writes it; everything else opens unchanged with a NULL key.
   */
  private void open(boolean scalper, PaperService.OrderRequest request) {
    if (scalper) {
      paper.openScalperOrder(request);
    } else {
      paper.openOrder(request);
    }
  }

  /** Option-premium bracket levels derived from the YAML's premium_pct exit rules. */
  record PremiumBrackets(java.math.BigDecimal stopLoss, java.math.BigDecimal takeProfit) {
    static final PremiumBrackets NONE = new PremiumBrackets(null, null);
  }

  /**
   * Resolves the version's {@code exit_rules} premium_pct percentages against the option entry
   * premium: a long option's {@code stop_loss 50} → SL = ltp×0.50; {@code take_profit 35} →
   * TP = ltp×1.35 (2dp HALF_UP). Non-premium_pct bases and absent rules yield no level — the
   * engine's structural/time exits still bound the position.
   */
  private PremiumBrackets premiumBrackets(java.util.UUID versionId, java.math.BigDecimal ltp) {
    try {
      var config = signals.versionConfig(versionId).orElse(null);
      if (config == null) {
        return PremiumBrackets.NONE;
      }
      java.math.BigDecimal sl = null;
      java.math.BigDecimal tp = null;
      for (com.fasterxml.jackson.databind.JsonNode rule : config.path("exit_rules")) {
        if (!"premium_pct".equals(rule.path("params").path("basis").asText())) {
          continue;
        }
        java.math.BigDecimal pct = decimal(rule.path("params"), "value");
        if (pct == null) {
          continue;
        }
        // §9-04: the ONE definition, shared with the backtest replay and PremiumBracketRules. The
        // exit-equivalence fixture called this a "genuine THIRD copy"; it is no longer a copy.
        String type = rule.path("type").asText();
        if ("stop_loss".equals(type)) {
          sl = in.arthayantra.strategyengine.eval.PremiumLevels.paiseRounded(ltp, pct, false);
        } else if ("take_profit".equals(type)) {
          tp = in.arthayantra.strategyengine.eval.PremiumLevels.paiseRounded(ltp, pct, true);
        }
      }
      return new PremiumBrackets(sl, tp);
    } catch (Exception e) {
      log.warn("premium bracket derivation failed for version {}: {}", versionId, e.getMessage());
      return PremiumBrackets.NONE;
    }
  }

  /** A decimal field off the scalper_detail JSON; null when absent/non-numeric. */
  private static java.math.BigDecimal decimal(com.fasterxml.jackson.databind.JsonNode root, String field) {
    com.fasterxml.jackson.databind.JsonNode n = root.path(field);
    if (n.isMissingNode() || n.isNull()) {
      return null;
    }
    try {
      return new java.math.BigDecimal(n.asText());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** Opens BOTH straddle legs (CE + PE) at the combined-premium lot count, linked to the signal. */
  private void openStraddle(SignalTaken event, StraddleLegs.Pair pair, boolean scalper) {
    // The lot comes from the CE leg's own instrument meta — both legs of a straddle share the
    // underlying's lot size. Without it combinedQty cannot floor to a whole lot (review C1).
    long lot =
        instruments == null
            ? 0L
            : instruments.meta(pair.ce().exchange(), pair.ce().tradingsymbol()).lotSize();
    int qty = StraddleLegs.combinedQty(event.qty(), pair.ce().ltp(), pair.pe().ltp(), lot);
    if (qty <= 0) {
      // A premium was missing, or the lot did not resolve — degrade to the single primary leg
      // rather than mis-size the pair.
      openSingle(event, scalper);
      return;
    }
    // ATOMIC: both legs or neither. A capital cap can now refuse the second leg, and a refused leg 2
    // with leg 1 already open is a naked directional position, not a straddle.
    PaperService.OrderRequest ce = legRequest(event.signalId(), pair.ce(), qty);
    PaperService.OrderRequest pe = legRequest(event.signalId(), pair.pe(), qty);
    if (scalper) {
      paper.openScalperPair(ce, pe);
    } else {
      paper.openPair(ce, pe);
    }
    log.info(
        "straddle 2-leg paper open: signal {} → CE {} + PE {} @ {} lots each",
        event.signalId(), pair.ce().tradingsymbol(), pair.pe().tradingsymbol(), qty);
  }

  private PaperService.OrderRequest legRequest(long signalId, StraddleLegs.Leg leg, int qty) {
    return new PaperService.OrderRequest(
        signalId, leg.exchange(), leg.tradingsymbol(), "BUY", qty, leg.ltp(), null, null, null);
  }
}
