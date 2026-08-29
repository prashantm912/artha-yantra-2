package in.arthayantra.strategysignal.paper;

/**
 * Published after a paper position is opened or averaged into (inside {@link PaperService#openOrder}).
 * The F9 margin annotator listens AFTER_COMMIT to price the position's broker-real SPAN margin
 * (fail-soft, off the ledger transaction) and stamp {@code margin_snapshot}/{@code margin_pct} — an
 * advisory-only annotation that never touches the fill or the position's qty. The event lives in the
 * publisher's module (paper) and its only consumer is in paper too, so the module graph stays acyclic.
 */
/**
 * ⚠️ {@code instrumentClass} was added for H44 and is NOT decoration: a consumer that reasons
 * about TICKS must be able to tell an option from a cash equity, because EQUITIES DO NOT TICK.
 * {@code PaperService.countMtmBlindPositions} records that a tick-only check once counted all 18
 * cash-equity swing positions and became useless as a signal; {@link NoTickFillListener} would
 * have repeated that defect exactly (cross-vendor review, round 2) without this component.
 */
public record PaperPositionOpened(
    long positionId,
    String book,
    String exchange,
    String tradingsymbol,
    String side,
    long qty,
    in.arthayantra.strategyengine.fills.InstrumentClass instrumentClass) {}

