package in.arthayantra.marketdata.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The NEW-13 quorum rule (`NetworkReachabilityProbe`).
 *
 * <p>⚠ The quorum IS the diagnosis, so these tests pin the BOUNDARY on both sides. "One vendor is
 * down" and "the host lost its network" are different findings with different responses, and the
 * 2026-08-19 / 08-20 / 09-01 incidents were each first filed as the wrong one. A test that only
 * checked "an episode opens when things fail" would pass for an implementation with no quorum at
 * all — which is precisely the bug this rule exists to prevent.
 *
 * <p>Reachability itself is not exercised here (that would mean real sockets); the probe is driven
 * through an overridden {@code reachable} so the quorum arithmetic is the only variable.
 */
class NetworkReachabilityQuorumTest {

  private static final Instant T0 = Instant.parse("2026-09-01T07:12:00Z");
  private static final Clock FIXED = Clock.fixed(T0, ZoneOffset.UTC);

  /**
   * ⚠ The PRODUCTION default, referenced rather than copied. An earlier revision of this file
   * declared its own literal copy of the five destinations and asserted the origins-only rule
   * against that — which would have stayed green while someone added a credential-bearing path to
   * the real default. A test that supplies its own input proves nothing about the class.
   */
  private static final String DEFAULTS = NetworkReachabilityProbe.DEFAULT_DESTINATION_SPEC;

  @Test
  @DisplayName("at or above the quorum, with nothing open, OPENS an episode")
  void opensAnEpisodeWhenTheQuorumIsMet() {
    NetworkReachabilityRepository repo = mock(NetworkReachabilityRepository.class);
    when(repo.openEpisodeKey()).thenReturn(Optional.empty());

    probe(repo, 3, "kite", "telegram", "ntfy").probe();

    verify(repo).open(anyString(), any(), anyInt(), anyInt(), anyInt(), anyString(), anyString());
  }

  @Test
  @DisplayName("BELOW the quorum is a vendor problem and opens nothing")
  void doesNotOpenBelowTheQuorum() {
    NetworkReachabilityRepository repo = mock(NetworkReachabilityRepository.class);
    when(repo.openEpisodeKey()).thenReturn(Optional.empty());

    // Two of five — the 'one vendor is down' shape. An implementation without a quorum opens here.
    probe(repo, 3, "kite", "telegram").probe();

    verify(repo, never())
        .open(anyString(), any(), anyInt(), anyInt(), anyInt(), anyString(), anyString());
  }

  @Test
  @DisplayName("the failed destination NAMES and the THRESHOLD in force are both recorded")
  void recordsWhichDestinationsFailedAndTheThreshold() {
    NetworkReachabilityRepository repo = mock(NetworkReachabilityRepository.class);
    when(repo.openEpisodeKey()).thenReturn(Optional.empty());

    probe(repo, 3, "kite", "telegram", "ntfy").probe();

    // probed=5, unreachable=3, quorum=3 — the threshold is stored, not just the observation, so a
    // row can still be judged after the configuration changes.
    verify(repo)
        .open(anyString(), any(), eq(5), eq(3), eq(3), eq("kite,telegram,ntfy"), anyString());
  }

  @Test
  @DisplayName("an episode already open is not re-opened while the outage continues")
  void doesNotReopenAnOpenEpisode() {
    NetworkReachabilityRepository repo = mock(NetworkReachabilityRepository.class);
    when(repo.openEpisodeKey()).thenReturn(Optional.of("reach-1"));

    probe(repo, 3, "kite", "telegram", "ntfy").probe();

    verify(repo, never())
        .open(anyString(), any(), anyInt(), anyInt(), anyInt(), anyString(), anyString());
    verify(repo, never()).close(anyString(), any());
  }

  @Test
  @DisplayName("recovery below the quorum CLOSES the open episode")
  void closesOnRecovery() {
    NetworkReachabilityRepository repo = mock(NetworkReachabilityRepository.class);
    when(repo.openEpisodeKey()).thenReturn(Optional.of("reach-1"));
    when(repo.close(anyString(), any())).thenReturn(true);

    probe(repo, 3).probe();

    verify(repo).close(eq("reach-1"), any());
    verify(repo, never())
        .open(anyString(), any(), anyInt(), anyInt(), anyInt(), anyString(), anyString());
  }

  // ---------------------------------------------------------------------------------------------
  // An interrupted pass has NO verdict.
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("an INTERRUPTED pass writes nothing — it must not stamp a false recovery")
  void interruptedPassMakesNoTransition() {
    // ⚠ The failure this pins is the nastiest one in the class. On shutdown every remaining send
    // fails instantly and is reported REACHABLE (correctly — it is not a network verdict), so the
    // pass looks like recovery and would CLOSE the open episode. That writes a false end time at
    // the exact moment the host is going down: the incident this table exists to record, corrupted
    // by the recorder.
    NetworkReachabilityRepository repo = mock(NetworkReachabilityRepository.class);
    when(repo.openEpisodeKey()).thenReturn(Optional.of("reach-1"));

    Thread.currentThread().interrupt();
    try {
      probe(repo, 3).probe();
    } finally {
      // Clear it, or every later test in this JVM inherits the flag.
      assertThat(Thread.interrupted()).isTrue();
    }

    verify(repo, never()).close(anyString(), any());
    verify(repo, never())
        .open(anyString(), any(), anyInt(), anyInt(), anyInt(), anyString(), anyString());
    verify(repo, never()).openEpisodeKey();
  }

  // ---------------------------------------------------------------------------------------------
  // A write that did not land must not corrupt the record.
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("a failed close is retried with the instant recovery was ACTUALLY observed")
  void retriesAFailedCloseWithTheOriginalRecoveryInstant() {
    NetworkReachabilityRepository repo = mock(NetworkReachabilityRepository.class);
    when(repo.openEpisodeKey()).thenReturn(Optional.of("reach-1"));
    when(repo.close(anyString(), any())).thenReturn(false).thenReturn(true);

    MutableClock clock = new MutableClock(T0);
    TestProbe p = new TestProbe(repo, 3, false, clock);

    p.probe(); // recovery observed at T0; the write fails
    clock.set(T0.plusSeconds(300)); // five minutes later
    p.probe(); // retried

    // ⚠ T0, not T0+300. Retrying with `now` would inflate every episode that hit a DB blip by the
    // retry delay, quietly making outages look longer than they were.
    verify(repo, times(2)).close(eq("reach-1"), eq(T0));
  }

  @Test
  @DisplayName("a failed close does NOT merge a following outage into the previous episode")
  void aFailedCloseDoesNotSwallowTheNextOutage() {
    // ⚠ The reason `close` returns a boolean at all. With the close swallowed, the row stays open;
    // a new outage then finds an episode already open and writes nothing, so two separate incidents
    // are recorded as one long one that never happened — authoritative-looking and wrong.
    NetworkReachabilityRepository repo = mock(NetworkReachabilityRepository.class);
    when(repo.openEpisodeKey())
        .thenReturn(Optional.of("reach-1")) // pass 1: still open, we observe recovery
        .thenReturn(Optional.empty()); // pass 2: the retry landed, so nothing is open
    when(repo.close(anyString(), any())).thenReturn(false).thenReturn(true);

    MutableClock clock = new MutableClock(T0);
    TestProbe p = new TestProbe(repo, 3, false, clock);

    p.probe(); // recovery; close fails
    clock.set(T0.plusSeconds(300));
    p.failing("kite", "telegram", "ntfy"); // a NEW outage
    p.probe();

    verify(repo, times(2)).close(eq("reach-1"), eq(T0));
    verify(repo).open(anyString(), any(), anyInt(), anyInt(), anyInt(), anyString(), anyString());
  }

  @Test
  @DisplayName("a failed open is retried under the SAME key and start, not a new one")
  void retriesAFailedOpenIdempotently() {
    // The start instant is retained, so the retry re-derives the same episode key — which is what
    // makes ON CONFLICT (episode_key) DO NOTHING an idempotent retry rather than a second episode.
    NetworkReachabilityRepository repo = mock(NetworkReachabilityRepository.class);
    when(repo.openEpisodeKey()).thenReturn(Optional.empty());

    MutableClock clock = new MutableClock(T0);
    TestProbe p = new TestProbe(repo, 3, false, clock);
    p.failing("kite", "telegram", "ntfy");

    p.probe();
    clock.set(T0.plusSeconds(300));
    p.probe();

    verify(repo, times(2))
        .open(eq("reach-" + T0.toEpochMilli()), eq(T0), anyInt(), anyInt(), anyInt(), anyString(),
            anyString());
  }

  // ---------------------------------------------------------------------------------------------
  // Configuration is refused at STARTUP, because every one of these is silent at runtime.
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("a quorum above the destination count is refused — it could never be met")
  void refusesAnUnsatisfiableQuorum() {
    // ⚠ The 'structurally unsatisfiable gate' shape: the probe would report healthy forever while
    // recording nothing, and nothing else in the system would look wrong.
    assertThatThrownBy(() -> new TestProbe(mock(NetworkReachabilityRepository.class), 6, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("could never be met");
  }

  @Test
  @DisplayName("a quorum of one is refused when several destinations are configured")
  void refusesAQuorumOfOneAcrossManyDestinations() {
    // One destination failing is that VENDOR. Filing it as the host is the exact misreading the
    // 08-19 / 08-20 / 09-01 incidents cost, so the config that would produce it is refused.
    assertThatThrownBy(() -> new TestProbe(mock(NetworkReachabilityRepository.class), 1, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least 2");
  }

  @Test
  @DisplayName("a destination spec without name=origin is refused at construction")
  void refusesAMalformedDestinationSpec() {
    assertThatThrownBy(() -> newProbe("https://api.kite.trade", 3))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("name=origin");
  }

  @Test
  @DisplayName("a destination carrying a PATH is refused — an ntfy topic URL is the credential")
  void refusesADestinationWithAPath() {
    assertThatThrownBy(() -> newProbe("ntfy=https://ntfy.sh/my-secret-topic", 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ORIGIN");
  }

  @Test
  @DisplayName("a destination carrying credentials or a query is refused")
  void refusesEmbeddedCredentialsAndQueries() {
    assertThatThrownBy(() -> newProbe("kite=https://user:pw@api.kite.trade", 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> newProbe("kite=https://api.kite.trade?token=abc", 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> newProbe("kite=ftp://api.kite.trade", 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("a rejection message NEVER echoes the offending value")
  void neverEchoesTheRejectedValue() {
    // ⚠ The point of rejecting it is that it may be a secret. Echoing it into an exception writes
    // it to the same logs the rule exists to keep it out of — so the guard would leak the thing it
    // was guarding.
    String secret = "super-secret-topic-9f3a";
    assertThatThrownBy(() -> newProbe("ntfy=https://ntfy.sh/" + secret, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining(secret);
  }

  @Test
  @DisplayName("duplicate origins are refused — one vendor must not meet the quorum alone")
  void refusesDuplicateOrigins() {
    assertThatThrownBy(
            () -> newProbe("a=https://api.kite.trade,b=https://API.kite.trade,c=https://x.test", 2))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicates");
  }

  @Test
  @DisplayName("every PRODUCTION default destination is an ORIGIN — no path, no credential")
  void productionDefaultDestinationsCarryNoPath() {
    // Asserted against the constant the @Value actually uses, and through the real validator, so
    // adding a credential-bearing path to the shipped defaults fails here.
    assertThatCode(() -> newProbe(DEFAULTS, 3)).doesNotThrowAnyException();
    for (String entry : DEFAULTS.split(",")) {
      URI origin = URI.create(entry.substring(entry.indexOf('=') + 1));
      assertThat(origin.getPath()).isEmpty();
      assertThat(origin.getUserInfo()).isNull();
      assertThat(origin.getQuery()).isNull();
    }
  }

  @Test
  @DisplayName("disabled does nothing at all")
  void disabledDoesNothing() {
    NetworkReachabilityRepository repo = mock(NetworkReachabilityRepository.class);

    new TestProbe(repo, 3, true, "kite", "telegram", "ntfy").probe();

    verify(repo, never()).openEpisodeKey();
  }

  // ---------------------------------------------------------------------------------------------

  private static NetworkReachabilityProbe newProbe(String spec, int quorum) {
    return new NetworkReachabilityProbe(
        mock(NetworkReachabilityRepository.class), new SimpleMeterRegistry(), FIXED, true, 5, spec,
        quorum);
  }

  private static TestProbe probe(
      NetworkReachabilityRepository repo, int quorum, String... failing) {
    return new TestProbe(repo, quorum, false, failing);
  }

  /** A clock the test can advance, so a retained instant is distinguishable from {@code now}. */
  private static final class MutableClock extends Clock {
    private Instant at;

    MutableClock(Instant at) {
      this.at = at;
    }

    void set(Instant next) {
      this.at = next;
    }

    @Override
    public Instant instant() {
      return at;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }

  /** Overrides only the network call, so the quorum arithmetic is what is under test. */
  private static final class TestProbe extends NetworkReachabilityProbe {
    private java.util.Set<String> failing;

    TestProbe(
        NetworkReachabilityRepository repo, int quorum, boolean disabled, String... failing) {
      this(repo, quorum, disabled, FIXED, failing);
    }

    TestProbe(NetworkReachabilityRepository repo, int quorum, boolean disabled, Clock clock,
        String... failing) {
      super(repo, new SimpleMeterRegistry(), clock, !disabled, 1, DEFAULTS, quorum);
      this.failing = java.util.Set.of(failing);
    }

    void failing(String... names) {
      this.failing = java.util.Set.of(names);
    }

    @Override
    boolean reachable(Destination d) {
      return !failing.contains(d.name());
    }
  }
}
