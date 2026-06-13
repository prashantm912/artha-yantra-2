package in.arthayantra.strategyengine.fills;

/**
 * Fill reference-price timing (A9 [FP-6]). {@code NEXT_OPEN} is the default for every style;
 * {@code AT_CLOSE} (BTST default) fills at the signal bar's close to capture the overnight gap that
 * a next-open fill structurally misses. Live uses next-tick LTP / signal-bar close to match.
 */
public enum FillTiming {
  NEXT_OPEN,
  AT_CLOSE
}
