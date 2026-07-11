package in.arthayantra.strategysignal.signals;

/**
 * One funnel name the slot cap dropped on an over-subscribed swing batch (ledger F3). {@code
 * admissionRank} is the name's 1-based position in the RS-priority admission order (the funnel is
 * walked buyable RS-desc, then on-deck RS-desc, until the book's slots fill), so a dropped name's rank
 * IS its RS-priority position — the discriminator for the offline FIFO-vs-RS net-vs-net read.
 *
 * <p>Defined in the {@code signals} module (not {@code swing}) so the {@link SwingBatchRunRepository}
 * can persist/read it without a signals&#8594;swing import (which would cycle with swing&#8594;signals).
 * The {@code SwingBatchEngine} produces these from the {@code swing} side.
 */
public record DroppedCandidate(String symbol, int admissionRank) {}
