package in.arthayantra.strategysignal.signals;

import in.arthayantra.strategysignal.scalper.ScalperConfluenceGate;
import in.arthayantra.strategysignal.scalper.StrikePicker;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The shadow book (signal-analysis §7.1/§7.2): opens a REJECTED scalper entry as a virtual 1-lot
 * long-premium position so the exit machinery labels it with a real PnL — the evidence base for
 * tuning the entry gates ("rail X keeps blocking winners" vs "rail X earns its keep").
 *
 * <p>Eligibility (v1): the composite PASSED its threshold (the would-have-fired class — a rail
 * other than the confluence vetoed the entry; optionally widened via {@code min-composite}), the
 * StrikePicker resolved a leg with a live premium, and no OPEN shadow already exists for the same
 * strategy+side (dedup — the same blocked setup re-evaluates every primary bar). A global open cap
 * bounds the book. Default-OFF ({@code artha.scalper.shadow-book.enabled}); LIVE-only by
 * construction (only the live engine persists rejections). Failures never propagate — the shadow
 * book must never break the live signal path.
 */
@Component
public class ShadowBookService {

  private static final Logger log = LoggerFactory.getLogger(ShadowBookService.class);

  private final ShadowPositionRepository shadows;
  private final SignalRepository signals;
  private final boolean enabled;
  private final BigDecimal minComposite;
  private final long maxOpen;

  /** Wires the shadow ledger + the version-config source for the premium brackets. */
  public ShadowBookService(
      ShadowPositionRepository shadows,
      SignalRepository signals,
      @Value("${artha.scalper.shadow-book.enabled:false}") boolean enabled,
      @Value("${artha.scalper.shadow-book.min-composite:#{null}}") BigDecimal minComposite,
      @Value("${artha.scalper.shadow-book.max-open:50}") long maxOpen) {
    this.shadows = shadows;
    this.signals = signals;
    this.enabled = enabled;
    this.minComposite = minComposite;
    this.maxOpen = maxOpen;
  }

  /**
   * Opens a shadow position for an eligible rejection; returns the shadow id or null when not
   * eligible (disabled / no leg / composite short / dedup / cap). Never throws.
   */
  public Long maybeOpen(
      long rejectionId,
      UUID strategyVersionId,
      String strategySlug,
      String signalExchange,
      String signalTradingsymbol,
      OffsetDateTime barTime,
      ScalperConfluenceGate.RejectionDiagnostic d) {
    if (!enabled || d == null) {
      return null;
    }
    try {
      if (d.side() == null || d.pick() == null || d.underlying() == null) {
        return null; // blocked before the leg was resolvable (time-window / chain / straddle path)
      }
      StrikePicker.Candidate leg = d.pick().candidate();
      BigDecimal entryLtp = leg.ltp();
      if (entryLtp == null || entryLtp.signum() <= 0) {
        return null;
      }
      BigDecimal floor = minComposite != null ? minComposite : d.compositeThreshold();
      if (d.compositeScore() == null || floor == null || d.compositeScore().compareTo(floor) < 0) {
        return null; // not a would-have-fired-class entry — PnL would label noise
      }
      String side = d.side().name();
      if (shadows.hasOpen(strategySlug, side)) {
        return null; // one live experiment per strategy+side at a time
      }
      if (shadows.countOpen() >= maxOpen) {
        log.warn("shadow book at max-open cap {} — skipping rejection {}", maxOpen, rejectionId);
        return null;
      }
      PremiumBracketRules.Brackets brackets =
          PremiumBracketRules.resolve(
              signals.versionConfig(strategyVersionId).orElse(null), entryLtp);
      long id =
          shadows.insert(
              rejectionId, strategyVersionId, strategySlug, side, d.underlying(),
              optionExchange(d.underlying()), leg.tradingsymbol(), leg.strike(), d.expiry(),
              entryLtp, brackets.stopLoss(), brackets.takeProfit(), d.structuralStop(),
              signalExchange, signalTradingsymbol, d.blockingRail(), d.compositeScore(), barTime);
      log.info(
          "shadow open: {} {} {} @ {} (rejection {} rail={} composite={}) sl={} tp={} structStop={}",
          strategySlug, side, leg.tradingsymbol(), entryLtp, rejectionId, d.blockingRail(),
          d.compositeScore(), brackets.stopLoss(), brackets.takeProfit(), d.structuralStop());
      return id;
    } catch (RuntimeException e) {
      log.warn("shadow open failed for rejection {}: {}", rejectionId, e.toString());
      return null;
    }
  }

  /** The option leg's derivatives exchange from the option root (BSE roots trade on BFO). */
  static String optionExchange(String underlying) {
    String u = underlying == null ? "" : underlying.toUpperCase(java.util.Locale.ROOT);
    return u.startsWith("SENSEX") || u.startsWith("BANKEX") ? "BFO" : "NFO";
  }
}
