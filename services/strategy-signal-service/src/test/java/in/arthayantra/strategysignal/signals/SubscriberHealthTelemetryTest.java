package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves the durable-telemetry writer (audit A13) both persists a row and — critically — is
 * FAIL-SOFT: a DB failure must be swallowed so it can never break the watchdog sweep or the engine.
 */
class SubscriberHealthTelemetryTest {

  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final SubscriberHealthTelemetry telemetry = new SubscriberHealthTelemetry(jdbc);

  @Test
  void record_insertsOneRow() {
    telemetry.record("eval-stall", "detail");

    verify(jdbc)
        .update(
            "INSERT INTO subscriber_health_events (kind, detail) VALUES (?, ?)",
            "eval-stall",
            "detail");
  }

  @Test
  void record_swallowsDbFailure() {
    doThrow(new DataAccessResourceFailureException("db down"))
        .when(jdbc)
        .update(anyString(), anyString(), anyString());

    assertThatCode(() -> telemetry.record("receive-stall", "boom")).doesNotThrowAnyException();
  }
}
