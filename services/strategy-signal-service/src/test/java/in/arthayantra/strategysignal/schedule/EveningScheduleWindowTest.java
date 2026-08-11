package in.arthayantra.strategysignal.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.scheduling.support.CronExpression;

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
              SRC + "insights/InsightSweeper.java"),
          // ⚠️ Money path. Both ran at 21:15/21:20, i.e. NOT AT ALL since the owner started closing
          // the machine at 19:00 — a silently dead reconciler, not a late one.
          new Job(
              "artha.paper.reconciliation.cron",
              "ARTHA_PAPER_RECONCILIATION_CRON",
              SRC + "paper/PaperReconciliationScheduler.java"),
          new Job(
              "artha.paper.past-expiry-recon.cron",
              "ARTHA_PAPER_PAST_EXPIRY_RECON_CRON",
              SRC + "paper/PaperScheduler.java"));

  /**
   * The morning catch-up is the ONLY path that recovers a session the evening chain missed (a late
   * NSE publish), so it gets its own window: it must still be firing when the owner actually starts
   * the machine, which they report is sometimes as late as 09:00.
   */
  private static final String CATCHUP_ENV = "ARTHA_SWING_CATCHUP_CRON";

  @Test
  @DisplayName("EVERY firing of every evening cron lands inside 18:00–19:00 IST")
  void everyEveningJobFiresBeforeTheOwnerClosesTheMachine() throws IOException {
    List<String> compose = composeLines();
    for (Job job : JOBS) {
      String cron = composeDefault(compose, job.envName());
      // ⚠️ EVERY firing, not just the first. Nothing here may poll (see the compose header for the
      // review that killed that), but an hour field like `18-19` reads as a wider safety net while
      // every 19:xx pass is in fact dead — the machine is off. Checking one firing would pass that
      // exact mistake, and it is the mistake a well-meaning "widen the net" edit makes.
      List<String> firings = firings(job.envName(), cron);
      assertThat(firings)
          .as("%s = '%s' produced no firing this test could read", job.envName(), cron)
          .isNotEmpty();
      for (String at : firings) {
        int hour = Integer.parseInt(at.substring(0, at.indexOf(':')));
        assertThat(hour)
            .as(
                "%s fires at %s IST (cron '%s'). The owner shuts the machine down at %d:00 and"
                    + " starts it after 08:00 (decision 2026-08-11), so anything at or after %d:00"
                    + " NEVER RUNS — it is not 'late', it is silently skipped every single day",
                job.envName(), at, cron, WINDOW_END_HOUR, WINDOW_END_HOUR)
            .isBetween(WINDOW_START_HOUR, WINDOW_END_HOUR - 1);
      }
    }
  }

  /**
   * Every {@code HH:mm} a second-precision Spring cron fires at, expanding {@code a,b} lists and
   * {@code a-b} ranges in the minute and hour fields. Deliberately REFUSES anything else (a step, a
   * wildcard hour) rather than guessing — a schedule this test cannot enumerate is a schedule it
   * cannot vouch for, and silently returning an empty list would turn the assertion above into a
   * guard that checks nothing.
   */
  private static List<String> firings(String envName, String cron) {
    String[] f = cron.trim().split("\\s+");
    if (f.length != 6) {
      fail(envName + " = '" + cron + "' is not a 6-field second-precision cron");
    }
    // ⚠️ Validate with Spring's own parser first (cross-vendor review Major). This used to read
    // fields 1 and 2 and ignore everything else, so `* 0 18 * * MON-FRI` — which fires every SECOND
    // of 18:00 — and a minute of `60`, which Spring rejects outright at boot, both sailed through as
    // "18:00, inside the window". A schedule this file vouches for must at least BE a schedule.
    if (!CronExpression.isValidExpression(cron)) {
      fail(envName + " = '" + cron + "' is not a valid Spring cron expression");
    }
    // Seconds are not a cadence knob here: every one of these jobs fires once, on the minute. A list
    // or range in seconds would multiply the firings the window and collision checks reason about.
    if (!"0".equals(f[0])) {
      fail(
          envName
              + " = '"
              + cron
              + "' has a seconds field of '"
              + f[0]
              + "'. These jobs fire once, on the minute — anything else changes what a 'firing'"
              + " means for the window and collision checks");
    }
    List<String> out = new ArrayList<>();
    for (int h : expand(envName, cron, "hour", f[2], 23)) {
      for (int m : expand(envName, cron, "minute", f[1], 59)) {
        out.add(String.format("%02d:%02d", h, m));
      }
    }
    return out;
  }

  private static List<Integer> expand(
      String envName, String cron, String field, String spec, int max) {
    List<Integer> out = new ArrayList<>();
    for (String part : spec.split(",")) {
      Matcher range = Pattern.compile("^(\\d{1,2})-(\\d{1,2})$").matcher(part);
      if (part.matches("\\d{1,2}")) {
        out.add(inRange(envName, cron, field, Integer.parseInt(part), max));
      } else if (range.matches()) {
        int from = inRange(envName, cron, field, Integer.parseInt(range.group(1)), max);
        int to = inRange(envName, cron, field, Integer.parseInt(range.group(2)), max);
        for (int v = from; v <= to; v++) {
          out.add(v);
        }
      } else {
        fail(
            envName
                + " = '"
                + cron
                + "' has a "
                + field
                + " field ('"
                + part
                + "') this test cannot enumerate. Widen it deliberately — never let an unreadable"
                + " schedule pass unchecked");
      }
    }
    return out;
  }

  private static int inRange(String envName, String cron, String field, int value, int max) {
    if (value > max) {
      fail(envName + " = '" + cron + "' has " + field + " " + value + ", above the maximum " + max);
    }
    return value;
  }

  @Test
  @DisplayName("the morning catch-up is still firing if the machine starts as late as 09:00")
  void theMorningCatchUpKeepsFiringUntilTheMarketOpens() throws IOException {
    List<String> firings = firings(CATCHUP_ENV, composeDefault(composeLines(), CATCHUP_ENV));

    // ⚠️ ONE assertion, over the INTERSECTION. Cross-vendor review Major: written as three separate
    // anySatisfy checks — "fires at/after 09:00", "fires before 09:15", "covers an on-time start" —
    // the schedule `0 59 8-9` passes all three while having NO usable late pass at all: 08:59
    // satisfies "before 09:15" and 09:59 satisfies "at/after 09:00", and its only post-09:00 firing
    // is refused by the deadline. Separate existential checks over the same set do not compose into
    // the conjunction they look like.
    //
    // What actually has to be true: a pass in [09:00, 09:15) — late enough to catch a 09:00 start,
    // early enough that SwingBatchCatchUp's own market-open deadline (SwingBatchCatchUp:610) still
    // lets it do work. A session the evening chain missed is recovered ONLY here.
    assertThat(firings)
        .as(
            "%s has no firing inside [09:00, 09:15). Later passes are refused by the market-open"
                + " deadline and earlier ones happen before the machine is up, so a morning the"
                + " owner starts at 09:00 loses the whole session with no second chance",
            CATCHUP_ENV)
        .anySatisfy(at -> assertThat(at).isBetween("09:00", "09:14"));

    // ...and it must still cover the normal on-time start, which is the common case.
    assertThat(firings)
        .as("%s must also cover a normal start, before 09:00", CATCHUP_ENV)
        .anySatisfy(at -> assertThat(at).isBetween("08:00", "08:59"));
  }

  @Test
  @DisplayName("the morning catch-up is wired like every other job — name, source, IST, service")
  void theMorningCatchUpIsWiredLikeTheRest() throws IOException {
    // Cross-vendor review Major: CATCHUP_ENV was pinned for its FIRING TIMES and nothing else, so
    // the property could be renamed, the passthrough could move to another service, or the zone
    // could be dropped, and only the evening jobs would notice. Same four checks, same reasons.
    Job catchUp =
        new Job("artha.swing.catchup-cron", CATCHUP_ENV, SRC + "swing/SwingBatchCatchUp.java");

    StandardEnvironment spring = new StandardEnvironment();
    spring
        .getPropertySources()
        .replace(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                Map.of(catchUp.envName(), "RESOLVED")));
    assertThat(spring.resolveRequiredPlaceholders("${" + catchUp.property() + ":FELL_BACK}"))
        .as("%s does not resolve from %s (#653)", catchUp.property(), catchUp.envName())
        .isEqualTo("RESOLVED");

    String src = Files.readString(repoRoot().resolve(catchUp.sourceFile()), StandardCharsets.UTF_8);
    int at = src.indexOf("cron = \"${" + catchUp.property() + ":");
    assertThat(at)
        .as("%s no longer reads ${%s}", catchUp.sourceFile(), catchUp.property())
        .isGreaterThan(0);
    assertThat(src.substring(at, Math.min(src.length(), at + 220)))
        .as("the catch-up must schedule in IST, or 09:00 means nothing")
        .contains("zone = \"Asia/Kolkata\"");

    assertThat(serviceBlock(composeLines(), "strategy-signal-service"))
        .as("%s is missing from the strategy-signal-service environment block", catchUp.envName())
        .anySatisfy(line -> assertThat(line.trim()).startsWith(catchUp.envName() + ": "));
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
  @DisplayName("application.yml reads the same env names for the crons it declares")
  void applicationYmlDefersToTheSameEnvNames() throws IOException {
    List<String> yml =
        Files.readAllLines(
            repoRoot()
                .resolve("services/strategy-signal-service/src/main/resources/application.yml"),
            StandardCharsets.UTF_8);
    int checked = 0;
    for (Job job : JOBS) {
      // ⚠️ Resolve the FULL dotted path through the YAML nesting, never the leaf key alone. Three of
      // these properties end in `.cron`, and a bare `cron: ` search matches whichever unrelated
      // `cron:` line appears first — measured: it demanded that some other job's line read
      // ARTHA_PAPER_RECONCILIATION_CRON and failed. Naive key matching turns this guard into noise.
      String declared = valueAtPath(yml, job.property());
      if (declared == null) {
        continue; // not declared in YAML — the relaxed-binding test above is what covers it
      }
      checked++;
      assertThat(declared)
          .as(
              "application.yml declares %s but reads a different env var than compose sets, so"
                  + " compose's value is swallowed and the YAML default wins (#653)",
              job.property())
          .startsWith("${" + job.envName() + ":");
    }
    // ⚠️ Without this the whole test passes by enumerating NOTHING — a YAML restructure that moved
    // every key would read as "all assertions passed". Two are declared today.
    assertThat(checked)
        .as(
            "no cron in this list was found in application.yml at all — the path walk is broken, or"
                + " the file was restructured. A guard that checks zero items must fail, not pass")
        .isGreaterThanOrEqualTo(2);
  }

  /**
   * The scalar at a dotted YAML path, or {@code null} if the path is absent. Indentation-based and
   * deliberately simple — enough for {@code artha.insights.sell-decision-cron}, and it refuses to
   * guess anywhere else.
   */
  private static String valueAtPath(List<String> yml, String property) {
    String[] segments = property.split("\\.");
    int depth = 0;
    int expectedIndent = 0;
    for (String line : yml) {
      if (line.isBlank() || line.trim().startsWith("#")) {
        continue;
      }
      int indent = line.length() - line.stripLeading().length();
      if (indent < expectedIndent) {
        // Left the subtree we were descending; restart the match from the top.
        depth = 0;
        expectedIndent = 0;
      }
      if (indent != expectedIndent) {
        continue;
      }
      String trimmed = line.trim();
      String want = segments[depth] + ":";
      if (!trimmed.equals(want) && !trimmed.startsWith(want + " ")) {
        continue;
      }
      if (depth == segments.length - 1) {
        String value = trimmed.substring(want.length()).trim();
        return value.replaceAll("^\"|\"$", "");
      }
      depth++;
      expectedIndent = indent + 2;
    }
    return null;
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
