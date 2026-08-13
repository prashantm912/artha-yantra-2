package in.arthayantra.marketdata.canary;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the #653 trap for {@link BhavcopyCloseCanary}'s coverage floor: a {@code @Value} default
 * with no compose passthrough is pinned to that default forever, and a {@code .env} edit silently
 * does nothing — no error, no log line, the knob simply is not tunable.
 *
 * <p>⚠️ <b>There is no GENERAL guard for this.</b> {@code CronPassthroughParityTest} covers only
 * the ten {@code *.cron} properties, and the two non-cron precedents ({@code
 * CorporateActionKnobPassthroughTest}, {@code PlaneDivergenceKnobPassthroughTest}) are each
 * bespoke to their own feature. Nothing sweeps the codebase for {@code @Value} knobs lacking a
 * passthrough, so a NEW knob is covered only if someone writes a test like this one. That is why
 * this file exists rather than an assumption that an existing gate would catch the drift.
 *
 * <p>⚠️ <b>Scope, deliberately narrow.</b> {@code min-compared} is the knob this PR introduces and
 * therefore owns. Its five siblings — {@code enabled}, {@code alerts-enabled}, {@code threshold},
 * {@code red-floor}, {@code sample-limit} — have no passthrough either and are equally un-tunable
 * today. That is PRE-EXISTING and is reported rather than fixed here: adding five more compose
 * lines is five more chances to mistype a default and silently move live behaviour, which is the
 * very trap being closed. Widening the list below is the right follow-up, in its own change.
 *
 * <p>Relaxed binding is what connects the two sides, so the assertion is on the exact translated
 * name: {@code artha.bhavcopy-close.min-compared} ⇄ {@code ARTHA_BHAVCOPY_CLOSE_MIN_COMPARED}.
 * Both sides are derived here rather than written twice, so renaming the property without renaming
 * the passthrough fails this test.
 */
class BhavcopyCloseKnobPassthroughTest {

  private static final String PREFIX = "artha.bhavcopy-close.";
  private static final List<String> KNOBS = List.of("min-compared");

  /**
   * ⚠️ RATCHET. Every assertion below iterates {@link #KNOBS}, so emptying it makes this file
   * execute zero assertions and pass — the guard-that-checks-nothing shape (catalogue trap #14),
   * which is the same class of defect this whole PR is about.
   */
  private static final int EXPECTED_KNOB_COUNT = 1;

  @Test
  void theKnobListIsNotEmpty() {
    assertThat(KNOBS).hasSize(EXPECTED_KNOB_COUNT);
  }

  @Test
  void everyKnobHasAMarketDataComposePassthrough() throws IOException {
    String compose = Files.readString(repoRoot().resolve("deploy/docker-compose.yml"));

    for (String knob : KNOBS) {
      String env = envName(PREFIX + knob);
      assertThat(compose)
          .as("compose passthrough for %s (#653: no passthrough = pinned to the @Value default)",
              PREFIX + knob)
          .contains(env + ": \"${" + env + ":-");
    }
  }

  @Test
  void everyKnobIsDocumentedInEnvExample() throws IOException {
    String envExample = Files.readString(repoRoot().resolve(".env.example"));

    for (String knob : KNOBS) {
      assertThat(envExample).contains(envName(PREFIX + knob));
    }
  }

  /**
   * The compose default must byte-match the code default, or a deploy silently moves live
   * behaviour — the passthrough would then be a CHANGE wearing a no-op's clothes.
   */
  @Test
  void theComposeDefaultMatchesTheCodeDefault() throws IOException {
    String compose = Files.readString(repoRoot().resolve("deploy/docker-compose.yml"));
    String source =
        Files.readString(
            repoRoot()
                .resolve("services/market-data-service/src/main/java/in/arthayantra/marketdata")
                .resolve("canary/BhavcopyCloseCanary.java"));

    assertThat(compose).contains("ARTHA_BHAVCOPY_CLOSE_MIN_COMPARED: \"${"
        + "ARTHA_BHAVCOPY_CLOSE_MIN_COMPARED:-100}\"");
    assertThat(source).contains("${artha.bhavcopy-close.min-compared:100}");
  }

  /** Spring's {@code SystemEnvironmentPropertySource} translation, applied in the same direction. */
  private static String envName(String property) {
    return property.toUpperCase(java.util.Locale.ROOT).replace('.', '_').replace('-', '_');
  }

  private static Path repoRoot() {
    Path p = Path.of("").toAbsolutePath();
    while (p != null && !Files.exists(p.resolve("deploy/docker-compose.yml"))) {
      p = p.getParent();
    }
    if (p == null) {
      throw new IllegalStateException("repo root not found from " + Path.of("").toAbsolutePath());
    }
    return p;
  }
}
