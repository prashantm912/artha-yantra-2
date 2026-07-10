package in.arthayantra.strategysignal.signals;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Durable, recreate-proof telemetry for {@link SubscriberHealthCanary} (audit A13, RC-1). The
 * canary's original evidence was a log line + a fire-and-forget ntfy push only, so the 2026-07-10
 * 15:57 container re-create WIPED the entire stall-window logs — the episode left ZERO durable trace.
 * Each detection/recovery now also lands one row in {@code strategy.subscriber_health_events}, which
 * survives a restart, so a post-mortem always has the timeline even when the logs are gone.
 *
 * <p>{@code kind} is one of {@code receive-stall} / {@code resubscribe} / {@code eval-stall} /
 * {@code recovery}. Writes are FAIL-SOFT: a telemetry insert must never break the engine or the
 * sweep, so any failure is swallowed with a WARN. No read path, no endpoint, no DTO — this is
 * write-only operator forensics (kept off the contract/Map-ratchet gates on purpose).
 *
 * <p>Gated on {@code artha.signals.engine-enabled} to share the canary's lifecycle (its only
 * consumer): when the engine is disabled (e.g. the paper {@code *IntegrationTest} contexts) neither
 * bean is created.
 */
@Component
@ConditionalOnProperty(
    value = "artha.signals.engine-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class SubscriberHealthTelemetry {

  private static final Logger log = LoggerFactory.getLogger(SubscriberHealthTelemetry.class);

  private final JdbcTemplate jdbc;

  /** Wires the strategy datasource. */
  public SubscriberHealthTelemetry(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Persists one health event (fail-soft — a telemetry failure never propagates). */
  public void record(String kind, String detail) {
    try {
      jdbc.update(
          "INSERT INTO subscriber_health_events (kind, detail) VALUES (?, ?)", kind, detail);
    } catch (RuntimeException e) {
      log.warn("subscriber health telemetry insert failed (kind={}): {}", kind, e.toString());
    }
  }
}
