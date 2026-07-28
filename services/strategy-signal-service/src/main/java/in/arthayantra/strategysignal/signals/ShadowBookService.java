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
 * <p>Two book kinds share the ledger, told apart by {@code variant} (roadmap F1):
 *
 * <ul>
 *   <li><b>champion</b> — the original book: the composite PASSED its threshold (the
 *       would-have-fired class — a rail other than the confluence vetoed the entry; optionally
 *       widened via {@code min-composite}).
 *   <li><b>challenger variants</b> ({@link ShadowVariants}) — the same diagnostic re-scored under a
 *       config diff (rail threshold override / rail disable / composite floor); a variant that
 *       ACCEPTS the entry opens its own tagged position, so knob proposals earn real PnL labels
 *       side by side on identical market data.
 * </ul>
 *
 * <p>Common eligibility: the StrikePicker resolved a leg with a live premium. Dedup is one OPEN per
 * (strategy, side, variant) — the same blocked setup re-evaluates every primary bar; the open cap
 * bounds each book independently. Default-OFF ({@code artha.scalper.shadow-book.enabled});
 * LIVE-only by construction (only the live engine persists rejections). Failures never propagate —
 * the shadow book must never break the live signal path.
 */
@Component
public class ShadowBookService {

  private static final Logger log = LoggerFactory.getLogger(ShadowBookService.class);

  private final ShadowPositionRepository shadows;
  private final SignalRepository signals;
  private final ShadowVariantRegistry variants;
  private final boolean enabled;
  private final BigDecimal minComposite;
  private final long maxOpen;

  /** Wires the shadow ledger + the version-config source for the premium brackets. */
  public ShadowBookService(
      ShadowPositionRepository shadows,
      SignalRepository signals,
      ShadowVariantRegistry variants,
      @Value("${artha.scalper.shadow-book.enabled:false}") boolean enabled,
      @Value("${artha.scalper.shadow-book.min-composite:#{null}}") BigDecimal minComposite,
      @Value("${artha.scalper.shadow-book.max-open:50}") long maxOpen) {
    this.shadows = shadows;
    this.signals = signals;
    this.variants = variants;
    this.enabled = enabled;
    this.minComposite = minComposite;
    this.maxOpen = maxOpen;
  }

  /**
   * Opens the champion shadow (when the rejection is would-have-fired class) plus every challenger
   * variant that accepts it; returns the champion's shadow id or null. Never throws.
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
      Long championId = null;
      BigDecimal floor = minComposite != null ? minComposite : d.compositeThreshold();
      if (d.compositeScore() != null && floor != null && d.compositeScore().compareTo(floor) >= 0) {
        championId =
            openBook(
                ShadowVariants.CHAMPION, rejectionId, strategyVersionId, strategySlug,
                signalExchange, signalTradingsymbol, barTime, d, leg, entryLtp);
      }
      for (ShadowVariants.Variant v : variants.active()) {
        if (ShadowVariants.accepts(d, v)) {
          openBook(
              v.name(), rejectionId, strategyVersionId, strategySlug,
              signalExchange, signalTradingsymbol, barTime, d, leg, entryLtp);
        }
      }
      return championId;
    } catch (RuntimeException e) {
      log.warn("shadow open failed for rejection {}: {}", rejectionId, e.toString());
      return null;
    }
  }

  private Long openBook(
      String variant,
      long rejectionId,
      UUID strategyVersionId,
      String strategySlug,
      String signalExchange,
      String signalTradingsymbol,
      OffsetDateTime barTime,
      ScalperConfluenceGate.RejectionDiagnostic d,
      StrikePicker.Candidate leg,
      BigDecimal entryLtp) {
    String side = d.side().name();
    if (shadows.hasOpen(strategySlug, side, variant)) {
      return null; // one live experiment per strategy+side+book at a time
    }
    if (shadows.countOpen(variant) >= maxOpen) {
      log.warn("shadow book '{}' at max-open cap {} — skipping rejection {}", variant, maxOpen, rejectionId);
      return null;
    }
    PremiumBracketRules.Brackets brackets =
        PremiumBracketRules.resolve(
            signals.versionConfig(strategyVersionId).orElse(null), entryLtp);
    // The leg's exchange is the instrument master's, carried on the picked candidate — the same
    // canonical value the real ENTRY stamps. It used to be a prefix guess off the underlying's name
    // (task_032bff42); that helper is gone, and a candidate cannot exist without an exchange because
    // MarketOiClient drops such a leg at the chain boundary. Nothing to fall back to, by design.
    long id =
        shadows.insert(
            rejectionId, strategyVersionId, strategySlug, side, d.underlying(),
            leg.exchange(), leg.tradingsymbol(), leg.strike(), d.expiry(),
            entryLtp, brackets.stopLoss(), brackets.takeProfit(), d.structuralStop(),
            signalExchange, signalTradingsymbol, d.blockingRail(), d.compositeScore(), barTime,
            variant);
    log.info(
        "shadow open [{}]: {} {} {} @ {} (rejection {} rail={} composite={}) sl={} tp={} structStop={}",
        variant, strategySlug, side, leg.tradingsymbol(), entryLtp, rejectionId, d.blockingRail(),
        d.compositeScore(), brackets.stopLoss(), brackets.takeProfit(), d.structuralStop());
    return id;
  }
}
