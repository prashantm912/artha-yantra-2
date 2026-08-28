package in.arthayantra.marketdata.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

/**
 * A boot-time 403 must NOT open the shared {@code kite-rest} breaker.
 *
 * <p><b>The measured defect, 2026-08-28.</b> At boot, before the daily login, ten consecutive Kite
 * calls returned {@code 403 TokenException "Incorrect api_key or access_token"} — a 100% failure
 * rate over the 10-call window — and opened this breaker. Kite was healthy and answering; we simply
 * had no token yet. Because the breaker is SHARED, that browned out every other consumer until
 * the login at 08:46 — about six minutes.
 *
 * <p>⚠️ The larger 08-27 episode (19 minutes of {@code futures_oi}, 564 cached-data lines) is
 * BLAST-RADIUS CONTEXT for why a shared breaker matters, NOT evidence that boot 403s caused it:
 * those clusters began at 09:13 and remain unexplained. Cross-vendor review caught this being
 * stated as causality here, which is the same overclaim the CLAUDE.md entry warns against.
 *
 * <p><b>Why this asks the BOUND config rather than grepping the YAML.</b> The ignore list is class
 * NAMES resolved at binding time, and a nested class needs the {@code $} form. A wrong spelling does
 * not fail loudly — it yields a breaker that quietly still counts 403s, which looks exactly like the
 * fix working right up until the next pre-login boot.
 *
 * <p><b>Why a context SLICE and not {@code @SpringBootTest}.</b> The full context needs a database,
 * so a whole-application test would make a config assertion depend on Testcontainers. This loads the
 * REAL {@code application.yml} through {@link ConfigDataApplicationContextInitializer} and only the
 * resilience4j autoconfiguration — faithful to the shipped file, with nothing else to go wrong.
 */
class KiteBreakerIgnoresTokenForbiddenTest {

  private static final String YML =
      "services/market-data-service/src/main/resources/application.yml";

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withInitializer(new ConfigDataApplicationContextInitializer())
          .withConfiguration(AutoConfigurations.of(CircuitBreakerAutoConfiguration.class));

  private static Predicate<Throwable> ignoreFor(AssertableApplicationContext ctx, String breaker) {
    CircuitBreakerConfig config =
        ctx.getBean(CircuitBreakerRegistry.class).circuitBreaker(breaker).getCircuitBreakerConfig();
    return config.getIgnoreExceptionPredicate();
  }

  private static Throwable forbidden() {
    return HttpClientErrorException.create(
        HttpStatus.FORBIDDEN,
        "Forbidden",
        HttpHeaders.EMPTY,
        "{\"error_type\":\"TokenException\"}".getBytes(StandardCharsets.UTF_8),
        StandardCharsets.UTF_8);
  }

  @Test
  void aTokenForbiddenIsNotCountedAgainstTheKiteBreaker() {
    runner.run(
        ctx -> {
          assertThat(forbidden()).isInstanceOf(HttpClientErrorException.Forbidden.class);
          assertThat(ignoreFor(ctx, "kite-rest").test(forbidden()))
              .as(
                  "a 403 says we are not authenticated, never that Kite is down — counting it"
                      + " opened the SHARED breaker at boot and browned out every other consumer")
              .isTrue();
        });
  }

  /**
   * The guard against over-reaching: only 403 is exempt. A 5xx is exactly what a breaker is FOR, and
   * an ignore list that swallowed it would disable the protection this change is not meant to touch.
   */
  @Test
  void aServerErrorStillCountsAgainstTheKiteBreaker() {
    runner.run(
        ctx -> {
          Throwable serverError =
              HttpServerErrorException.create(
                  HttpStatus.INTERNAL_SERVER_ERROR,
                  "Server Error",
                  HttpHeaders.EMPTY,
                  new byte[0],
                  StandardCharsets.UTF_8);
          assertThat(ignoreFor(ctx, "kite-rest").test(serverError)).isFalse();
        });
  }

  /** The pre-existing local-pacing exemption must survive this change. */
  @Test
  void theLocalPacingExemptionIsUnchanged() {
    runner.run(
        ctx ->
            assertThat(
                    ignoreFor(ctx, "kite-rest")
                        .test(
                            RequestNotPermitted.createRequestNotPermitted(
                                RateLimiter.ofDefaults("probe"))))
                .isTrue());
  }

  /**
   * ⚠️ Scoped deliberately to {@code kite-rest}. OpenAlgo has its own auth and its own breaker, and
   * nothing has been MEASURED there — widening on symmetry alone is how an unreviewed exemption
   * spreads. Pinned as a test so the asymmetry is a decision on the record, not an oversight.
   */
  @Test
  void theOpenAlgoBreakerIsDeliberatelyNotGivenTheSameExemption() throws IOException {
    String yml = Files.readString(repoRoot().resolve(YML), StandardCharsets.UTF_8);
    int mentions = yml.split("HttpClientErrorException[$]Forbidden", -1).length - 1;

    assertThat(mentions)
        .as("exactly one breaker exempts 403; read this test's javadoc before adding another")
        .isEqualTo(1);
  }

  private static Path repoRoot() {
    Path dir = Paths.get("").toAbsolutePath();
    for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
      if (Files.exists(dir.resolve("deploy/docker-compose.yml"))) {
        return dir;
      }
    }
    throw new IllegalStateException("could not locate the repo root");
  }
}
