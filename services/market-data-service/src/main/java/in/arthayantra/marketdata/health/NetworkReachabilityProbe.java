package in.arthayantra.marketdata.health;

import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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

  private final HttpClient http;
  private final NetworkReachabilityRepository repository;
  private final Clock clock;
  private final int quorum;
  private final boolean enabled;
  private final AtomicInteger unreachableGauge = new AtomicInteger();

  public NetworkReachabilityProbe(
      NetworkReachabilityRepository repository,
      MeterRegistry meterRegistry,
      Clock clock,
      @Value("${artha.health.reachability.enabled:true}") boolean enabled,
      @Value("${artha.health.reachability.timeout-seconds:5}") int timeoutSeconds,
      @Value(
              "${artha.health.reachability.destinations:"
                  + "kite=https://api.kite.trade,"
                  + "upstox=https://api.upstox.com,"
                  + "nse=https://www.nseindia.com,"
                  + "telegram=https://api.telegram.org,"
                  + "ntfy=https://ntfy.sh}")
          String destinationSpec,
      @Value("${artha.health.reachability.quorum:3}") int quorum) {
    this.repository = repository;
    this.clock = clock;
    this.enabled = enabled;
    this.quorum = quorum;
    this.destinations = parse(destinationSpec);
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
    unreachableGauge.set(failed.size());
    Instant now = clock.instant();

    boolean hostNetworkDown = failed.size() >= quorum;
    String openKey = repository.openEpisodeKey().orElse(null);

    if (hostNetworkDown && openKey == null) {
      String key = "reach-" + now.toEpochMilli();
      String names = String.join(",", failed);
      log.warn(
          "reachability: {} of {} destinations unreachable ({}) — opening episode {}."
              + " This is the HOST network, not one vendor.",
          failed.size(), destinations.size(), names, key);
      repository.open(key, now, destinations.size(), failed.size(), names,
          "quorum " + failed.size() + "/" + destinations.size() + " unreachable");
    } else if (!hostNetworkDown && openKey != null) {
      log.info("reachability: recovered ({} unreachable) — closing episode {}", failed.size(),
          openKey);
      repository.close(openKey, now);
    } else if (!failed.isEmpty()) {
      // Below quorum: a vendor problem, deliberately NOT an episode. Logged so it is still visible,
      // because "one vendor is down" is a real finding — it is just a different one.
      log.info("reachability: {} unreachable ({}) — below the {} quorum, treating as vendor-local",
          failed.size(), String.join(",", failed), quorum);
    }
  }

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

  private static List<Destination> parse(String spec) {
    List<Destination> out = new ArrayList<>();
    for (String entry : spec.split(",")) {
      String trimmed = entry.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      int eq = trimmed.indexOf('=');
      if (eq <= 0 || eq == trimmed.length() - 1) {
        throw new IllegalArgumentException(
            "reachability destination must be name=origin, got: " + trimmed);
      }
      out.add(new Destination(trimmed.substring(0, eq), URI.create(trimmed.substring(eq + 1))));
    }
    if (out.isEmpty()) {
      throw new IllegalArgumentException("reachability needs at least one destination");
    }
    return List.copyOf(out);
  }

  /** A probe target: a human name for the record, and an ORIGIN to reach. */
  record Destination(String name, URI uri) {}
}
