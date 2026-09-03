package in.arthayantra.marketdata.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The fail-soft CONTRACT of {@link NetworkReachabilityRepository}, at the seam where the database
 * actually breaks.
 *
 * <p>⚠ <b>Why this class exists separately from the quorum tests.</b> Those drive the probe with a
 * MOCKED repository, so they pin how the probe REACTS to a reported failure — they can say nothing
 * about whether the repository ever reports one. Mutating the write to swallow its error left every
 * one of them green, which is the classic "the test mocks the seam the fix lives behind" tautology.
 * The behaviour below is the other half, and without it the {@code return false} branch is never
 * executed by any test.
 *
 * <p>Both halves matter for the same guarantee, and it is a smaller one than it used to be: a
 * failed write now loses exactly one observation. There is no retry to get wrong, because a row is
 * a statement about one instant rather than a step in a state machine — the design change that
 * removed the Critical this class was originally written to guard.
 */
class NetworkReachabilityRepositoryTest {

  private static final Instant T0 = Instant.parse("2026-09-01T07:12:00Z");

  @Test
  @DisplayName("record REPORTS a failed write rather than swallowing it")
  void recordReportsFailure() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.update(anyString(), any(Object[].class)))
        .thenThrow(new DataAccessResourceFailureException("db down"));

    assertThat(new NetworkReachabilityRepository(jdbc).record(T0, 5, 3, 3, "kite,telegram,ntfy"))
        .as("a swallowed failure would thin the record with nothing in the log to say so")
        .isFalse();
  }

  @Test
  @DisplayName("record reports success when the write lands")
  void recordReportsSuccess() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

    assertThat(new NetworkReachabilityRepository(jdbc).record(T0, 5, 3, 3, "kite,telegram,ntfy"))
        .isTrue();
  }

  @Test
  @DisplayName("a failed write NEVER throws into the scheduled pass")
  void aFailedWriteNeverThrows() {
    // ⚠ The recorder must not be able to take out the pass it rides on. An observability feature
    // that becomes an outage of its own is strictly worse than no feature — and this pass shares
    // its thread with nothing else precisely so a network stall cannot spread, which a thrown
    // exception would undo by killing the schedule instead.
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.update(anyString(), any(Object[].class)))
        .thenThrow(new DataAccessResourceFailureException("db down"));

    assertThatCode(
            () -> new NetworkReachabilityRepository(jdbc).record(T0, 5, 3, 3, "kite"))
        .doesNotThrowAnyException();
  }
}
