package in.arthayantra.strategysignal.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * Pins the two facts the evening-cron passthroughs rest on: that each env var actually <b>reaches
 * Spring</b>, and that compose's default is still <b>byte-identical to the code default</b> it
 * mirrors.
 *
 * <p>⚠️ Why this file exists. Two of these properties had no compose passthrough at all, so setting
 * one in {@code .env} was silently swallowed and the code default won with no error (#653) — "we set
 * the env var" and "the job did not move" were both true at once. Adding the passthroughs fixes
 * that, but it introduces a second, quieter failure: compose now carries a copy of every default,
 * and a copy drifts. A drifted copy does not error either — it just reschedules a job, in a file
 * nobody diffs against the source.
 *
 * <p>None of these property names appears in {@code application.yml}. They reach Spring ONLY
 * through {@code SystemEnvironmentPropertySource}'s relaxed dot/hyphen-to-underscore resolution
 * ({@code artha.bhavcopy-close.cron} ⇢ {@code ARTHA_BHAVCOPY_CLOSE_CRON}). Live state could never
 * have proven that works, because every pre-existing cron passthrough happens to carry a value
 * identical to its code default — so "the override works" and "the default wins" are observationally
 * identical in production. Hence a test rather than a probe.
 */
class CronPassthroughParityTest {

  /** A scheduled job: the property its {@code @Scheduled} reads, and the env var compose sets. */
  private record Job(String property, String envName, String sourceFile) {}

  private static final String SRC =
      "services/strategy-signal-service/src/main/java/in/arthayantra/strategysignal/";

  private static final List<Job> JOBS =
      List.of(
          new Job(
              "artha.heartbeat.swing-cron",
              "ARTHA_HEARTBEAT_SWING_CRON",
              SRC + "signals/SwingBatchHeartbeat.java"),
          new Job(
              "artha.graduation.promotion-cron",
              "ARTHA_GRADUATION_PROMOTION_CRON",
              SRC + "paper/GraduationPromotionScheduler.java"),
          // ⚠️ Money path, and the two that had no passthrough at all until 2026-08-11.
          new Job(
              "artha.paper.reconciliation.cron",
              "ARTHA_PAPER_RECONCILIATION_CRON",
              SRC + "paper/PaperReconciliationScheduler.java"),
          new Job(
              "artha.paper.past-expiry-recon.cron",
              "ARTHA_PAPER_PAST_EXPIRY_RECON_CRON",
              SRC + "paper/PaperScheduler.java"));

  @Test
  @DisplayName("each ARTHA_*_CRON name actually resolves its dotted property (relaxed binding)")
  void theEnvVarNamesResolveTheirProperties() {
    // ⚠️ The load-bearing assertion. If this transformation is not what we think it is, every one of
    // these passthroughs is inert and the #653 fix is a no-op that looks shipped.
    Map<String, Object> env = new HashMap<>();
    for (Job job : JOBS) {
      env.put(job.envName(), "RESOLVED:" + job.envName());
    }
    StandardEnvironment spring = new StandardEnvironment();
    spring
        .getPropertySources()
        .replace(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env));

    for (Job job : JOBS) {
      assertThat(spring.resolveRequiredPlaceholders("${" + job.property() + ":FELL_BACK}"))
          .as(
              "%s does not resolve from %s — the env var is silently swallowed and the code default"
                  + " wins with no error (#653). Check the dot/hyphen-to-underscore mapping",
              job.property(), job.envName())
          .isEqualTo("RESOLVED:" + job.envName());
    }
  }

  @Test
  @DisplayName("compose's default is byte-identical to the @Scheduled code default")
  void composeMirrorsTheCodeDefaultExactly() throws IOException {
    List<String> compose = composeLines();
    for (Job job : JOBS) {
      String inCode = codeDefault(job);
      String inCompose = composeDefault(compose, job.envName());
      assertThat(inCompose)
          .as(
              "%s drifted: compose says '%s', %s says '%s'. Compose carries a COPY of this default,"
                  + " and the copy is what production runs — a drift here silently reschedules the"
                  + " job with nothing to diff it against",
              job.envName(), inCompose, job.sourceFile(), inCode)
          .isEqualTo(inCode);
    }
  }

  @Test
  @DisplayName("the @Scheduled site still reads that property, in IST")
  void thePropertyStillExistsAtItsScheduledSite() throws IOException {
    for (Job job : JOBS) {
      String src = Files.readString(repoRoot().resolve(job.sourceFile()), StandardCharsets.UTF_8);
      // Anchored on `cron = "${prop:` so the property merely appearing in a comment cannot satisfy
      // it, and a rename reddens here rather than going quietly inert in production.
      int at = src.indexOf("cron = \"${" + job.property() + ":");
      assertThat(at)
          .as(
              "%s no longer reads ${%s} — the compose passthrough is now orphaned and inert",
              job.sourceFile(), job.property())
          .isGreaterThan(0);
      // ⚠️ Without `zone`, Spring schedules in the JVM's zone — UTC in our containers — so 19:30
      // would fire at 01:00 IST on a line that reads as if it were an evening job.
      assertThat(src.substring(at, Math.min(src.length(), at + 220)))
          .as("%s must schedule in IST", job.property())
          .contains("zone = \"Asia/Kolkata\"");
    }
  }

  @Test
  @DisplayName("compose passes every name through ON strategy-signal-service")
  void composeCarriesEveryPassthroughOnThisService() throws IOException {
    List<String> block = serviceBlock(composeLines(), "strategy-signal-service");
    for (Job job : JOBS) {
      assertThat(block)
          .as(
              "%s is missing from the strategy-signal-service environment block. Sitting under another"
                  + " service is indistinguishable from being absent — it is inert either way",
              job.envName())
          .anySatisfy(line -> assertThat(line.trim()).startsWith(job.envName() + ": "));
    }
  }

  /** The default baked into the job's own {@code @Scheduled} annotation. */
  private static String codeDefault(Job job) throws IOException {
    String src = Files.readString(repoRoot().resolve(job.sourceFile()), StandardCharsets.UTF_8);
    Matcher m =
        Pattern.compile(
                "cron = \"\\$\\{" + Pattern.quote(job.property()) + ":([^}]*)\\}\"")
            .matcher(src);
    if (!m.find()) {
      fail(
          job.sourceFile()
              + " has no `cron = \"${"
              + job.property()
              + ":<default>}\"` — this test must FAIL rather than skip, or it becomes a guard that"
              + " checks nothing");
    }
    return m.group(1);
  }

  /** The cron compose defaults {@code name} to, i.e. the value with no {@code .env} override. */
  private static String composeDefault(List<String> compose, String name) {
    Pattern p = Pattern.compile("^\\s*" + name + ": \"?\\$\\{" + name + ":?-([^}]*)}\"?\\s*$");
    for (String line : compose) {
      Matcher m = p.matcher(line);
      if (m.matches()) {
        return m.group(1);
      }
    }
    return fail(
        name + " has no `${" + name + ":-<cron>}` default in deploy/docker-compose.yml");
  }

  private static List<String> composeLines() throws IOException {
    return Files.readAllLines(
        repoRoot().resolve("deploy/docker-compose.yml"), StandardCharsets.UTF_8);
  }

  /** The named service's own YAML lines: from its 2-space key to the next key at that indent. */
  private static List<String> serviceBlock(List<String> compose, String service) {
    int from = compose.indexOf("  " + service + ":");
    if (from < 0) {
      fail("no '  " + service + ":' block in deploy/docker-compose.yml — did it get renamed?");
    }
    int to = compose.size();
    for (int i = from + 1; i < compose.size(); i++) {
      String line = compose.get(i);
      if (line.startsWith("  ") && !line.startsWith("   ") && line.trim().endsWith(":")) {
        to = i;
        break;
      }
    }
    return compose.subList(from + 1, to);
  }

  /** Walks up from the working directory to the repo root. FAILS rather than skipping if absent. */
  private static Path repoRoot() {
    Path dir = Paths.get("").toAbsolutePath();
    for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
      if (Files.exists(dir.resolve("deploy/docker-compose.yml"))) {
        return dir;
      }
    }
    return fail(
        "could not locate the repo root from "
            + Paths.get("").toAbsolutePath()
            + " — this test must FAIL rather than skip, or it becomes a guard that checks nothing");
  }
}
