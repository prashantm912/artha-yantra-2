package in.arthayantra.strategysignal.signals;

/**
 * In-process event: the engine emitted an EXIT resolving an ENTRY anchor. {@code reason} is the
 * exit cause in the paper ledger's {@code close_reason} taxonomy (STRUCTURAL_STOP, CONFLUENCE_FLIP,
 * STOP_LOSS, TAKE_PROFIT, TRAILING_STOP, TIME_STOP, SIGNAL_EXIT). The paper module listens and
 * closes any open position linked to the anchor — without this, a TAKEN entry's position outlived
 * every engine exit (audit P0-2).
 */
public record SignalExited(long anchorSignalId, long exitSignalId, String reason) {}
