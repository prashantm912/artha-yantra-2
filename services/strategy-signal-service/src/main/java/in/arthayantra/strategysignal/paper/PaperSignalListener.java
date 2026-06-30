package in.arthayantra.strategysignal.paper;

import in.arthayantra.strategysignal.signals.SignalRepository;
import in.arthayantra.strategysignal.signals.SignalTaken;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

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

  private final PaperService paper;
  private final ScalperAccountModel scalperAccounts;
  private final SignalRepository signals;

  /** Wires the ledger service, the 5-account sub-ledger + the signal store. */
  public PaperSignalListener(
      PaperService paper, ScalperAccountModel scalperAccounts, SignalRepository signals) {
    this.paper = paper;
    this.scalperAccounts = scalperAccounts;
    this.signals = signals;
  }

  /** Opens a position (or, for a straddle, both legs) from the signal when a qty was supplied. */
  @EventListener
  public void onSignalTaken(SignalTaken event) {
    if (event.qty() == null || event.qty() <= 0) {
      return;
    }
    try {
      // E10: a scalper take is charged to a round-robin sub-account (the per-account first-loss freeze
      // reads it); a non-scalper / manual take leaves the ledger key NULL. A straddle's TWO legs share
      // the SAME sub-account (one position).
      Integer subaccountIdx = event.scalper() ? scalperAccounts.nextFreeAccount() : null;
      Optional<StraddleLegs.Pair> straddle =
          event.scalper()
              ? signals
                  .find(event.signalId())
                  .map(SignalRepository.SignalRow::scalperDetail)
                  .flatMap(StraddleLegs::parse)
              : Optional.empty();
      if (straddle.isPresent()) {
        openStraddle(event, straddle.get(), subaccountIdx);
      } else {
        openSingle(event, subaccountIdx);
      }
    } catch (Exception e) {
      log.warn("paper position not opened for taken signal {}: {}", event.signalId(), e.getMessage());
    }
  }

  /** The legacy single-leg open — the signal's primary leg at its suggested qty. */
  private void openSingle(SignalTaken event, Integer subaccountIdx) {
    paper.openOrder(
        new PaperService.OrderRequest(
            event.signalId(), null, null, null, event.qty(), event.fillPrice(), null, null,
            subaccountIdx));
  }

  /** Opens BOTH straddle legs (CE + PE) at the combined-premium lot count, linked to the signal. */
  private void openStraddle(SignalTaken event, StraddleLegs.Pair pair, Integer subaccountIdx) {
    int qty = StraddleLegs.combinedQty(event.qty(), pair.ce().ltp(), pair.pe().ltp());
    if (qty <= 0) {
      // A premium was missing — degrade to the single primary leg rather than mis-size the pair.
      openSingle(event, subaccountIdx);
      return;
    }
    openLeg(event.signalId(), pair.ce(), qty, subaccountIdx);
    openLeg(event.signalId(), pair.pe(), qty, subaccountIdx);
    log.info(
        "straddle 2-leg paper open: signal {} → CE {} + PE {} @ {} lots each",
        event.signalId(), pair.ce().tradingsymbol(), pair.pe().tradingsymbol(), qty);
  }

  private void openLeg(long signalId, StraddleLegs.Leg leg, int qty, Integer subaccountIdx) {
    paper.openOrder(
        new PaperService.OrderRequest(
            signalId, leg.exchange(), leg.tradingsymbol(), "BUY", qty, leg.ltp(), null, null,
            subaccountIdx));
  }
}
