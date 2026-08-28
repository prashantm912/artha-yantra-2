package in.arthayantra.marketdata.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Makes a circuit breaker say WHY it opened.
 *
 * <p><b>The gap this closes, measured 2026-08-27.</b> The {@code kite-rest} breaker was open in
 * bursts across a whole session — 564 {@code circuit open; serving cached data} lines, clustered
 * 09:13–09:58, 10:17–10:18 and 15:45 — and it cost 19 minutes of {@code futures_oi} capture. The
 * cause could not be established from the logs, and not for want of looking: <b>every one of those
 * lines is a CONSUMER observing an already-open breaker, never the moment it opened.</b> The first
 * line of the 09:13 cluster had no Kite failure logged before it at all.
 *
 * <p>What existed already was not enough, and it is worth being precise about why:
 *
 * <ul>
 *   <li>{@code resilience4j_circuitbreaker_state} is a GAUGE — it says what the state is when you
 *       scrape it, so a breaker that opens and recovers between scrapes is invisible.
 *   <li>{@code resilience4j_circuitbreaker_calls_seconds_count{kind="failed"}} counts failures but
 *       names neither the exception nor the moment.
 *   <li>Both reset on restart. Today's counters were lost to the evening's deploys before anything
 *       could be read off them.
 * </ul>
 *
 * <p>So this listens to the registry's own events and logs the two facts the metrics cannot carry:
 * every state TRANSITION, and — on the transition to OPEN — the recent failures that caused it.
 *
 * <p><b>Why it keeps its own ring of causes.</b> resilience4j does not retain the throwables behind
 * its failure rate, so by the time OPEN fires the reasons are gone. A small per-breaker ring buffer
 * is the only way to answer "what opened it" in the same log line that reports the opening.
 *
 * <p><b>Volume.</b> Transitions are rare by construction. Individual failure events are NOT logged;
 * they are only recorded in the ring and printed once, when a transition to OPEN actually happens.
 * A degraded upstream therefore produces one summary per open episode rather than a line per call —
 * the alternative would flood exactly when the log is most needed.
 *
 * <p>Observability only: it registers listeners and logs. It changes no breaker behaviour, no
 * threshold and no call path.
 */
@Component
public class CircuitBreakerDiagnostics {

  private static final Logger log = LoggerFactory.getLogger(CircuitBreakerDiagnostics.class);

  /** How many recent failure causes to keep per breaker. The window that opens it is 10 calls. */
  private static final int CAUSES_KEPT = 10;

  /**
   * Ignored errors are counted, never silently dropped.
   *
   * <p>WARNING: resilience4j fires {@code onIgnoredError}, NOT {@code onError}, for an exception
   * named in {@code ignore-exceptions}. So the moment 403 was added to that list, this class
   * would have gone silent on the very failures it was written to expose -- the boot-time
   * TokenException burst. A fix that hides its own evidence is worse than the defect.
   */
  private static final String IGNORED_TOTAL = "ay_circuit_breaker_ignored_total";

  private final Clock clock;

  private final MeterRegistry meters;

  /** Per-breaker ring of recent failure causes. Guarded by its own monitor; writes are rare. */
  private final java.util.Map<String, Deque<String>> recentCauses = new java.util.concurrent.ConcurrentHashMap<>();

  public CircuitBreakerDiagnostics(
      CircuitBreakerRegistry registry, Clock clock, MeterRegistry meters) {
    this.clock = clock;
    this.meters = meters;
    registry.getAllCircuitBreakers().forEach(this::instrument);
    // Breakers are created lazily on first use, so instrumenting only what exists at startup would
    // miss any breaker whose first call happens later — which is most of them.
    registry.getEventPublisher().onEntryAdded(added -> instrument(added.getAddedEntry()));
  }

  private void instrument(CircuitBreaker breaker) {
    String name = breaker.getName();
    breaker
        .getEventPublisher()
        .onError(
            event -> {
              Throwable cause = event.getThrowable();
              record(
                  name,
                  OffsetDateTime.now(clock).toLocalTime().withNano(0)
                      + " "
                      + cause.getClass().getSimpleName()
                      + ": "
                      + summarise(cause));
            })
        .onIgnoredError(
            event -> {
              Throwable cause = event.getThrowable();
              meters
                  .counter(
                      IGNORED_TOTAL, "name", name, "exception", cause.getClass().getSimpleName())
                  .increment();
              // DEBUG, not WARN: a pre-login boot produces these in bursts by design, and a
              // warning per call would train the reader to skip the log. The COUNTER is the
              // signal; this line is for when someone is already looking.
              log.debug(
                  "circuit breaker {} ignored {}: {}",
                  name,
                  cause.getClass().getSimpleName(),
                  summarise(cause));
            })
        .onStateTransition(
            event -> {
              CircuitBreaker.Metrics metrics = breaker.getMetrics();
              if (event.getStateTransition().getToState() == CircuitBreaker.State.OPEN) {
                log.warn(
                    "circuit breaker {} {} — failure rate {}% over {} calls. Recent failures: [{}]",
                    name,
                    event.getStateTransition(),
                    metrics.getFailureRate(),
                    metrics.getNumberOfBufferedCalls(),
                    String.join(" | ", drain(name)));
              } else {
                log.info(
                    "circuit breaker {} {} — failure rate {}% over {} calls",
                    name,
                    event.getStateTransition(),
                    metrics.getFailureRate(),
                    metrics.getNumberOfBufferedCalls());
              }
            });
  }

  /** First line only: a stack trace in a log message buries the fact it is carrying. */
  private static String summarise(Throwable cause) {
    String message = cause.getMessage();
    if (message == null || message.isBlank()) {
      return "(no message)";
    }
    int newline = message.indexOf('\n');
    String firstLine = newline < 0 ? message : message.substring(0, newline);
    return firstLine.length() <= 200 ? firstLine : firstLine.substring(0, 200) + "…";
  }

  private void record(String name, String cause) {
    Deque<String> ring = recentCauses.computeIfAbsent(name, ignored -> new ArrayDeque<>());
    synchronized (ring) {
      ring.addLast(cause);
      while (ring.size() > CAUSES_KEPT) {
        ring.removeFirst();
      }
    }
  }

  private java.util.List<String> drain(String name) {
    Deque<String> ring = recentCauses.get(name);
    if (ring == null) {
      return java.util.List.of("none recorded");
    }
    synchronized (ring) {
      java.util.List<String> snapshot = ring.stream().collect(Collectors.toList());
      ring.clear();
      return snapshot.isEmpty() ? java.util.List.of("none recorded") : snapshot;
    }
  }
}
