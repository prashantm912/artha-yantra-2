package in.arthayantra.gateway.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import in.arthayantra.gateway.auth.OwnerAuthService;
import java.time.Duration;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Phase-17 IT: the aggregated status rolls up Redis shared keys WITHOUT REST fan-out, and a
 * flipped {@code kite:session:status} key shows within one 5 s cache window. The /topic/system
 * destination maps to the {@code kite.status} channel (B-13 deltas).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class SystemStatusIntegrationTest {

  private static final String OWNER_PASSWORD = "test-owner-password";

  @Container @ServiceConnection
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

  @DynamicPropertySource
  static void ownerHash(DynamicPropertyRegistry registry) {
    registry.add(
        "artha.owner-password-hash", () -> OwnerAuthService.ENCODER.encode(OWNER_PASSWORD));
  }

  @Autowired private WebTestClient client;
  @Autowired private ReactiveStringRedisTemplate redis;

  private ResponseCookie login() {
    return Objects.requireNonNull(
        client
            .post()
            .uri("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .bodyValue("password=" + OWNER_PASSWORD)
            .exchange()
            .expectStatus().isNoContent()
            .returnResult(Void.class)
            .getResponseCookies()
            .getFirst("SESSION"));
  }

  @Test
  void statusRollsUpRedisKeysAndReflectsFlipsWithinOneCacheWindow() {
    ResponseCookie session = login();
    redis.opsForValue().set("kite:session:status", "MOCK").block();

    client
        .get()
        .uri("/api/v1/system/status")
        .cookie(session.getName(), session.getValue())
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.status").isEqualTo("UP")
        .jsonPath("$.kite").isEqualTo("MOCK")
        .jsonPath("$.jobs.queued").isEqualTo(0); // jobs:summary absent until Phase 28

    redis.opsForValue().set("kite:session:status", "TOKEN_EXPIRED").block();

    await()
        .atMost(Duration.ofSeconds(7)) // one 5 s cache window + slack
        .untilAsserted(
            () ->
                assertThat(
                        client
                            .get()
                            .uri("/api/v1/system/status")
                            .cookie(session.getName(), session.getValue())
                            .exchange()
                            .expectStatus().isOk()
                            .expectBody(String.class)
                            .returnResult()
                            .getResponseBody())
                    .contains("\"status\":\"DEGRADED\"")
                    .contains("TOKEN_EXPIRED"));
  }

  @Test
  void statusRequiresASession() {
    client.get().uri("/api/v1/system/status").exchange().expectStatus().isUnauthorized();
  }
}
