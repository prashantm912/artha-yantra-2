package in.arthayantra.strategyschema;

import java.util.List;

/**
 * Combined schema + semantic outcome. {@code errors} block save/publish; {@code warnings} are
 * lints (bare positional optimize paths, optional indicators that can never activate) surfaced
 * to the editor but never blocking.
 */
public record ValidationResult(
    boolean valid, List<ValidationIssue> errors, List<ValidationIssue> warnings) {

  /** A passing result with optional lints. */
  public static ValidationResult ok(List<ValidationIssue> warnings) {
    return new ValidationResult(true, List.of(), List.copyOf(warnings));
  }

  /** A failing result. */
  public static ValidationResult failed(
      List<ValidationIssue> errors, List<ValidationIssue> warnings) {
    return new ValidationResult(false, List.copyOf(errors), List.copyOf(warnings));
  }
}
