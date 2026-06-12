package in.arthayantra.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.gateway.auth.OwnerAuthService;
import java.util.Objects;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
 * Phase-5 IT battery (A.2) against a real Redis (pinned to the production compose tag, A.10):
 * login round-trip + cookie attributes, session probe, logout, rate limiting, 401/503 envelopes,
 * security headers, correlation id.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestMethodOrder(MethodOrderer.MethodName.class)
class GatewayIntegrationTest {

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
  @Autowired private org.springframework.data.redis.core.ReactiveStringRedisTemplate redis;

  @org.junit.jupiter.api.BeforeEach
  void resetLoginLimiter() {
    // every test's login() counts against the same client IP — clear between tests
    redis.keys("login:*").flatMap(redis::delete).blockLast();
  }

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
  void t01_loginSetsHardenedSessionCookie() {
    ResponseCookie session = login();

    assertThat(session.isHttpOnly()).isTrue();
    assertThat(session.getSameSite()).isEqualTo("Strict");
    assertThat(session.getPath()).isEqualTo("/");
    assertThat(session.isSecure()).isFalse(); // plain loopback HTTP (A.2.5)
  }

  @Test
  void t02_wrongPasswordIs401Envelope() {
    client
        .post()
        .uri("/api/v1/auth/login")
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .bodyValue("password=nope")
        .exchange()
        .expectStatus().isUnauthorized()
        .expectBody()
        .jsonPath("$.code").isEqualTo("AUTH_BAD_CREDENTIALS")
        .jsonPath("$.message").isNotEmpty();
  }

  @Test
  void t03_sessionProbeReflectsAuthenticationAndProfile() {
    ResponseCookie session = login();

    client
        .get()
        .uri("/api/v1/auth/session")
        .cookie("SESSION", session.getValue())
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.authenticated").isEqualTo(true)
        .jsonPath("$.loginTime").isNotEmpty()
        .jsonPath("$.profile").isEqualTo("mock");

    client
        .get()
        .uri("/api/v1/auth/session")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.authenticated").isEqualTo(false);
  }

  @Test
  void t04_logoutInvalidatesSession() {
    ResponseCookie session = login();

    // fetch the CSRF cookie (mutating calls need the token, A.2.3)
    String xsrf =
        Objects.requireNonNull(
                client
                    .get()
                    .uri("/api/v1/auth/session")
                    .cookie("SESSION", session.getValue())
                    .exchange()
                    .expectStatus().isOk()
                    .returnResult(Void.class)
                    .getResponseCookies()
                    .getFirst("XSRF-TOKEN"))
            .getValue();

    client
        .post()
        .uri("/api/v1/auth/logout")
        .cookie("SESSION", session.getValue())
        .cookie("XSRF-TOKEN", xsrf)
        .header("X-XSRF-TOKEN", xsrf)
        .exchange()
        .expectStatus().isNoContent();

    client
        .get()
        .uri("/api/v1/auth/session")
        .cookie("SESSION", session.getValue())
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.authenticated").isEqualTo(false);
  }

  @Test
  void t05_unauthenticatedApiCallGets401Envelope() {
    client
        .get()
        .uri("/api/v1/strategies/anything")
        .exchange()
        .expectStatus().isUnauthorized()
        .expectBody()
        .jsonPath("$.code").isEqualTo("AUTH_REQUIRED");
  }

  @Test
  void t06_absentUpstreamGets503EnvelopeNeverTraces() {
    ResponseCookie session = login();

    client
        .mutate().responseTimeout(java.time.Duration.ofSeconds(30)).build()
        .get()
        .uri("/api/v1/strategies/anything")
        .cookie("SESSION", session.getValue())
        .exchange()
        .expectStatus().isEqualTo(503)
        .expectBody()
        .jsonPath("$.code").isEqualTo("UPSTREAM_UNAVAILABLE")
        .jsonPath("$.details").isMap();
  }

  @Test
  void t07_securityHeadersOnEveryResponse() {
    client
        .get()
        .uri("/api/v1/auth/session")
        .exchange()
        .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
        .expectHeader().valueEquals("X-Frame-Options", "DENY")
        .expectHeader().valueEquals("Referrer-Policy", "no-referrer")
        .expectHeader().valueMatches("Content-Security-Policy", "default-src 'self'.*")
        .expectHeader().exists("X-Request-Id");
  }

  @Test
  void t99_sixthLoginAttemptWithinOneMinuteIs429WithCooldown() {
    // runs last (MethodName order): the cooldown poisons this client IP
    for (int i = 0; i < 5; i++) {
      client
          .post()
          .uri("/api/v1/auth/login")
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .bodyValue("password=wrong-" + i)
          .exchange()
          .expectStatus().isUnauthorized();
    }
    client
        .post()
        .uri("/api/v1/auth/login")
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .bodyValue("password=" + OWNER_PASSWORD)
        .exchange()
        .expectStatus().isEqualTo(429)
        .expectBody()
        .jsonPath("$.code").isEqualTo("AUTH_RATE_LIMITED")
        .jsonPath("$.details.retryAfterMs").isNumber();
  }
}
