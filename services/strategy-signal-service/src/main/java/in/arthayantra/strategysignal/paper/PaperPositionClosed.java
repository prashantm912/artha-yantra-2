package in.arthayantra.strategysignal.paper;

import java.math.BigDecimal;

/**
 * Published after a paper position is closed (the single {@code doSettle} choke point — manual
 * close, the 15:45 sweep, and expiry settlement all route through it). The journal module listens
 * AFTER_COMMIT to auto-stub a journal entry; the event lives in the publisher's module (paper) so
 * the consumer (journal) depends on paper, mirroring the SignalTaken precedent and keeping the
 * module graph acyclic.
 */
public record PaperPositionClosed(long positionId, BigDecimal realized, String closeReason) {}
