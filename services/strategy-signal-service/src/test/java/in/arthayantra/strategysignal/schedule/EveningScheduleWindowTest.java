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
 * The strategy-signal half of the owner's operating window: these four jobs read market-data's
 * evening output, so they run last — but still before 19:00 IST, when the owner shuts the machine
 * down (decision 2026-08-11). See {@code EveningScheduleWindowTest} in market-data-service for the
 * full rationale; the failure mode is identical and the two must not drift apart.
 *
 * <p>Two of these four ({@code artha.insights.*}) are wired explicitly in {@code application.yml};
 * the other two reach Spring only through {@code SystemEnvironmentPropertySource}'s relaxed name
 * resolution. Both paths are asserted the same way, because the difference is invisible at the call
 * site and a future edit could move a name between them.
 */
class EveningScheduleWindowTest {

  /** A scheduled evening job: where the cron is read, and what compose must set it to. */
  private record Job(String property, String envName, String sourceFile) {}

  /** The window the owner operates in. Inclusive start, EXCLUSIVE end. */
  private static final int WINDOW_START_HOUR = 18;
  private static final int WINDOW_END_HOUR = 19;

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
          new Job(
              "artha.insights.strategy-evidence-cron",
              "ARTHA_INSIGHTS_STRATEGY_EVIDENCE_CRON",
              SRC + "insights/InsightSweeper.java"),
          new Job(
              "artha.insights.sell-decision-cron",
              "ARTHA_INSIGHTS_SELL_DECISION_CRON",
              SRC + "insights/InsightSweeper.java"));

  @Test
  @DisplayName("every evening cron compose sets lands inside 18:00–19:00 IST")
  void everyEveningJobFiresBeforeTheOwnerClosesTheMachine() throws IOException {
    List<String> compose = composeLines();
    for (Job job : JOBS) {
      String cron = composeDefault(compose, job.envName());
      Matcher m = Pattern.compile("^0 (\\d{1,2}) (\\d{1,2}) ").matcher(cron);
      assertThat(m.find())
          .as("%s = '%s' is not a second-precision cron this test can read", job.envName(), cron)
          .isTrue();
      int hour = Integer.parseInt(m.group(2));
      assertThat(hour)
          .as(
              "%s fires at %02d:%02d IST. The owner shuts the machine down at %d:00 and starts it"
                  + " after 08:00 (decision 2026-08-11), so anything at or after %d:00 NEVER RUNS —"
                  + " it is not 'late', it is silently skipped every single day",
              job.envName(),
              hour,
              Integer.parseInt(m.group(1)),
              WINDOW_END_HOUR,
              WINDOW_END_HOUR)
          .isBetween(WINDOW_START_HOUR, WINDOW_END_HOUR - 1);
    }
  }

  @Test
  @DisplayName("each ARTHA_*_CRON name actually resolves its dotted property (relaxed binding)")
  void theEnvVarNamesResolveTheirProperties() {
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
  @DisplayName("the @Scheduled site still reads that property, in IST")
  void thePropertyStillExistsAtItsScheduledSite() throws IOException {
    for (Job job : JOBS) {
      String src = Files.readString(repoRoot().resolve(job.sourceFile()), StandardCharsets.UTF_8);
      int at = src.indexOf("cron = \"${" + job.property() + ":");
      assertThat(at)
          .as(
              "%s no longer reads ${%s} — the compose passthrough is now orphaned and inert",
              job.sourceFile(), job.property())
          .isGreaterThan(0);
      // ⚠️ Without `zone`, Spring schedules in the JVM's zone (UTC in our containers) and 18:00 UTC
      // is 23:30 IST — outside the window, on a line that reads as if it were inside it.
      assertThat(src.substring(at, Math.min(src.length(), at + 220)))
          .as("%s must schedule in IST or the hour above means nothing", job.property())
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
              "%s is missing from the strategy-signal-service environment block. Sitting under"
                  + " another service is indistinguishable from being absent — it is inert either"
                  + " way",
              job.envName())
          .anySatisfy(line -> assertThat(line.trim()).startsWith(job.envName() + ": "));
    }
  }

  /**
   * The two {@code artha.insights.*} crons ARE declared in application.yml, so that file's default
   * must not contradict compose — a stale YAML default is what an operator reads when the env var is
   * absent (a bare {@code docker run}, a test profile), and it currently says 21:xx.
   */
  @Test
  @DisplayName("application.yml reads the same env names for the insights crons")
  void applicationYmlDefersToTheSameEnvNames() throws IOException {
    String yml =
        Files.readString(
            repoRoot()
                .resolve("services/strategy-signal-service/src/main/resources/application.yml"),
            StandardCharsets.UTF_8);
    for (Job job : JOBS) {
      String key = job.property().substring(job.property().lastIndexOf('.') + 1);
      if (!yml.contains(key + ": ")) {
        continue; // not declared in YAML — covered by the relaxed-binding test above
      }
      assertThat(yml)
          .as(
              "application.yml declares %s but reads a different env var than compose sets, so"
                  + " compose's value is swallowed (#653)",
              key)
          .contains(key + ": ${" + job.envName() + ":");
    }
  }

  private static List<String> composeLines() throws IOException {
    return Files.readAllLines(
        repoRoot().resolve("deploy/docker-compose.yml"), StandardCharsets.UTF_8);
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
        name
            + " has no `${"
            + name
            + ":-<cron>}` default in deploy/docker-compose.yml — this test must FAIL rather than"
            + " skip, or it becomes a guard that checks nothing");
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
