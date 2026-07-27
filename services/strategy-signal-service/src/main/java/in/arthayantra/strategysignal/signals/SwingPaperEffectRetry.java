package in.arthayantra.strategysignal.signals;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * In-process retry command for one already-claimed swing paper effect. The swing module publishes this
 * record; the paper module performs the effect, keeping the module edge one-way (signals -> paper).
 */
public record SwingPaperEffectRetry(
    String batch,
    LocalDate sessionDate,
    Kind kind,
    long signalId,
    Integer qty,
    BigDecimal fillPrice,
    boolean scalper,
    long anchorSignalId,
    long exitSignalId,
    String reason,
    BigDecimal price) {

  public enum Kind {
    ENTRY,
    EXIT
  }

  public static SwingPaperEffectRetry entry(
      String batch, LocalDate sessionDate, long signalId, Integer qty, BigDecimal fillPrice,
      boolean scalper) {
    return new SwingPaperEffectRetry(
        batch, sessionDate, Kind.ENTRY, signalId, qty, fillPrice, scalper, 0L, 0L, null, null);
  }

  public static SwingPaperEffectRetry exit(
      String batch, LocalDate sessionDate, long anchorSignalId, long exitSignalId,
      String reason, BigDecimal price) {
    return new SwingPaperEffectRetry(
        batch, sessionDate, Kind.EXIT, 0L, null, null, false, anchorSignalId, exitSignalId,
        reason, price);
  }
}
