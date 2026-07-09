package in.arthayantra.strategysignal.signals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * External dead-man's-switch for the daily swing batches — the ONE failure the in-stack canaries
 * cannot catch: the whole stack (or host) being DOWN at batch time.
 *
 * <p>The P0-4 {@code strategy.swing_batch_runs} did-not-run canary and the market-data
 * {@code DataHealthCanary} both run INSIDE this stack, so a full-stack/host outage kills the watchman
 * together with the batch and no alert fires — the root cause of the 2026-07-09 silently-missed 20:05
 * batch (Docker was down; nothing on-box could report it). This heartbeat pings an EXTERNAL monitor
 * (healthchecks.io / UptimeRobot heartbeat / any dead-man's-switch URL) once per trading evening,
 * AFTER both swing batches (Minervini 20:00, Manas 20:05). If the stack is down at 20:15 IST the ping
 * never arrives and the external monitor alerts the owner on the missed schedule — off-box, so it
 * survives exactly the outage the in-stack canaries can't see. Data-quality-while-alive stays with the
 * in-stack canaries (which CAN alert, since the stack is up); this only proves the process ran.
 *
 * <p>Dormant until armed: it loads only when {@code artha.heartbeat.url} is set (paste the monitor's
 * ping URL into {@code .env} as {@code ARTHA_HEARTBEAT_URL}, then redeploy). Configure the external
 * check to EXPECT a ping on the matching schedule (cron {@code 15 20 * * 1-5}, TZ Asia/Kolkata) with a
 * grace window, so a missed 20:15 ping raises the alert. Fail-soft: a ping failure is logged, never
 * thrown — the batch is unaffected (this observes it, never gates it).
 */
@Component
@ConditionalOnProperty(name = "artha.heartbeat.url")
public class SwingBatchHeartbeat {

  private static final Logger log = LoggerFactory.getLogger(SwingBatchHeartbeat.class);

  private final String url;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  /** Wires the dead-man's-switch ping URL (present only when armed — see the class conditional). */
  public SwingBatchHeartbeat(@Value("${artha.heartbeat.url}") String url) {
    this.url = url;
  }

  /** Post-batch daily ping (20:15 IST weekdays) — after the 20:00 + 20:05 swing batches. */
  @Scheduled(cron = "${artha.heartbeat.swing-cron:0 15 20 * * MON-FRI}", zone = "Asia/Kolkata")
  public void beat() {
    if (url == null || url.isBlank()) {
      return; // belt-and-braces; the conditional already gates loading
    }
    try {
      send(url);
      log.info("swing batch heartbeat: pinged the external dead-man's-switch");
    } catch (Exception e) {
      log.warn("swing batch heartbeat ping failed (external monitor may alert): {}", e.toString());
    }
  }

  /** The actual GET — package-private so a unit test can capture it without real network I/O. */
  void send(String pingUrl) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(pingUrl)).timeout(Duration.ofSeconds(5)).GET().build();
    http.send(request, HttpResponse.BodyHandlers.discarding());
  }
}
