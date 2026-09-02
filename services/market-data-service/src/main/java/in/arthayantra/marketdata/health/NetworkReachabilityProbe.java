package in.arthayantra.marketdata.health;

import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * NEW-13 — outbound-reachability probe. Answers ONE question durably: when calls to the outside
 * world start failing, was it the host's own network or a single vendor?
 *
 * <p>⚠️ <b>The quorum IS the diagnosis, and getting it wrong has already cost real investigations.</b>
 * One destination failing is that vendor being down. Most or all failing inside the same window is
 * the host's outbound network. On 2026-08-19, 2026-08-20 and 2026-09-01 the second happened and was
 * first filed as a Kite outage each time; on 09-01 five destinations died together — Kite REST, the
 * Kite socket, niftyindices, telegram and ntfy. The distinction was only ever recovered by hand,
 * afterwards, from container logs that a redeploy can destroy.
 *
 * <p>⚠️ <b>RECORD-ONLY. This never alerts, and that is a decision, not an omission</b> (owner,
 * 2026-09-02). When it fires, the paging channels are among the things that are down — measured on
 * 2026-09-01, telegram and ntfy both dead while every container was healthy. An alert is therefore
 * the one mechanism that cannot be relied on here, and attempting it would block on timeouts during
 * the exact minutes the service is already struggling.
 *
 * <p>⚠️ <b>Durability is not a nicety.</b> The counters below exist for dashboards, but the record
 * lives in the DB, because a Micrometer counter is process-lifetime and this event is routinely
 * followed by the box going down. The platform has paid for that twice already — a weekly arming
 * report keyed on a counter could only ever say "since the last restart", and the H26 rate peaks
 * died with their process.
 */
@Component
public class NetworkReachabilityProbe {

  private static final Logger log = LoggerFactory.getLogger(NetworkReachabilityProbe.class);

  /**
   * ⚠️ ORIGINS ONLY — never a full URL with a path, and this is a security constraint rather than a
   * style one. An ntfy topic URL IS the credential, and these values are logged, stored and read by
   * humans. Probing the origin answers the reachability question without handling a secret.
   */
  private final List<Destination> destinations;

  /**
   * The shipped destinations, as a NAMED CONSTANT rather than a literal buried in the {@code @Value}
   * below.
   *
   * <p>⚠️ This exists so the origins-only rule can be tested against the value production actually
   * uses. The previous revision asserted that rule against a COPY of this string living in the test
   * file, which would have stayed green while someone added a credential-bearing path here — the
   * test supplied its own input and therefore proved nothing about this class.
   */
  static final String DEFAULT_DESTINATION_SPEC =
      "kite=https://api.kite.trade,"
          + "upstox=https://api.upstox.com,"
          + "nse=https://www.nseindia.com,"
          + "telegram=https://api.telegram.org,"
          + "ntfy=https://ntfy.sh";

  private static final String DESTINATIONS_PROPERTY =
      "${artha.health.reachability.destinations:" + DEFAULT_DESTINATION_SPEC + "}";

  /** Names go into log lines and a TEXT column, so they are restricted rather than trusted. */
  private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");

  private final HttpClient http;
  private final NetworkReachabilityRepository repository;
  private final Clock clock;
  private final int quorum;
  private final boolean enabled;
  private final AtomicInteger unreachableGauge = new AtomicInteger();

  /**
   * When the current outage was FIRST observed, retained across a failed open so the episode's
   * {@code started_at} is the real start rather than whenever the retry happened to succeed.
   */
  private Instant outageFirstObservedAt;

  /**
   * A close that did not land, retained with the instant recovery was actually observed.
   *
   * <p>⚠️ Without this, a failed close plus a NEW outage inside one cron period silently MERGES two
   * incidents into one row — the record would show a single long outage that never happened, which
   * is a worse failure than a missing row because it looks authoritative.
   */
  /**
   * When recovery was first OBSERVED and not yet fully recorded.
   *
   * <p>Retained across a failed close AND across a pass that could not read episode state at all,
   * so the episode's end is the moment reachability actually returned rather than whenever the
   * write eventually succeeded.
   */
  private Instant recoveryObservedAt;

  /**
   * An episode whose OPENING write never landed.
   *
   * <p>⚠ Without this, an outage that recovers before its insert succeeds is lost completely: the
   * recovery pass finds nothing open, clears the retained start, and writes nothing — so the
   * incident leaves no trace at all. That is strictly worse than a wrong duration, and it is the
   * single outcome this table exists to prevent.
   */
  private PendingOpen pendingOpen;

  public NetworkReachabilityProbe(
      NetworkReachabilityRepository repository,
      MeterRegistry meterRegistry,
      Clock clock,
      @Value("${artha.health.reachability.enabled:true}") boolean enabled,
      @Value("${artha.health.reachability.timeout-seconds:5}") int timeoutSeconds,
      @Value(DESTINATIONS_PROPERTY) String destinationSpec,
      @Value("${artha.health.reachability.quorum:3}") int quorum) {
    this.repository = repository;
    this.clock = clock;
    this.enabled = enabled;
    this.destinations = parse(destinationSpec);
    // ⚠️ Both bounds fail at STARTUP, because both misconfigurations are SILENT at runtime and
    // point the wrong way. A quorum above the destination count can never be met, so the probe
    // reports healthy forever while recording nothing — the "structurally unsatisfiable gate" shape.
    // A quorum below 2 (with more than one destination) contradicts this class's own diagnosis:
    // one destination failing is that vendor, not the host, and filing it as the host is exactly
    // the misreading the 08-19 / 08-20 / 09-01 incidents cost.
    if (quorum > this.destinations.size()) {
      throw new IllegalArgumentException(
          "reachability quorum " + quorum + " exceeds the " + this.destinations.size()
              + " configured destinations — it could never be met");
    }
    // A MAJORITY, not merely "more than one". Both this class and the migration state the
    // diagnosis as "most or all failing together is the host"; accepting 2-of-5 would let the
    // implementation contradict its own documented contract, which is the misreading the
    // 08-19 / 08-20 / 09-01 incidents cost. Integer division is deliberate: for 5 destinations
    // this requires 3, for 2 it requires 2, for 1 it requires 1.
    int majority = this.destinations.size() / 2 + 1;
    if (quorum < majority) {
      throw new IllegalArgumentException(
          "reachability quorum must be a majority — at least " + majority + " of "
              + this.destinations.size() + " destinations; got " + quorum);
    }
    this.quorum = quorum;
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(timeoutSeconds))
            // ⚠️ NEVER follow redirects. A redirect can send the probe somewhere else entirely, and
            // a 3xx already proves the only thing being asked: the host reached the destination.
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    meterRegistry.gauge("ay_network_unreachable_destinations", unreachableGauge);
  }

  /**
   * Every 5 minutes. Frequent enough to bound an episode's start to a few minutes, rare enough that
   * the probe is not itself a load source.
   *
   * <p>⚠️ Runs on its OWN scheduler, not the shared default pool — that pool is one thread shared by
   * ~30 scheduled methods, and this method deliberately BLOCKS on network timeouts. Parking it there
   * would let a network outage stall every other scheduled job in the service, which is the precise
   * failure this class exists to observe rather than cause.
   */
  @Scheduled(
      cron = "${artha.health.reachability.cron:0 */5 * * * *}",
      zone = "Asia/Kolkata",
      scheduler = "reachabilityTaskScheduler")
  public void probe() {
    if (!enabled) {
      return;
    }
    List<String> failed = new ArrayList<>();
    for (Destination d : destinations) {
      if (!reachable(d)) {
        failed.add(d.name());
      }
    }

    // ⚠ AN INTERRUPTED PASS HAS NO OPINION, AND MUST NOT WRITE ONE.
    // Once the thread is interrupted every remaining send fails instantly, and `reachable` reports
    // those as REACHABLE (correctly — they are not a network verdict). The result is a pass that
    // looks like recovery. With an episode open that would CLOSE it, stamping a FALSE recovery at
    // the exact moment the host is shutting down — the incident this table exists to record. So the
    // pass is abandoned whole: no gauge update, no transition.
    if (Thread.currentThread().isInterrupted()) {
      log.info("reachability: pass interrupted (shutdown) — no verdict, no episode transition");
      return;
    }

    unreachableGauge.set(failed.size());
    Instant now = clock.instant();
    boolean hostNetworkDown = failed.size() >= quorum;

    // ---------------------------------------------------------------------------------------
    // OBSERVE — touches NO database. Everything this pass learned is retained here, so a pass
    // that cannot reach the database still contributes what it saw.
    //
    // ⚠ The split is load-bearing, and getting it wrong lost outages three different ways. The
    // ACT phase below returns early when episode state is unreadable; anything recorded after
    // that point is simply not recorded on such a pass.
    // ---------------------------------------------------------------------------------------
    if (hostNetworkDown) {
      if (outageFirstObservedAt == null) {
        outageFirstObservedAt = now;
      }
      // ⚠ The evidence is captured HERE, not after the lookup. Built later, an outage whose very
      // first lookup was unreadable retained only its start; the recovery pass then found nothing
      // pending, cleared that start and wrote nothing — the incident vanished completely.
      if (pendingOpen == null) {
        pendingOpen =
            new PendingOpen(
                "reach-" + outageFirstObservedAt.toEpochMilli(),
                outageFirstObservedAt,
                destinations.size(),
                failed.size(),
                quorum,
                String.join(",", failed),
                "quorum " + failed.size() + "/" + destinations.size() + " unreachable"
                    + " (threshold " + quorum + ")");
        log.warn(
            "reachability: {} of {} destinations unreachable ({}) — opening episode {}."
                + " This is the HOST network, not one vendor.",
            failed.size(), destinations.size(), pendingOpen.failedNames(), pendingOpen.key());
      }
    } else {
      if (recoveryObservedAt == null) {
        recoveryObservedAt = now;
      }
      // ⚠ The outage observation ENDS here, not after the lookup. Cleared later, an unreadable
      // recovery pass returned early and left the old start standing; the NEXT outage then reused
      // it as both start and episode key, and that insert is an ON CONFLICT no-op against the
      // already-closed row — so the new outage reported success and was never recorded.
      outageFirstObservedAt = null;
      if (!failed.isEmpty()) {
        // Below quorum: a vendor problem, deliberately NOT an episode. Logged so it is still
        // visible, because "one vendor is down" is a real finding — it is just a different one.
        log.info("reachability: {} unreachable ({}) — below the {} quorum, treating as vendor-local",
            failed.size(), String.join(",", failed), quorum);
      }
    }

    // ---------------------------------------------------------------------------------------
    // ACT — every database transition, and every one of them may decline.
    // ---------------------------------------------------------------------------------------
    NetworkReachabilityRepository.OpenEpisodeLookup lookup = repository.openEpisode();
    if (!lookup.readSucceeded()) {
      // UNKNOWN, not "nothing is open". Everything observed above survives to the next pass.
      log.warn("reachability: episode state unreadable — no transition this pass");
      return;
    }
    String openKey = lookup.key();

    // An unrecorded recovery is settled FIRST, whatever the current state. Deferring it would let
    // a new outage beginning before the close lands be absorbed into the previous episode.
    if (recoveryObservedAt != null) {
      if (openKey != null) {
        log.info("reachability: recovered — closing episode {}", openKey);
        if (repository.close(openKey, recoveryObservedAt)) {
          recoveryObservedAt = null;
          openKey = null;
        }
      } else if (pendingOpen != null) {
        // The outage ended before its opening write ever landed. Record it retrospectively rather
        // than losing the incident: the original start, closed at the observed recovery.
        log.warn(
            "reachability: recording episode {} retrospectively — its opening write never landed",
            pendingOpen.key());
        if (write(pendingOpen)) {
          // ⚠ Cleared the moment the row is DURABLE, not once the close also lands. Held past
          // that, a stale pending object survives the close and is then replayed as the NEXT
          // outage — whose insert is an ON CONFLICT no-op, so that outage is silently lost.
          String recorded = pendingOpen.key();
          pendingOpen = null;
          if (repository.close(recorded, recoveryObservedAt)) {
            recoveryObservedAt = null;
          }
        }
      } else {
        recoveryObservedAt = null;
      }
    }

    if (hostNetworkDown && pendingOpen != null) {
      if (openKey != null && openKey.equals(pendingOpen.key())) {
        pendingOpen = null; // already durable under this very key
      } else if (openKey == null && write(pendingOpen)) {
        pendingOpen = null;
      }
      // An open row under a DIFFERENT key means a previous episode's close has not landed yet.
      // The pending observation is kept for a later pass rather than merged into that row.
    }
  }

  private boolean write(PendingOpen e) {
    return repository.open(
        e.key(), e.startedAt(), e.probed(), e.unreachable(), e.quorum(), e.failedNames(),
        e.detail());
  }

  /** An observed outage whose opening write has not landed yet. */
  private record PendingOpen(
      String key,
      Instant startedAt,
      int probed,
      int unreachable,
      int quorum,
      String failedNames,
      String detail) {}

  /**
   * ⚠️ ANY HTTP RESPONSE COUNTS AS REACHABLE, including 4xx and 5xx. The question is whether packets
   * leave the host and come back, not whether the endpoint likes an unauthenticated HEAD. Treating a
   * 403 as unreachable would report a network death every time a vendor tightened an endpoint.
   */
  // Package-private and overridable ON PURPOSE: it is the only seam that touches a real socket,
  // so a test can drive the quorum arithmetic without opening one. Everything above it is the
  // logic worth pinning.
  boolean reachable(Destination d) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(d.uri()).method("HEAD", HttpRequest.BodyPublishers.noBody())
              .timeout(http.connectTimeout().orElse(Duration.ofSeconds(5)))
              .build();
      http.send(request, HttpResponse.BodyHandlers.discarding());
      return true;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      // Shutdown, not a network verdict. Reporting unreachable here would open an episode every
      // time the service stops.
      return true;
    } catch (Exception unreachable) {
      return false;
    }
  }

  /**
   * ⚠ The origins-only rule is ENFORCED here, not merely documented above it.
   *
   * <p>An earlier revision validated only the {@code name=value} shape, so a path, a query or
   * embedded credentials were all accepted — and the value flows straight into a log line, a TEXT
   * column and an outbound request. An ntfy topic URL IS the credential, which makes "no path" a
   * security invariant rather than tidiness.
   *
   * <p>⚠ Failures NEVER echo the offending value. The whole reason to reject it is that it may be
   * a secret, and an exception message is written to the same logs the value was being kept out of.
   */
  private static List<Destination> parse(String spec) {
    List<Destination> out = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    String[] entries = spec.split(",");
    for (int i = 0; i < entries.length; i++) {
      String trimmed = entries[i].trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      int eq = trimmed.indexOf('=');
      if (eq <= 0 || eq == trimmed.length() - 1) {
        throw new IllegalArgumentException(
            "reachability destination #" + (i + 1) + " must be name=origin"
                + " (value withheld: it may carry a credential)");
      }
      String name = trimmed.substring(0, eq);
      if (!SAFE_NAME.matcher(name).matches()) {
        throw new IllegalArgumentException(
            "reachability destination #" + (i + 1) + " has an unsafe name;"
                + " expected [A-Za-z0-9_-]{1,32}");
      }
      URI uri;
      try {
        uri = new URI(trimmed.substring(eq + 1));
      } catch (URISyntaxException malformed) {
        throw new IllegalArgumentException(
            "reachability destination '" + name + "' is not a valid URI"
                + " (value withheld: it may carry a credential)");
      }
      String scheme =
          uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
      String path = uri.getPath();
      boolean originOnly =
          ("http".equals(scheme) || "https".equals(scheme))
              && uri.getHost() != null
              && !uri.getHost().isEmpty()
              && uri.getUserInfo() == null
              && (path == null || path.isEmpty() || "/".equals(path))
              && uri.getQuery() == null
              && uri.getFragment() == null;
      if (!originOnly) {
        throw new IllegalArgumentException(
            "reachability destination '" + name + "' must be a bare http(s) ORIGIN —"
                + " no path, query, fragment or credentials (value withheld)");
      }
      // Duplicates would inflate probed_count AND let ONE vendor meet the quorum by itself, which
      // is precisely the host-vs-vendor confusion the quorum exists to prevent.
      // ⚠ An EXPLICIT default port is the same origin as an implicit one, so it must normalize to
      // the same string. Otherwise https://vendor and https://vendor:443 are accepted as two
      // destinations, which inflates probed_count and lets ONE vendor satisfy the majority alone —
      // defeating the very distinction the quorum exists to draw.
      int port = uri.getPort();
      if (("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80)) {
        port = -1;
      }
      String origin =
          scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + (port < 0 ? "" : ":" + port);
      if (!seen.add(origin)) {
        throw new IllegalArgumentException(
            "reachability destination '" + name + "' duplicates an earlier origin");
      }
      out.add(new Destination(name, uri));
    }
    if (out.isEmpty()) {
      throw new IllegalArgumentException("reachability needs at least one destination");
    }
    return List.copyOf(out);
  }

  /** A probe target: a human name for the record, and an ORIGIN to reach. */
  record Destination(String name, URI uri) {}
}
