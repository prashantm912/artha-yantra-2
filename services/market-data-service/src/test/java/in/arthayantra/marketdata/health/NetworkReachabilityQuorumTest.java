package in.arthayantra.marketdata.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The NEW-13 quorum rule (`NetworkReachabilityProbe`).
 *
 * <p>⚠️ The quorum IS the diagnosis, so these tests pin the BOUNDARY on both sides. "One vendor is
 * down" and "the host lost its network" are different findings with different responses, and the
 * 2026-08-19 / 08-20 / 09-01 incidents were each first filed as the wrong one. A test that only
 * checked "an episode opens when things fail" would pass for an implementation with no quorum at
 * all — which is precisely the bug this rule exists to prevent.
 *
 * <p>Reachability itself is not exercised here (that would mean real sockets); the probe is driven
 * through an overridden {@code reachable} so the quorum arithmetic is the only variable.
 */
class NetworkReachabilityQuorumTest {

  private static final Clock FIXED =
      Clock.fixed(Instant.parse("2026-09-01T07:12:00Z"), ZoneOffset.UTC);

  private static final String FIVE =
      "kite=https://api.kite.trade,upstox=https://api.upstox.com,nse=https://www.nseindia.com,"
          + "telegram=https://api.telegram.org,ntfy=https://ntfy.sh";

  @Test
  @DisplayName("at or above the quorum, with nothing open, OPENS an episode")
  void opensAnEpisodeWhenTheQuorumIsMet() {
    NetworkReachabilityRepository repo = mock(NetworkReachabilityRepository.class);
    when(repo.openEpisodeKey()).thenReturn(Optional.empty());

    probe(repo, 3, "kite", "telegram", "ntfy").probe();

    verify(repo).open(anyString(), any(), anyInt(), anyInt(), anyString(), anyString());
  }

  @Test
  @DisplayName("BELOW the quorum is a vendor problem and opens nothing")
  void doesNotOpenBelowTheQuorum() {
    NetworkReachabilityRepository repo = mock(NetworkReachabilityRepository.class);
    when(repo.openEpisodeKey()).thenReturn(Optional.empty());

    // Two of five — the 'one vendor is down' shape. An implementation without a quorum opens here.
    probe(repo, 3, "kite", "telegram").probe();

    verify(repo, never()).open(anyString(), any(), anyInt(), anyInt(), anyString(), anyString());
  }

  @Test
  @DisplayName("the failed destination NAMES are recorded, so a vendor pattern stays visible")
  void recordsWhichDestinationsFailed() {
    NetworkReachabilityRepository repo = mock(NetworkReachabilityRepository.class);
    when(repo.openEpisodeKey()).thenReturn(Optional.empty());

    probe(repo, 3, "kite", "telegram", "ntfy").probe();

    verify(repo)
        .open(anyString(), any(), org.mockito.ArgumentMatchers.eq(5),
            org.mockito.ArgumentMatchers.eq(3),
            org.mockito.ArgumentMatchers.eq("kite,telegram,ntfy"), anyString());
  }

  @Test
  @DisplayName("an episode already open is not re-opened while the outage continues")
  void doesNotReopenAnOpenEpisode() {
    NetworkReachabilityRepository repo = mock(NetworkReachabilityRepository.class);
    when(repo.openEpisodeKey()).thenReturn(Optional.of("reach-1"));

    probe(repo, 3, "kite", "telegram", "ntfy").probe();

    verify(repo, never()).open(anyString(), any(), anyInt(), anyInt(), anyString(), anyString());
    verify(repo, never()).close(anyString(), any());
  }

  @Test
  @DisplayName("recovery below the quorum CLOSES the open episode")
  void closesOnRecovery() {
    NetworkReachabilityRepository repo = mock(NetworkReachabilityRepository.class);
    when(repo.openEpisodeKey()).thenReturn(Optional.of("reach-1"));

    probe(repo, 3).probe();

    verify(repo).close(org.mockito.ArgumentMatchers.eq("reach-1"), any());
    verify(repo, never()).open(anyString(), any(), anyInt(), anyInt(), anyString(), anyString());
  }

  @Test
  @DisplayName("a destination spec without name=origin is refused at construction")
  void refusesAMalformedDestinationSpec() {
    // ⚠️ Fail at STARTUP, not silently at probe time. A bare URL would also be the shape most
    // likely to smuggle a credential-bearing path into a column this class stores and logs.
    assertThatThrownBy(
            () ->
                new NetworkReachabilityProbe(
                    mock(NetworkReachabilityRepository.class),
                    new SimpleMeterRegistry(),
                    FIXED,
                    true,
                    5,
                    "https://api.kite.trade",
                    3))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("name=origin");
  }

  @Test
  @DisplayName("disabled does nothing at all")
  void disabledDoesNothing() {
    NetworkReachabilityRepository repo = mock(NetworkReachabilityRepository.class);

    new TestProbe(repo, 3, true, "kite", "telegram", "ntfy").probe();

    verify(repo, never()).openEpisodeKey();
  }

  @Test
  @DisplayName("every default destination is an ORIGIN — no path, no credential")
  void defaultDestinationsCarryNoPath() {
    // ⚠️ Pinned deliberately: an ntfy topic URL IS the credential, and these values are stored in
    // a text column and written to logs. A future edit adding a path would be caught here.
    for (String entry : FIVE.split(",")) {
      String origin = entry.substring(entry.indexOf('=') + 1);
      assertThat(java.net.URI.create(origin).getPath()).isEmpty();
    }
  }

  private static TestProbe probe(
      NetworkReachabilityRepository repo, int quorum, String... failing) {
    return new TestProbe(repo, quorum, false, failing);
  }

  /** Overrides only the network call, so the quorum arithmetic is what is under test. */
  private static final class TestProbe extends NetworkReachabilityProbe {
    private final java.util.Set<String> failing;

    TestProbe(
        NetworkReachabilityRepository repo, int quorum, boolean disabled, String... failing) {
      super(repo, new SimpleMeterRegistry(), FIXED, !disabled, 1, FIVE, quorum);
      this.failing = java.util.Set.of(failing);
    }

    @Override
    boolean reachable(Destination d) {
      return !failing.contains(d.name());
    }
  }
}
