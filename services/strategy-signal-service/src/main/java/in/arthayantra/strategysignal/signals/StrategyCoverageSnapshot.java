package in.arthayantra.strategysignal.signals;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The total load classification produced by one {@link SignalEngine} reload.
 *
 * <p>This is load coverage, not signal or evaluation liveness. The snapshot deliberately retains
 * the identity of strategies that the engine skips before they can become {@link
 * SignalEngine.Loaded} entries, so a partial reload cannot look healthy merely because the loaded
 * list is non-empty.
 */
public record StrategyCoverageSnapshot(
    long generation,
    long requestedGeneration,
    long completedGeneration,
    OffsetDateTime reloadTimestamp,
    TerminalState terminalState,
    Map<String, Classification> classifications) {

  /** One terminal outcome for an enabled strategy with a published pointer. */
  public enum Classification {
    RESOLVED,
    RESOLVED_EMPTY,
    NOT_APPLICABLE_SWING,
    UNRESOLVED,
    NOT_LIVE_RESOLVABLE,
    MISSING_VERSION_ROW,
    NO_BOUNDING_EXIT,
    NOT_ROLLABLE_PRIMARY,
    LOAD_ERROR;

    /** True for outcomes that mean the strategy is not safely live-covered. */
    public boolean abnormal() {
      return switch (this) {
        case RESOLVED, RESOLVED_EMPTY, NOT_APPLICABLE_SWING -> false;
        case UNRESOLVED,
            NOT_LIVE_RESOLVABLE,
            MISSING_VERSION_ROW,
            NO_BOUNDING_EXIT,
            NOT_ROLLABLE_PRIMARY,
            LOAD_ERROR -> true;
      };
    }
  }

  /** Snapshot lifecycle. {@link #IN_FLIGHT} is published before a reload body starts. */
  public enum TerminalState {
    IN_FLIGHT,
    HEALTHY,
    DEGRADED_TERMINAL
  }

  public StrategyCoverageSnapshot {
    classifications =
        Collections.unmodifiableMap(new LinkedHashMap<>(classifications == null ? Map.of() : classifications));
  }

  /** Creates the marker that makes an aborted reload independently observable. */
  static StrategyCoverageSnapshot inFlight(
      long generation, long completedGeneration, OffsetDateTime requestedAt) {
    return new StrategyCoverageSnapshot(
        generation,
        generation,
        completedGeneration,
        requestedAt,
        TerminalState.IN_FLIGHT,
        Map.of());
  }

  /** Whether this snapshot is the completed snapshot for the currently requested generation. */
  public boolean completedCurrentGeneration() {
    return terminalState != TerminalState.IN_FLIGHT
        && generation == requestedGeneration
        && requestedGeneration == completedGeneration;
  }
}
