package in.arthayantra.marketdata.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Regression for the 2026-07-20 ntfy alerting outage on the MARKET-DATA ops channel.
 *
 * <p>{@link NtfyClient} carries title and priority as HTTP HEADERS, which the JDK client rejects
 * outright when they contain a non-ASCII char. Two ops alerts hardcode an em-dash in the title —
 * the Manas Arora and Minervini backtest-failure pages — so both were dead on arrival: the client
 * swallows every exception, so they failed completely silently.
 *
 * <p>Drives the real client against a loopback stub; never a real ntfy topic (the URL and topic
 * here are literals, and no credential appears in this file).
 */
class NtfyClientHeaderTest {

  private HttpServer server;
  private NtfyClient client;
  private SimpleMeterRegistry meters;
  private final AtomicReference<String> receivedTitle = new AtomicReference<>();
  private final AtomicReference<String> receivedPriority = new AtomicReference<>();
  private final AtomicReference<byte[]> receivedBody = new AtomicReference<>();

  @BeforeEach
  void startStub() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/",
        exchange -> {
          receivedTitle.set(exchange.getRequestHeaders().getFirst("Title"));
          receivedPriority.set(exchange.getRequestHeaders().getFirst("Priority"));
          try (InputStream in = exchange.getRequestBody()) {
            receivedBody.set(in.readAllBytes());
          }
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();
    meters = new SimpleMeterRegistry();
    client =
        new NtfyClient(
            RestClient.builder(),
            meters,
            "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":"
                + server.getAddress().getPort(),
            "unit-test-topic");
  }

  @AfterEach
  void stopStub() {
    server.stop(0);
  }

  /** The exact title of the two hardcoded-em-dash ops alerts now reaches the wire. */
  @Test
  void emDashTitleReachesTheWire() {
    client.send("ArthaYantra — Manas Arora backtest failed", "high", "stack trace");

    assertThat(receivedTitle.get()).isEqualTo("ArthaYantra - Manas Arora backtest failed");
    assertThat(receivedPriority.get()).isEqualTo("high");
  }

  /** Contract-drift alerts join their findings with newlines — the BODY keeps them verbatim. */
  @Test
  void multiLineBodyKeepsNewlinesAndUtf8() {
    String body = "field removed — quote.last_price\nfield retyped → volume";

    client.send("Kite contract drift", "urgent", body);

    assertThat(new String(receivedBody.get(), StandardCharsets.UTF_8)).isEqualTo(body);
  }

  /** A delivery failure is swallowed by design here, but must still be counted. */
  @Test
  void deliveryFailureIsCounted() {
    server.stop(0); // nothing listening -> transport failure

    client.send("ArthaYantra feed stalled", "urgent", "detail");

    assertThat(meters.counter("ay_ntfy_send_failed_total").count()).isEqualTo(1.0);
  }

  /** A blank topic stays a silent no-op (mock stacks configure no ntfy). */
  @Test
  void blankTopicIsANoOp() {
    NtfyClient unconfigured =
        new NtfyClient(RestClient.builder(), meters, "http://127.0.0.1:1", "");

    unconfigured.send("title", "urgent", "detail");

    assertThat(receivedTitle.get()).isNull();
    assertThat(meters.counter("ay_ntfy_send_failed_total").count()).isZero();
  }

  /** trySend reports the delivery outcome instead of swallowing it — the EveningChainCanary case. */
  @Test
  void trySendReportsTrueOnSuccessAndFalseOnFailure() {
    assertThat(client.trySend("ok", "default", "body")).isTrue();

    server.stop(0);
    assertThat(client.trySend("ok", "default", "body")).isFalse();
  }

  /** trySend on an unconfigured (blank-topic) client reports false — "not sent", not an exception. */
  @Test
  void trySendReportsFalseWhenNoTopicIsConfigured() {
    NtfyClient unconfigured =
        new NtfyClient(RestClient.builder(), meters, "http://127.0.0.1:1", "");

    assertThat(unconfigured.trySend("title", "urgent", "detail")).isFalse();
  }
}
