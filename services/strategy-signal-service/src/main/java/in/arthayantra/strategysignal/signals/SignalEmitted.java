package in.arthayantra.strategysignal.signals;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * In-process domain event (Phase 41 / Q6): an ENTRY signal was just emitted. The notifier module
 * subscribes via {@code @EventListener} — the in-process, at-most-once-per-signal push trigger
 * (distinct from the fire-and-forget Redis {@code signals} channel the WS bridge consumes). Carries
 * the payload fields so the notifier never re-queries; it looks up only the strategy's opt-in.
 *
 * <p>{@code scalp} is a LIVE-only side-channel ({@code null} for every non-scalper signal): it
 * carries the option leg/strike/confluence the {@code ScalperConfluenceGate} picked at entry so the
 * scalp-alert listener (Z1) can format a rich alert without re-querying. It is populated only on the
 * live emit path — deterministic replay never publishes {@link SignalEmitted}, so it cannot affect
 * the golden vectors.
 */
public record SignalEmitted(
    long signalId,
    UUID strategyVersionId,
    String exchange,
    String tradingsymbol,
    String side,
    BigDecimal entryPrice,
    BigDecimal stopLoss,
    BigDecimal target,
    BigDecimal composite,
    BigDecimal threshold,
    ScalpDetail scalp) {

  /**
   * The scalper option leg + confluence captured at entry (the V009 side-channel, distilled to the
   * fields a scalp alert renders). {@code optionSide} is CE/PE; the BUY/SELL on the index future is
   * the enclosing event's {@link #side()}.
   */
  public record ScalpDetail(
      String underlying,
      String optionSide,
      BigDecimal strike,
      String tradeable,
      BigDecimal optionLtp,
      BigDecimal confluence,
      String ohTier,
      Integer ohProbPct) {}

  /** Back-compat constructor for non-scalper emits (no scalp side-channel). */
  public SignalEmitted(
      long signalId,
      UUID strategyVersionId,
      String exchange,
      String tradingsymbol,
      String side,
      BigDecimal entryPrice,
      BigDecimal stopLoss,
      BigDecimal target,
      BigDecimal composite,
      BigDecimal threshold) {
    this(
        signalId, strategyVersionId, exchange, tradingsymbol, side, entryPrice, stopLoss, target,
        composite, threshold, null);
  }
}
