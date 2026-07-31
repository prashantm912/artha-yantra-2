package in.arthayantra.strategysignal.signals;

import java.time.OffsetDateTime;

/** Typed signals-to-notifier event emitted by the strategy coverage watchdog. */
public record StrategyCoverageAlert(
    Kind kind,
    String slug,
    StrategyCoverageSnapshot.Classification classification,
    long generation,
    long requestedGeneration,
    long completedGeneration,
    OffsetDateTime reloadTimestamp,
    StrategyCoverageSnapshot.TerminalState terminalState) {

  public enum Kind {
    STRATEGY_DARK,
    SNAPSHOT_MISSING
  }

  public String title() {
    return switch (kind) {
      case STRATEGY_DARK -> "ArthaYantra strategy dark: " + slug;
      case SNAPSHOT_MISSING -> "ArthaYantra strategy coverage: SNAPSHOT_MISSING";
    };
  }

  public String message() {
    return switch (kind) {
      case STRATEGY_DARK ->
          "slug=" + slug
              + " classification=" + classification
              + " generation=" + generation
              + " reloadTimestamp=" + reloadTimestamp
              + " terminalState=" + terminalState;
      case SNAPSHOT_MISSING ->
          "requestedGeneration=" + requestedGeneration
              + " completedGeneration=" + completedGeneration
              + " reloadTimestamp=" + reloadTimestamp;
    };
  }
}
