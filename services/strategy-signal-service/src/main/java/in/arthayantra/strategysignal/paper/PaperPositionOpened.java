package in.arthayantra.strategysignal.paper;

/**
 * Published after a paper position is opened or averaged into (inside {@link PaperService#openOrder}).
 * The F9 margin annotator listens AFTER_COMMIT to price the position's broker-real SPAN margin
 * (fail-soft, off the ledger transaction) and stamp {@code margin_snapshot}/{@code margin_pct} — an
 * advisory-only annotation that never touches the fill or the position's qty. The event lives in the
 * publisher's module (paper) and its only consumer is in paper too, so the module graph stays acyclic.
 */
public record PaperPositionOpened(
    long positionId, String book, String exchange, String tradingsymbol, String side, long qty) {}
