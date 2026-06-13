package in.arthayantra.strategysignal.signals;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * In-process domain event (Phase 41 / Q6): an ENTRY signal was just emitted. The notifier module
 * subscribes via {@code @EventListener} — the in-process, at-most-once-per-signal push trigger
 * (distinct from the fire-and-forget Redis {@code signals} channel the WS bridge consumes). Carries
 * the payload fields so the notifier never re-queries; it looks up only the strategy's opt-in.
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
    BigDecimal threshold) {}
