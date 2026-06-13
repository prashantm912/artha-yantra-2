package in.arthayantra.gateway.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import in.arthayantra.gateway.auth.OwnerAuthService;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Phase-8 IT (A.7b): per-symbol isolation, conflation under burst, never-conflated signals, and
 * 401 on unauthenticated upgrade — against a real Redis.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class StompWsBridgeIntegrationTest {

  private static final String OWNER_PASSWORD = "ws-test-password";

  @Container @ServiceConnection
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

  @DynamicPropertySource
  static void ownerHash(DynamicPropertyRegistry registry) {
    registry.add(
        "artha.owner-password-hash", () -> OwnerAuthService.ENCODER.encode(OWNER_PASSWORD));
  }

  @LocalServerPort private int port;
  @Autowired private WebTestClient webTestClient;
  @Autowired private StringRedisTemplate redis;

  private String login() {
    return Objects.requireNonNull(
            webTestClient
                .post()
                .uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue("password=" + OWNER_PASSWORD)
                .exchange()
                .expectStatus().isNoContent()
                .returnResult(Void.class)
                .getResponseCookies()
                .getFirst("SESSION"))
        .getValue();
  }

  @Test
  void bridgeDeliversOnlySubscribedSymbolsConflatesTicksAndNeverConflatesSignals() {
    String session = login();
    List<String> received = new CopyOnWriteArrayList<>();

    HttpHeaders headers = new HttpHeaders();
    headers.add(HttpHeaders.COOKIE, "SESSION=" + session);

    ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();
    Mono<Void> sessionMono =
        client.execute(
            URI.create("ws://127.0.0.1:" + port + "/ws"),
            headers,
            wsSession -> {
              Flux<WebSocketMessage> outbound =
                  Flux.concat(
                      Mono.just(
                          wsSession.textMessage(
                              StompFrame.of(
                                      "CONNECT",
                                      java.util.Map.of("accept-version", "1.2"),
                                      "")
                                  .serialize())),
                      Mono.just(
                          wsSession.textMessage(
                              StompFrame.of(
                                      "SUBSCRIBE",
                                      java.util.Map.of(
                                          "id", "sub-0",
                                          "destination", "/topic/ticks.NSE.TEST"),
                                      "")
                                  .serialize())),
                      Mono.just(
                          wsSession.textMessage(
                              StompFrame.of(
                                      "SUBSCRIBE",
                                      java.util.Map.of(
                                          "id", "sub-1", "destination", "/topic/signals"),
                                      "")
                                  .serialize())));
              return wsSession
                  .send(outbound)
                  .thenMany(
                      wsSession
                          .receive()
                          .map(WebSocketMessage::getPayloadAsText)
                          .doOnNext(received::add))
                  .then();
            });
    sessionMono.subscribe();

    // wait until CONNECTED arrives (subscriptions registered) — generous for CI
    await()
        .atMost(Duration.ofSeconds(30))
        .until(() -> received.stream().anyMatch(f -> f.startsWith("CONNECTED")));

    // give the Redis-side subscriptions a beat to attach
    sleep(500);

    // burst: 1000 ticks on the subscribed symbol + noise on another within ~1s
    for (int i = 1; i <= 1000; i++) {
      redis.convertAndSend("ticks.NSE.TEST", "{\"lastPrice\":\"" + i + ".00\",\"seq\":" + i + "}");
      if (i % 20 == 0) {
        redis.convertAndSend("ticks.NSE.OTHER", "{\"seq\":" + i + "}");
      }
    }
    // 5 signals — every one must arrive
    for (int i = 1; i <= 5; i++) {
      redis.convertAndSend("signals", "{\"signal\":" + i + "}");
    }

    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> countBodies(received, "\"signal\":") >= 5);
    sleep(500); // a final flush window

    List<String> tickFrames =
        received.stream()
            .filter(f -> f.startsWith("MESSAGE") && f.contains("/topic/ticks.NSE.TEST"))
            .toList();
    long otherFrames =
        received.stream().filter(f -> f.contains("ticks.NSE.OTHER")).count();
    long signalFrames = countBodies(received, "\"signal\":");

    // only the subscribed symbol — never the firehose
    assertThat(otherFrames).isZero();
    // conflation: a 1000-tick burst yields a bounded number of frames, latest price last
    assertThat(tickFrames).isNotEmpty().hasSizeLessThanOrEqualTo(30);
    assertThat(tickFrames.get(tickFrames.size() - 1)).contains("\"lastPrice\":\"1000.00\"");
    // signals are never conflated
    assertThat(signalFrames).isEqualTo(5);
  }

  /**
   * Opens an authenticated WS session driven by a sink, collecting every inbound frame. CONNECT
   * is the guaranteed first frame of the send flux (emitted only after the handshake), and the
   * CONNECTED wait is generous — CI runners are slow and 2-core.
   */
  private reactor.core.publisher.Sinks.Many<String> openSession(String session, List<String> received) {
    reactor.core.publisher.Sinks.Many<String> outbound =
        reactor.core.publisher.Sinks.many().unicast().onBackpressureBuffer();
    HttpHeaders headers = new HttpHeaders();
    headers.add(HttpHeaders.COOKIE, "SESSION=" + session);
    String connectFrame =
        StompFrame.of("CONNECT", java.util.Map.of("accept-version", "1.2"), "").serialize();
    new ReactorNettyWebSocketClient()
        .execute(
            URI.create("ws://127.0.0.1:" + port + "/ws"),
            headers,
            wsSession ->
                wsSession
                    .send(
                        Flux.concat(Mono.just(connectFrame), outbound.asFlux())
                            .map(wsSession::textMessage))
                    .and(
                        wsSession
                            .receive()
                            .map(WebSocketMessage::getPayloadAsText)
                            .doOnNext(received::add)
                            .then()))
        .subscribe();
    await()
        .atMost(Duration.ofSeconds(30))
        .until(() -> received.stream().anyMatch(f -> f.startsWith("CONNECTED")));
    return outbound;
  }

  @Test
  void unsubscribeStopsDelivery() {
    List<String> received = new CopyOnWriteArrayList<>();
    var outbound = openSession(login(), received);

    outbound.tryEmitNext(
        StompFrame.of(
                "SUBSCRIBE",
                java.util.Map.of("id", "sub-u", "destination", "/topic/ticks.NSE.UNSUB"),
                "")
            .serialize());
    sleep(500); // let the Redis-side subscription attach

    redis.convertAndSend("ticks.NSE.UNSUB", "{\"marker\":\"before\"}");
    await()
        .atMost(Duration.ofSeconds(20))
        .until(() -> countBodies(received, "\"marker\":\"before\"") >= 1);

    outbound.tryEmitNext(
        StompFrame.of("UNSUBSCRIBE", java.util.Map.of("id", "sub-u"), "").serialize());
    sleep(500); // unsubscribe + one flush window

    for (int i = 0; i < 10; i++) {
      redis.convertAndSend("ticks.NSE.UNSUB", "{\"marker\":\"after\"}");
    }
    sleep(1000); // several flush windows — nothing may arrive

    assertThat(countBodies(received, "\"marker\":\"after\"")).isZero();
  }

  @Test
  void allRegisteredTopicFormsAcceptSubscriptionSilently() {
    List<String> received = new CopyOnWriteArrayList<>();
    var outbound = openSession(login(), received);

    List<String> forms =
        List.of(
            "/topic/candles.1m.NSE.TEST",
            "/topic/signals",
            "/topic/jobs/job-42",
            "/topic/system",
            "/topic/options.chain");
    for (int i = 0; i < forms.size(); i++) {
      outbound.tryEmitNext(
          StompFrame.of(
                  "SUBSCRIBE",
                  java.util.Map.of("id", "form-" + i, "destination", forms.get(i)),
                  "")
              .serialize());
    }
    // and one ILLEGAL form that must be rejected
    outbound.tryEmitNext(
        StompFrame.of(
                "SUBSCRIBE", java.util.Map.of("id", "bad-0", "destination", "/topic/firehose"), "")
            .serialize());

    await()
        .atMost(Duration.ofSeconds(20))
        .until(() -> received.stream().anyMatch(f -> f.startsWith("ERROR")));
    sleep(800); // silence window for the legal forms

    long errors = received.stream().filter(f -> f.startsWith("ERROR")).count();
    long messages = received.stream().filter(f -> f.startsWith("MESSAGE")).count();
    assertThat(errors).as("only the illegal destination errors").isEqualTo(1);
    assertThat(messages).as("producers do not exist yet — silence is fine (A.7.2)").isZero();
  }

  @Test
  void browserStyleHandshakeNegotiatesTheStompSubprotocol() throws InterruptedException {
    // RFC 6455: a real browser FAILS the upgrade unless the server echoes one of the
    // Sec-WebSocket-Protocol values it offered. @stomp/stompjs always offers v12/v11/v10.stomp,
    // so the gateway MUST advertise + select one or no browser socket ever opens. The Netty
    // client here is lenient (it does not enforce the echo), which is why this needs an explicit
    // assertion rather than a connection failure.
    java.util.concurrent.atomic.AtomicReference<String> negotiated =
        new java.util.concurrent.atomic.AtomicReference<>();
    java.util.concurrent.CountDownLatch handled = new java.util.concurrent.CountDownLatch(1);
    HttpHeaders headers = new HttpHeaders();
    headers.add(HttpHeaders.COOKIE, "SESSION=" + login());

    org.springframework.web.reactive.socket.WebSocketHandler clientHandler =
        new org.springframework.web.reactive.socket.WebSocketHandler() {
          @Override
          public List<String> getSubProtocols() {
            return List.of("v12.stomp", "v11.stomp", "v10.stomp");
          }

          @Override
          public Mono<Void> handle(org.springframework.web.reactive.socket.WebSocketSession s) {
            negotiated.set(s.getHandshakeInfo().getSubProtocol());
            handled.countDown();
            return s.close(); // close immediately — never leak a server session into later tests
          }
        };

    new ReactorNettyWebSocketClient()
        .execute(URI.create("ws://127.0.0.1:" + port + "/ws"), headers, clientHandler)
        .subscribe();

    assertThat(handled.await(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    assertThat(negotiated.get()).isEqualTo("v12.stomp");
  }

  @Test
  void unauthenticatedUpgradeIsRejected() {
    webTestClient
        .get()
        .uri("/ws")
        .header(HttpHeaders.UPGRADE, "websocket")
        .header(HttpHeaders.CONNECTION, "Upgrade")
        // the public RFC 6455 sample nonce, not a credential
        .header("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==") // gitleaks:allow
        .header("Sec-WebSocket-Version", "13")
        .exchange()
        .expectStatus().isUnauthorized();
  }

  private static long countBodies(List<String> frames, String marker) {
    return frames.stream().filter(f -> f.startsWith("MESSAGE") && f.contains(marker)).count();
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
