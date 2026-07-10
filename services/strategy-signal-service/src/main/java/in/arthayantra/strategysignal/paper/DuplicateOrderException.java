package in.arthayantra.strategysignal.paper;

import in.arthayantra.strategysignal.paper.PaperService.PositionDto;

/**
 * V2 idempotency signal: a MANUAL order submission carried a {@code clientOrderId} that was already
 * filled (a network retry / double-click, or a concurrent duplicate that lost the unique-index race).
 * Carries the ORIGINAL position so {@code PaperController} can return it instead of a second fill.
 *
 * <p>Caught only in {@code PaperController.order}; never reaches the {@code GlobalExceptionHandler}
 * (it is not an {@code ApiException} — it maps to a 409 whose body is the original position DTO, not
 * an error envelope, per audit §8 V2: "409 replay returns the original order").
 */
public class DuplicateOrderException extends RuntimeException {

  private final transient PositionDto original;

  /** Wraps the original position an idempotent replay must return. */
  public DuplicateOrderException(PositionDto original) {
    super("duplicate clientOrderId; returning the original position " + original.id());
    this.original = original;
  }

  /** The position the original submission opened. */
  public PositionDto original() {
    return original;
  }
}
