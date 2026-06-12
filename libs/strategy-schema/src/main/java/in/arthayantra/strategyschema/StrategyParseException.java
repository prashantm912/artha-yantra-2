package in.arthayantra.strategyschema;

/** Thrown when a strategy document cannot be parsed at all (size cap, bad YAML, wrong root). */
public class StrategyParseException extends RuntimeException {

  /** With message only. */
  public StrategyParseException(String message) {
    super(message);
  }

  /** With cause. */
  public StrategyParseException(String message, Throwable cause) {
    super(message, cause);
  }
}
