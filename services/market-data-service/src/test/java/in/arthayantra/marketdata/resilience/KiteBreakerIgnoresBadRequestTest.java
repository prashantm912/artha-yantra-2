package in.arthayantra.marketdata.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

/**
 * NEW-14 — a permanent 400 must NOT open the shared {@code kite-rest} breaker.
 *
 * <p><b>The measured defect, 2026-09-02 15:45 IST.</b> Five
 * {@code 400 "invalid segment for continuous data"} responses, from {@code GapBackfillService}'s EOD
 * prefetch asking Kite for 1d history on BFO SENSEX option contracts, opened this breaker post-close.
 * Kite was healthy and answering; we had asked for something it does not serve. A 400 is PERMANENT —
 * the identical request will fail identically forever — so it can never mean "the upstream is
 * unavailable", which is the only condition this breaker exists to detect.
 *
 * <p>⚠️ <b>Why five calls were enough, and this is the part worth carrying forward.</b> The window is
 * {@code COUNT_BASED} over 10 with a 50% threshold, so <b>the breaker is most fragile when traffic is
 * LOWEST</b>. Post-close, five permanent 400s are half the window. The 2026-08-28 boot 403s were the
 * same shape at the other quiet end of the day — low volume, not high failure. Any future exemption
 * argument should reason about the quiet hours first.
 *
 * <p>⚠️ This does NOT hide the failure: the calls still fail, and {@link CircuitBreakerDiagnostics}
 * counts ignored errors separately. It stops a bad REQUEST being reported as a bad UPSTREAM. The
 * caller asking for an unserved segment is a separate, still-open question (NEW-14) — containment
 * here is not a fix there.
 *
 * <p>Mirrors {@link KiteBreakerIgnoresTokenForbiddenTest}, including its decision to interrogate the
 * BOUND config rather than grep the YAML: the ignore list is class names resolved at binding time,
 * a nested class needs the {@code $} form, and a wrong spelling fails SILENTLY — yielding a breaker
 * that quietly still counts 400s and looks exactly like a working fix until the next quiet hour.
 */
class KiteBreakerIgnoresBadRequestTest {

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

  /** The exact shape Kite returned on 2026-09-02. */
  private static Throwable invalidSegment() {
    return HttpClientErrorException.create(
        HttpStatus.BAD_REQUEST,
        "Bad Request",
        HttpHeaders.EMPTY,
        "{\"status\":\"error\",\"message\":\"invalid segment for continuous data\"}"
            .getBytes(StandardCharsets.UTF_8),
        StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("a permanent 400 is not counted against the shared Kite breaker")
  void aBadRequestIsNotCountedAgainstTheKiteBreaker() {
    runner.run(
        ctx -> {
          assertThat(invalidSegment()).isInstanceOf(HttpClientErrorException.BadRequest.class);
          assertThat(ignoreFor(ctx, "kite-rest").test(invalidSegment()))
              .as(
                  "a 400 says we asked for something that does not exist, never that Kite is down —"
                      + " five of them opened the SHARED breaker post-close on 2026-09-02")
              .isTrue();
        });
  }

  /**
   * The guard against over-reaching: a 5xx is exactly what a breaker is FOR, and an ignore list that
   * swallowed it would disable the protection this change must not touch.
   */
  @Test
  @DisplayName("a 5xx still counts — the breaker's actual purpose is untouched")
  void aServerErrorStillCountsAgainstTheKiteBreaker() {
    runner.run(
        ctx ->
            assertThat(
                    ignoreFor(ctx, "kite-rest")
                        .test(
                            HttpServerErrorException.create(
                                HttpStatus.INTERNAL_SERVER_ERROR,
                                "Server Error",
                                HttpHeaders.EMPTY,
                                new byte[0],
                                StandardCharsets.UTF_8)))
                .isFalse());
  }

  /**
   * ⚠️ A 404 is ALSO permanent, and is deliberately NOT exempted. Only the measured status is, for
   * the same reason the 403 change refused to generalise: an exemption widened on symmetry rather
   * than evidence is how breaker protection erodes one plausible status at a time.
   */
  @Test
  @DisplayName("404 is deliberately NOT exempted — only what was measured")
  void aNotFoundIsDeliberatelyStillCounted() {
    runner.run(
        ctx ->
            assertThat(
                    ignoreFor(ctx, "kite-rest")
                        .test(
                            HttpClientErrorException.create(
                                HttpStatus.NOT_FOUND,
                                "Not Found",
                                HttpHeaders.EMPTY,
                                new byte[0],
                                StandardCharsets.UTF_8)))
                .isFalse());
  }

  /**
   * ⚠️ Scoped to {@code kite-rest} only. OpenAlgo has its own breaker and nothing has been measured
   * there; widening on symmetry alone is how an unreviewed exemption spreads. Pinned so the
   * asymmetry is a decision on the record rather than an oversight.
   */
  @Test
  @DisplayName("the OpenAlgo breaker is deliberately not given the same exemption")
  void theOpenAlgoBreakerIsDeliberatelyNotGivenTheSameExemption() throws IOException {
    String yml = Files.readString(repoRoot().resolve(YML), StandardCharsets.UTF_8);
    int mentions = yml.split("HttpClientErrorException[$]BadRequest", -1).length - 1;
    assertThat(mentions)
        .as("exactly one breaker exempts BadRequest, and it is kite-rest")
        .isEqualTo(1);
  }

  private static Path repoRoot() {
    Path dir = Paths.get("").toAbsolutePath();
    while (dir != null && !Files.exists(dir.resolve(YML))) {
      dir = dir.getParent();
    }
    if (dir == null) {
      throw new IllegalStateException("could not locate the repo root from " + Paths.get("").toAbsolutePath());
    }
    return dir;
  }
}
