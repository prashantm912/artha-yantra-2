package in.arthayantra.marketdata.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The whole point of {@link CircuitBreakerDiagnostics} is that the OPEN line NAMES the failures.
 *
 * <p>Asserted on the RENDERED message rather than the format string: a format string cannot tell
 * you what its arguments expand to, and "the causes are in the line" is exactly the claim.
 */
class CircuitBreakerDiagnosticsTest {

  /** Opens after 4 failures of 4 — small enough to drive by hand, same shape as the real config. */
  private static final CircuitBreakerConfig CONFIG =
      CircuitBreakerConfig.custom()
          .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
          .slidingWindowSize(4)
          .minimumNumberOfCalls(4)
          .failureRateThreshold(50)
          .build();

  private ListAppender<ILoggingEvent> logs;
  private Logger diagnosticsLogger;
  private CircuitBreakerRegistry registry;

  @BeforeEach
  void attachAppender() {
    logs = new ListAppender<>();
    logs.start();
    diagnosticsLogger = (Logger) LoggerFactory.getLogger(CircuitBreakerDiagnostics.class);
    diagnosticsLogger.addAppender(logs);
    registry = CircuitBreakerRegistry.of(CONFIG);
    // ⚠️ Created BEFORE the diagnostics, on purpose: it is the only way the two registration paths
    // stay distinguishable. An empty registry makes every breaker lazily-created, so a test named
    // "created after startup" would pass for the same reason as its sibling and prove nothing --
    // which is exactly what the red-proof caught, by reddening both at once.
    registry.circuitBreaker("kite-rest");
    new CircuitBreakerDiagnostics(
        registry, Clock.fixed(Instant.parse("2026-08-27T03:43:00Z"), ZoneOffset.UTC));
  }

  @AfterEach
  void detachAppender() {
    diagnosticsLogger.detachAppender(logs);
  }

  private static void failFourTimes(CircuitBreaker breaker, String message) {
    for (int i = 0; i < 4; i++) {
      try {
        breaker.executeCallable(
            () -> {
              throw new IOException(message);
            });
      } catch (Exception expected) {
        // throwing IS the point of the call
      }
    }
  }

  private List<String> messages(Level level) {
    return logs.list.stream()
        .filter(event -> event.getLevel() == level)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
  }

  @Test
  void theOpenLineNamesTheFailuresThatCausedIt() {
    CircuitBreaker breaker = registry.circuitBreaker("kite-rest");

    failFourTimes(breaker, "Connection reset by peer");

    assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    assertThat(messages(Level.WARN))
        .singleElement(InstanceOfAssertFactories.STRING)
        .contains("kite-rest")
        .contains("IOException")
        .contains("Connection reset by peer");
  }

  /**
   * A breaker created AFTER startup must still be instrumented. Breakers are created lazily on
   * first use, so instrumenting only what exists at construction would miss most of them — and the
   * miss would be silent, since an uninstrumented breaker simply logs nothing.
   */
  @Test
  void aBreakerCreatedAfterStartupIsStillInstrumented() {
    CircuitBreaker later = registry.circuitBreaker("created-later");

    failFourTimes(later, "late breaker");

    assertThat(messages(Level.WARN))
        .singleElement(InstanceOfAssertFactories.STRING)
        .contains("created-later")
        .contains("late breaker");
  }

  /**
   * Individual failures must NOT be logged — only the summary on OPEN. A line per failed call would
   * flood the log at exactly the moment it is most needed.
   */
  @Test
  void failuresBelowTheThresholdAreSilent() {
    CircuitBreaker breaker = registry.circuitBreaker("kite-rest");

    try {
      breaker.executeCallable(
          () -> {
            throw new IOException("one blip");
          });
    } catch (Exception expected) {
      // throwing IS the point of the call
    }

    assertThat(logs.list).isEmpty();
  }
}
