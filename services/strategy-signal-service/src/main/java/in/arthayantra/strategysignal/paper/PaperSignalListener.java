package in.arthayantra.strategysignal.paper;

import in.arthayantra.strategysignal.signals.SignalRepository;
import in.arthayantra.strategysignal.signals.SignalTaken;
import in.arthayantra.strategysignal.signals.SwingPaperEffectRepository;
import in.arthayantra.strategysignal.signals.SwingPaperEffectRetry;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
  private final SwingPaperEffectRepository paperEffects;
  private final InstrumentMetaClient instruments;

  /** Wires the ledger service, the 5-account sub-ledger, the signal store + the instrument master. */
  @Autowired
  public PaperSignalListener(
      PaperService paper,
      ScalperAccountModel scalperAccounts,
      SignalRepository signals,
      SwingPaperEffectRepository paperEffects,
      InstrumentMetaClient instruments) {
    this.paper = paper;
    this.scalperAccounts = scalperAccounts;
    this.signals = signals;
    this.paperEffects = paperEffects;
    this.instruments = instruments;
  }

  /** Backwards-compatible constructor for focused paper-listener tests. */
  public PaperSignalListener(
      PaperService paper,
      ScalperAccountModel scalperAccounts,
      SignalRepository signals,
      SwingPaperEffectRepository paperEffects) {
    this(paper, scalperAccounts, signals, paperEffects, null);
  }

  /** Backwards-compatible constructor for focused paper-listener tests. */
  public PaperSignalListener(
      PaperService paper, ScalperAccountModel scalperAccounts, SignalRepository signals) {
    this(paper, scalperAccounts, signals, null, null);
  }

  /** Opens a position (or, for a straddle, both legs) from the signal when a qty was supplied. */
  @EventListener
  public void onSignalTaken(SignalTaken event) {
    if (event.qty() == null || event.qty() <= 0) {
      return;
    }
    try {
      Optional<SwingPaperEffectRepository.Effect> swingEffect =
          paperEffects == null ? Optional.empty() : paperEffects.findOpenBySignal(event.signalId());
      if (swingEffect.isPresent()) {
        String decision = swingEffect.get().decision();
        if ("REQUIRED".equals(decision)) {
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
    }
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
      // Leave CLAIMED. The next catch-up repair can reclaim the stale lease after read-back.
      log.warn("paper swing effect {} failed: {}", lease.id(), e.getMessage());
    }
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
