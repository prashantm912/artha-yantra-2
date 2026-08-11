package in.arthayantra.marketdata.schedule;

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
 * Pins the owner's operating window: <b>every market-data evening job must fire before 19:00 IST</b>
 * (owner decision 2026-08-11 — the machine is shut down at 19:00 daily and started after 08:00, so a
 * job scheduled outside that window simply never runs).
 *
 * <p>⚠️ Why this file exists. The times live in {@code deploy/docker-compose.yml} as env-var
 * defaults, and <b>not one of these ten names appears in {@code application.yml}</b> — they reach
 * Spring only through {@code SystemEnvironmentPropertySource}'s relaxed name resolution
 * ({@code artha.bhavcopy-close.cron} ⇢ {@code ARTHA_BHAVCOPY_CLOSE_CRON}). That mechanism is
 * invisible: get a name wrong and the env var is silently swallowed, the code default wins, and the
 * job keeps running at its old post-19:00 time with <b>no error anywhere</b> (#653). Every existing
 * cron passthrough in compose happens to carry a value identical to its code default, so live state
 * could not have proven the mechanism works either — hence a test rather than a probe.
 *
 * <p>The four assertions are deliberately different questions: does the name resolve, does the
 * property still exist in the source, does compose still carry it on THIS service, and is the
 * resulting time actually inside the window. A rename breaks the second, a moved passthrough breaks
 * the third, and a well-meaning "put it back to 19:50" breaks the fourth.
 */
class EveningScheduleWindowTest {

  /** A scheduled evening job: where the cron is read, and what compose must set it to. */
  private record Job(String property, String envName, String sourceFile) {}

  /** The window the owner operates in. Inclusive start, EXCLUSIVE end. */
  private static final int WINDOW_START_HOUR = 18;
  private static final int WINDOW_END_HOUR = 19;

  private static final String SRC = "services/market-data-service/src/main/java/in/arthayantra/marketdata/";

  private static final List<Job> JOBS =
      List.of(
          new Job("artha.nse.eod-cron", "ARTHA_NSE_EOD_CRON", SRC + "nse/NseEodScheduler.java"),
          new Job(
              "artha.bhavcopy.eod-cron",
              "ARTHA_BHAVCOPY_EOD_CRON",
              SRC + "bhavcopy/BhavcopyBackfillService.java"),
          new Job(
              "artha.bhavcopy-close.cron",
              "ARTHA_BHAVCOPY_CLOSE_CRON",
              SRC + "canary/BhavcopyCloseCanary.java"),
          new Job(
              "artha.upstox.canary-cron",
              "ARTHA_UPSTOX_CANARY_CRON",
              SRC + "upstox/canary/UpstoxContractCanary.java"),
          new Job(
              "artha.data-quality.eod-cron",
              "ARTHA_DATA_QUALITY_EOD_CRON",
              SRC + "dataquality/DataQualityEodJob.java"),
          new Job(
              "artha.minervini.cron",
              "ARTHA_MINERVINI_CRON",
              SRC + "screener/minervini/MinerviniScheduler.java"),
          new Job(
              "artha.manas-arora.cron",
              "ARTHA_MANAS_ARORA_CRON",
              SRC + "screener/manas/ManasScheduler.java"),
          new Job(
              "artha.breadth.materialize-cron",
              "ARTHA_BREADTH_MATERIALIZE_CRON",
              SRC + "nse/analytics/EquityBreadthEodJob.java"),
          new Job(
              "artha.context.eod-cron",
              "ARTHA_CONTEXT_EOD_CRON",
              SRC + "context/MarketContextEodJob.java"),
          new Job(
              "artha.minervini.buyable-alerts.cron",
              "ARTHA_MINERVINI_BUYABLE_ALERTS_CRON",
              SRC + "screener/minervini/MinerviniBuyableProducer.java"));

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
    // ⚠️ The load-bearing assertion. NONE of these properties appears in application.yml, so the
    // ONLY thing connecting compose to Spring is SystemEnvironmentPropertySource's relaxed lookup.
    // If that transformation is not what we think it is, every one of these passthroughs is inert
    // and the schedule change is a no-op that looks shipped.
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
      // Anchored on `cron = "${prop:` so a property that merely appears in a comment cannot satisfy
      // it, and a rename reddens here rather than going quietly inert in production.
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
  @DisplayName("compose passes every name through ON market-data-service")
  void composeCarriesEveryPassthroughOnThisService() throws IOException {
    List<String> block = serviceBlock(composeLines(), "market-data-service");
    for (Job job : JOBS) {
      assertThat(block)
          .as(
              "%s is missing from the market-data-service environment block. Sitting under another"
                  + " service is indistinguishable from being absent — it is inert either way",
              job.envName())
          .anySatisfy(line -> assertThat(line.trim()).startsWith(job.envName() + ": "));
    }
  }

  private static List<String> composeLines() throws IOException {
    return Files.readAllLines(repoRoot().resolve("deploy/docker-compose.yml"), StandardCharsets.UTF_8);
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
