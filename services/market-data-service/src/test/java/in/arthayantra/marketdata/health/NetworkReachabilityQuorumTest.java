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
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
  @DisplayName("at or above the quorum, the pass is RECORDED")
  void recordsWhenTheQuorumIsMet() {
    NetworkReachabilityRepository repo = mock(NetworkReachabilityRepository.class);
    when(repo.record(any(), anyInt(), anyInt(), anyInt(), anyString())).thenReturn(true);

    probe(repo, 3, "kite", "telegram", "ntfy").probe();

    verify(repo).record(eq(T0), anyInt(), anyInt(), anyInt(), anyString());
  }

  @Test
  @DisplayName("BELOW the quorum is a vendor problem and records nothing")
  void doesNotRecordBelowTheQuorum() {
    NetworkReachabilityRepository repo = mock(NetworkReachabilityRepository.class);

    // Two of five - the 'one vendor is down' shape. An implementation without a quorum records
    // here, and would then write 288 rows a day for one vendor that refuses our probe.
    probe(repo, 3, "kite", "telegram").probe();

    verify(repo, never()).record(any(), anyInt(), anyInt(), anyInt(), anyString());
  }

  @Test
  @DisplayName("the failed destination NAMES and the THRESHOLD in force are both recorded")
  void recordsWhichDestinationsFailedAndTheThreshold() {
    NetworkReachabilityRepository repo = mock(NetworkReachabilityRepository.class);

    probe(repo, 3, "kite", "telegram", "ntfy").probe();

    // probed=5, unreachable=3, quorum=3 - the threshold is stored, not just the observation, so a
    // row can still be judged after the configuration changes.
    verify(repo).record(eq(T0), eq(5), eq(3), eq(3), eq("kite,telegram,ntfy"));
  }

  // ---------------------------------------------------------------------------------------------
  // The shape of the record itself: one TRUE row per pass, no state between passes.
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("a continuing outage writes ONE ROW PER PASS — the episode model's discriminator")
  void aContinuingOutageWritesOneRowPerPass() {
    // ⚠ THIS is the test that the episode implementation cannot pass, and it is the only one in
    // this file that can honestly claim so — the episode model wrote ONE row for a continuing
    // outage and re-opened nothing on passes 2 and 3. The neighbouring failed-write and
    // two-outage tests are weaker discriminators and say so themselves; keeping that distinction
    // straight matters, because a suite whose tests all claim to be the decisive one is a suite
    // nobody can reason about.
    NetworkReachabilityRepository repo = mock(NetworkReachabilityRepository.class);
    when(repo.record(any(), anyInt(), anyInt(), anyInt(), anyString())).thenReturn(true);

    MutableClock clock = new MutableClock(T0);
    TestProbe p = new TestProbe(repo, 3, false, clock);
    p.failing("kite", "telegram", "ntfy");

    p.probe();
    clock.set(T0.plusSeconds(300));
    p.probe();
    clock.set(T0.plusSeconds(600));
    p.probe();

    verify(repo).record(eq(T0), anyInt(), anyInt(), anyInt(), anyString());
    verify(repo).record(eq(T0.plusSeconds(300)), anyInt(), anyInt(), anyInt(), anyString());
    verify(repo).record(eq(T0.plusSeconds(600)), anyInt(), anyInt(), anyInt(), anyString());
  }

  @Test
  @DisplayName("recovery simply stops recording - there is nothing to close")
  void recoveryStopsRecording() {
    NetworkReachabilityRepository repo = mock(NetworkReachabilityRepository.class);
    when(repo.record(any(), anyInt(), anyInt(), anyInt(), anyString())).thenReturn(true);

    MutableClock clock = new MutableClock(T0);
    TestProbe p = new TestProbe(repo, 3, false, clock);
    p.failing("kite", "telegram", "ntfy");
    p.probe();

    clock.set(T0.plusSeconds(300));
    p.failing();
    p.probe();

    // Exactly one row, from the down pass. The recovery pass has no write of any kind to fail.
    verify(repo, times(1)).record(any(), anyInt(), anyInt(), anyInt(), anyString());
  }

  @Test
  @DisplayName("a FAILED write loses only its own observation and is never retried")
  void aFailedWriteLosesOnlyItsOwnObservation() {
    // The Critical this design removes. Under the episode model a failed write had to be
    // remembered and replayed, and the replay is where every corruption came from. Here the second
    // pass carries its own instant and its own evidence, and the first pass's failure is simply a
    // gap - recoverable from the WARN log, and incapable of producing a wrong row.
    NetworkReachabilityRepository repo = mock(NetworkReachabilityRepository.class);
    when(repo.record(any(), anyInt(), anyInt(), anyInt(), anyString()))
        .thenReturn(false)
        .thenReturn(true);

    MutableClock clock = new MutableClock(T0);
    TestProbe p = new TestProbe(repo, 3, false, clock);
    p.failing("kite", "telegram", "ntfy");
    p.probe(); // the write fails

    clock.set(T0.plusSeconds(300));
    p.failing("kite", "telegram", "ntfy", "nse"); // and the world has moved on
    p.probe();

    // The second row describes the SECOND pass - 4 failures - never a replay of the first's 3.
    verify(repo).record(eq(T0), eq(5), eq(3), eq(3), eq("kite,telegram,ntfy"));
    verify(repo).record(eq(T0.plusSeconds(300)), eq(5), eq(4), eq(3), eq("kite,nse,telegram,ntfy"));
    verify(repo, times(2)).record(any(), anyInt(), anyInt(), anyInt(), anyString());
    verifyNoMoreInteractions(repo);
  }

  @Test
  @DisplayName("an outage, a recovery and a SECOND outage are TWO independent rows")
  void twoOutagesSeparatedByRecoveryAreNeverMerged() {
    // ⚠ An earlier version of this name promised THREE rows while asserting two, and its comment
    // claimed to reproduce "the exact sequence" of the Critical. Neither was true, and both
    // overstated the test: the Critical needed a FAILED CLOSE, which this design has no way to
    // express, so the sequence cannot be set up here at all. What this does pin is the weaker but
    // real property that a healthy pass writes NOTHING, so two outages leave two rows with a
    // readable gap rather than one row spanning both.
    NetworkReachabilityRepository repo = mock(NetworkReachabilityRepository.class);
    when(repo.record(any(), anyInt(), anyInt(), anyInt(), anyString())).thenReturn(true);

    MutableClock clock = new MutableClock(T0);
    TestProbe p = new TestProbe(repo, 3, false, clock);
    p.failing("kite", "telegram", "ntfy");
    p.probe(); // outage A

    clock.set(T0.plusSeconds(300));
    p.failing();
    p.probe(); // healthy - writes nothing at all

    clock.set(T0.plusSeconds(600));
    p.failing("kite", "telegram", "ntfy");
    p.probe(); // outage B

    verify(repo).record(eq(T0), anyInt(), anyInt(), anyInt(), anyString());
    verify(repo).record(eq(T0.plusSeconds(600)), anyInt(), anyInt(), anyInt(), anyString());
    verify(repo, times(2)).record(any(), anyInt(), anyInt(), anyInt(), anyString());
    // The healthy middle pass read nothing and wrote nothing - asserted, not assumed.
    verifyNoMoreInteractions(repo);
  }

  // ---------------------------------------------------------------------------------------------
  // An interrupted pass has NO verdict.
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("an INTERRUPTED pass records nothing - it must not stamp a false outage")
  void interruptedPassRecordsNothing() {
    // Sharper under the per-pass model than it was under episodes. On shutdown every remaining
    // send fails instantly and is reported REACHABLE (correctly - it is not a network verdict), so
    // a pass that had already seen enough real failures to meet the quorum would store a permanent,
    // plausible row asserting the host network died at the moment the service was merely stopping.
    NetworkReachabilityRepository repo = mock(NetworkReachabilityRepository.class);

    Thread.currentThread().interrupt();
    try {
      probe(repo, 3, "kite", "telegram", "ntfy").probe();
    } finally {
      // Clear it, or every later test in this JVM inherits the flag.
      assertThat(Thread.interrupted()).isTrue();
    }

    verify(repo, never()).record(any(), anyInt(), anyInt(), anyInt(), anyString());
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
  @DisplayName("a quorum of ONE is refused — that shape is a vendor outage, not a host one")
  void refusesAQuorumOfOne() {
    assertThatThrownBy(() -> new TestProbe(mock(NetworkReachabilityRepository.class), 1, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("majority");
  }

  @Test
  @DisplayName("a quorum of TWO of five is refused — 'most or all' means a majority")
  void refusesAQuorumBelowAMajority() {
    // Separate from the quorum-of-one case on purpose. 2-of-5 is the boundary that actually moved:
    // it passed the earlier "at least 2" floor while still contradicting the diagnosis both this
    // class and the migration state. A single method asserting both would stop at the first and
    // never demonstrate this one.
    assertThatThrownBy(() -> new TestProbe(mock(NetworkReachabilityRepository.class), 2, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least 3");
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

    verify(repo, never()).record(any(), anyInt(), anyInt(), anyInt(), anyString());
  }

  // ---------------------------------------------------------------------------------------------

  private static NetworkReachabilityProbe newProbe(String spec, int quorum) {
    return new NetworkReachabilityProbe(
        mock(NetworkReachabilityRepository.class), new SimpleMeterRegistry(), FIXED, true, 5, spec,
        quorum);
  }

  @Test
  @DisplayName("an explicit default port is the SAME origin — one vendor cannot fill the quorum")
  void refusesADuplicateOriginWrittenWithAnExplicitDefaultPort() {
    // https://x and https://x:443 are one origin. Accepted as two, a single vendor inflates
    // probed_count and can satisfy the majority alone — defeating the quorum's whole purpose.
    assertThatThrownBy(
            () -> newProbe("a=https://api.kite.trade,b=https://api.kite.trade:443,c=https://x.test",
                2))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicates");
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
