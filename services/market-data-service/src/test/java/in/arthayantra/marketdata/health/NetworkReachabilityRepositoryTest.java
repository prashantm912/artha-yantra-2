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
import org.springframework.jdbc.core.RowMapper;

/**
 * The fail-soft CONTRACT of {@link NetworkReachabilityRepository}, at the seam where the database
 * actually breaks.
 *
 * <p>⚠ <b>Why this class exists separately from the quorum tests.</b> Those drive the probe with a
 * MOCKED repository, so they pin how the probe REACTS to a reported failure — they can say nothing
 * about whether the repository ever reports one. Mutating {@code close} to swallow its error left
 * all eighteen of them green, which is the classic "the test mocks the seam the fix lives behind"
 * tautology. The behaviour below is the other half, and without it the {@code return false} branch
 * is never executed by any test.
 *
 * <p>Both halves matter for the same defect: a swallowed close leaves the episode open, and a new
 * outage then finds a row already open and writes nothing — merging two incidents into one that
 * reads as a single long outage which never happened.
 */
class NetworkReachabilityRepositoryTest {

  private static final Instant T0 = Instant.parse("2026-09-01T07:12:00Z");

  @Test
  @DisplayName("close REPORTS a failed write rather than swallowing it")
  void closeReportsFailure() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.update(anyString(), any(Object[].class)))
        .thenThrow(new DataAccessResourceFailureException("connection reset"));

    assertThat(new NetworkReachabilityRepository(jdbc).close("reach-1", T0)).isFalse();
  }

  @Test
  @DisplayName("close reports success when the write lands")
  void closeReportsSuccess() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

    assertThat(new NetworkReachabilityRepository(jdbc).close("reach-1", T0)).isTrue();
  }

  @Test
  @DisplayName("a failed write NEVER throws into the scheduled pass")
  void writesNeverThrowIntoTheCaller() {
    // A diagnostic recorder that throws into its caller can take out the pass it rides on, turning
    // an observability feature into an outage of its own.
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.update(anyString(), any(Object[].class)))
        .thenThrow(new DataAccessResourceFailureException("connection reset"));

    assertThatCode(
            () -> new NetworkReachabilityRepository(jdbc).open("reach-1", T0, 5, 3, 3, "kite", "d"))
        .doesNotThrowAnyException();
    assertThatCode(() -> new NetworkReachabilityRepository(jdbc).close("reach-1", T0))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("an unreadable open-episode query yields EMPTY rather than throwing")
  void openEpisodeKeyFailsSoftToEmpty() {
    // ⚠ This is the fail-soft with a real consequence: the next pass then believes nothing is open
    // and may try to open a second episode. The unique partial index in V061 is what stops that
    // becoming two overlapping open rows — see NetworkReachabilityEpisodeIntegrationTest.
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenThrow(new DataAccessResourceFailureException("connection reset"));

    assertThat(new NetworkReachabilityRepository(jdbc).openEpisodeKey()).isEmpty();
  }
}
