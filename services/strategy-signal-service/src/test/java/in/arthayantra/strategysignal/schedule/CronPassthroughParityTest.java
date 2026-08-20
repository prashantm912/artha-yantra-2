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

/**
 * Pins the chain every evening cron travels: {@code @Scheduled} default ⇠ {@code application.yml}
 * ⇠ compose {@code environment} ⇠ {@code .env}. Each link is silent when it breaks.
 *
 * <p>⚠️ Why this file exists. TWO of these four — both paper reconcilers, both money path — had no
 * compose passthrough at all, so setting either in {@code .env} was swallowed and the code default
 * won with no error (#653). Adding the passthroughs fixes that and introduces a quieter version of
 * the same problem: compose now carries a COPY of every default, and copies drift.
 *
 * <p>Unlike market-data, NONE of these four is declared in this service's {@code application.yml},
 * so there is no literal here for compose to shadow — asserted below rather than assumed.
 *
 * <p>The properties reach Spring through {@code SystemEnvironmentPropertySource}'s relaxed
 * dot/hyphen-to-underscore resolution ({@code artha.bhavcopy-close.cron} ⇢ {@code
 * ARTHA_BHAVCOPY_CLOSE_CRON}). Live state could never have proven that works: every pre-existing
 * cron passthrough happens to carry a value identical to its code default, so "the override works"
 * and "the default wins" are observationally identical in production.
 */
class CronPassthroughParityTest {

  /** A scheduled job: the property its {@code @Scheduled} reads, and the env var compose sets. */
  private record Job(String property, String envName, String sourceFile) {}

  private static final String SERVICE = "strategy-signal-service";
  private static final String SRC = "services/" + SERVICE + "/src/main/java/in/arthayantra/strategysignal/";
  private static final String YML = "services/" + SERVICE + "/src/main/resources/application.yml";

  /**
   * ⚠️ RATCHET. Every assertion below iterates {@link #JOBS}, so emptying or trimming this list makes
   * the whole file execute zero assertions and pass — the guard-that-checks-nothing shape. Lowering
   * this number is a deliberate act that must be justified in the PR that does it.
   */
  private static final int EXPECTED_JOB_COUNT = 8;

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
          // Money path, and the two that had no passthrough at all until 2026-08-11.
          new Job(
              "artha.paper.reconciliation.cron",
              "ARTHA_PAPER_RECONCILIATION_CRON",
              SRC + "paper/PaperReconciliationScheduler.java"),
          new Job(
              "artha.paper.past-expiry-recon.cron",
              "ARTHA_PAPER_PAST_EXPIRY_RECON_CRON",
              SRC + "paper/PaperScheduler.java"),
          // Added 2026-08-12. These two reached Spring through application.yml with NO compose
          // passthrough, which made them the one gap in OperatingWindowTest's stated premise that
          // parity covers every schedule. Both sides now exist, so the premise is true rather than
          // nearly true. Note both @Scheduled sites live in the SAME file — the parity assertions
          // key on the PROPERTY, not the file, so that is fine.
          new Job(
              "artha.insights.strategy-evidence-cron",
              "ARTHA_INSIGHTS_STRATEGY_EVIDENCE_CRON",
              SRC + "insights/InsightSweeper.java"),
          new Job(
              "artha.insights.sell-decision-cron",
              "ARTHA_INSIGHTS_SELL_DECISION_CRON",
              SRC + "insights/InsightSweeper.java"),
          // The two swing settles. Both have carried a compose passthrough since #623 and neither
          // was ever pinned here, so the compose copy and the annotation default were free to drift
          // — which is precisely what this file exists to stop. Added 2026-08-19 with ledger H27,
          // the PR that moves both crons and therefore has to touch both copies.
          new Job(
              "artha.minervini.swing.cron",
              "ARTHA_MINERVINI_SWING_CRON",
              SRC + "minervini/MinerviniSwingScheduler.java"),
          new Job(
              "artha.manas-arora.swing.cron",
              "ARTHA_MANAS_ARORA_SWING_CRON",
              SRC + "manas/ManasAroraSwingScheduler.java"));

  @Test
  @DisplayName("the catalogue still covers every job it claims to")
  void theJobCatalogueIsNotSilentlyShrunk() {
    assertThat(JOBS)
        .as(
            "every other assertion in this file iterates JOBS, so trimming it makes them all pass"
                + " while checking nothing")
        .hasSize(EXPECTED_JOB_COUNT);
    assertThat(JOBS.stream().map(Job::envName).distinct().count())
        .as("two jobs share an env var name — one of them is not actually pinned")
        .isEqualTo(EXPECTED_JOB_COUNT);
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
  @DisplayName("compose's environment default is byte-identical to the @Scheduled code default")
  void composeMirrorsTheCodeDefaultExactly() throws IOException {
    Map<String, String> environment = serviceEnvironment(SERVICE);
    for (Job job : JOBS) {
      String inCode = codeDefault(job);
      String inCompose = composeDefault(environment, job.envName());
      assertThat(inCompose)
          .as(
              "%s drifted: compose says '%s', %s says '%s'. Compose carries a COPY of this default"
                  + " and the copy is what production runs — a drift here silently reschedules the"
                  + " job with nothing to diff it against",
              job.envName(), inCompose, job.sourceFile(), inCode)
          .isEqualTo(inCode);
    }
  }

  @Test
  @DisplayName("where application.yml declares one it defers to the env var, with the same default")
  void applicationYmlDefersToTheEnvVar() throws IOException {
    // ⚠️ On market-data the same test found three properties carrying LITERAL cron values that the
    // new compose entries then shadowed — #653 in reverse, invisible because the values matched.
    //
    // It used to demand the yml declare NONE of these, which was true only while no job here had a
    // yml line. The two insight crons do, in the CORRECT deferring form, so the blanket rule failed
    // them for being right. Emptiness was never the invariant — "compose cannot shadow a literal"
    // is, and a ${ENV:default} line cannot be shadowed. What it CAN do is carry a third copy of the
    // default that drifts, so that is what is asserted instead.
    List<String> yml = Files.readAllLines(repoRoot().resolve(YML), StandardCharsets.UTF_8);
    List<String> literals = new ArrayList<>();
    int deferring = 0;
    for (Job job : JOBS) {
      String declared = valueAtPath(yml, job.property());
      if (declared == null) {
        continue;
      }
      String expected = "${" + job.envName() + ":" + codeDefault(job) + "}";
      if (!declared.startsWith("${")) {
        literals.add(job.property() + " = " + declared);
        continue;
      }
      deferring++;
      assertThat(declared)
          .as(
              "%s's application.yml line is a THIRD copy of this schedule (code, compose, yml). It"
                  + " must name the same env var and carry the same default, or the copy nobody"
                  + " reads becomes the one that is wrong",
              job.property())
          .isEqualTo(expected);
    }
    assertThat(literals)
        .as("a LITERAL cron in application.yml is silently shadowed by compose — use ${ENV:default}")
        .isEmpty();
    assertThat(deferring)
        .as("this test asserted nothing — no catalogued job has an application.yml line any more")
        .isEqualTo(2);
  }

  @Test
  @DisplayName("exactly one ACTIVE @Scheduled site reads each property, in IST")
  void onePropertyOneActiveScheduledSite() throws IOException {
    for (Job job : JOBS) {
      List<String> sites = activeCronSites(job);
      assertThat(sites)
          .as(
              "%s must have exactly ONE active @Scheduled reading ${%s} — zero means the compose"
                  + " passthrough is orphaned and inert, more than one means the schedule is"
                  + " ambiguous",
              job.sourceFile(), job.property())
          .hasSize(1);
      // Without `zone`, Spring schedules in the JVM's zone — UTC in our containers — so 19:30 fires
      // at 01:00 IST on a line that reads like an evening job.
      assertThat(sites.get(0))
          .as("%s must schedule in IST", job.property())
          .contains("zone = \"Asia/Kolkata\"");
    }
  }

  @Test
  @DisplayName("every name is passed through under this service's environment: block")
  void composeCarriesEveryPassthroughInTheEnvironmentBlock() throws IOException {
    // ⚠️ Structural, not "somewhere in the service block" (cross-vendor review Major, raised twice).
    // A correct-looking cron line under that service's `labels:` or `build.args:` satisfies a
    // block-wide search while setting no environment variable at all.
    Map<String, String> environment = serviceEnvironment(SERVICE);
    for (Job job : JOBS) {
      assertThat(environment)
          .as(
              "%s is not under services.%s.environment. Sitting elsewhere in the file — another"
                  + " service, or this service's labels/build.args — is indistinguishable from being"
                  + " absent: the container never sees it",
              job.envName(), SERVICE)
          .containsKey(job.envName());
    }
  }

  /** The default compose gives {@code name}, i.e. its value with no {@code .env} override. */
  private static String composeDefault(Map<String, String> environment, String name) {
    String raw = environment.get(name);
    if (raw == null) {
      return fail(name + " is not under services." + SERVICE + ".environment");
    }
    // ⚠️ Literal `:-`, never `-` (cross-vendor review Major). `${NAME-default}` substitutes only
    // when the variable is UNSET, so an explicitly blank one reaches Spring as an empty cron and
    // fails the boot; `${NAME:-default}` also covers blank. They are different contracts and the
    // old `:?-` regex accepted either, so a silent switch between them would have gone unnoticed.
    Matcher m = Pattern.compile("^\"?\\$\\{" + name + ":-(.*?)}\"?$").matcher(raw.trim());
    if (!m.matches()) {
      return fail(
          name + " must be written exactly `${" + name + ":-<cron>}` in compose, but reads: " + raw);
    }
    return m.group(1);
  }

  /** Every {@code KEY: value} under {@code services.<service>.environment}, in file order. */
  private static Map<String, String> serviceEnvironment(String service) throws IOException {
    List<String> compose =
        Files.readAllLines(repoRoot().resolve("deploy/docker-compose.yml"), StandardCharsets.UTF_8);
    int from = compose.indexOf("  " + service + ":");
    if (from < 0) {
      fail("no '  " + service + ":' block in deploy/docker-compose.yml — did it get renamed?");
    }
    Map<String, String> out = new HashMap<>();
    boolean inEnvironment = false;
    for (int i = from + 1; i < compose.size(); i++) {
      String line = compose.get(i);
      if (line.isBlank() || line.trim().startsWith("#")) {
        continue;
      }
      int indent = line.length() - line.stripLeading().length();
      if (indent <= 2) {
        break; // next service
      }
      if (indent == 4) {
        inEnvironment = "environment:".equals(line.trim());
        continue;
      }
      if (inEnvironment && indent == 6) {
        int colon = line.indexOf(':');
        if (colon > 0) {
          String key = line.substring(0, colon).trim();
          String previous = out.put(key, line.substring(colon + 1).trim());
          if (previous != null) {
            fail(key + " appears twice under services." + service + ".environment");
          }
        }
      }
    }
    return out;
  }

  /**
   * Every ACTIVE {@code @Scheduled} line reading this job's property, with comments removed first.
   *
   * <p>⚠️ ONE routine, used by both the site check and {@link #codeDefault} (cross-vendor review
   * Major, twice). A per-line filter misses a BLOCK comment — the annotation line inside
   * a block comment starts with neither a double-slash nor an asterisk — and a raw
   * {@code indexOf} over the whole file takes the FIRST occurrence, so a commented-out OLD default
   * above an active new one silently becomes the value compose is compared against.
   */
  private static List<String> activeCronSites(Job job) throws IOException {
    // ⚠️ Searches the JOINED uncommented source, not line by line. Several of these annotations wrap
    // across lines — PaperReconciliationScheduler and PaperScheduler put `zone` and `scheduler` on
    // the lines AFTER `cron` — and a per-line slice ends before `zone`, so the IST assertion below
    // fails on a job that is correctly zoned. Found 2026-08-12 by adding the two insight jobs; the
    // identical bug had already been found and fixed in OperatingWindowTest, which is the tell that
    // it is the shape of the extraction rather than any one call site.
    String source =
        String.join(
            System.lineSeparator(),
            uncommentedLines(
                Files.readString(repoRoot().resolve(job.sourceFile()), StandardCharsets.UTF_8)));
    String needle = "cron = \"${" + job.property() + ":";
    List<String> sites = new ArrayList<>();
    int at = source.indexOf(needle);
    while (at >= 0) {
      int close = source.indexOf(')', at);
      sites.add(source.substring(at, close < 0 ? source.length() : close + 1).trim());
      at = source.indexOf(needle, at + needle.length());
    }
    return sites;
  }

  /**
   * The source lines that are NOT inside a comment, in file order.
   *
   * <p>Tracks block-comment state across lines rather than filtering each line on its own: the
   * annotation line inside a block comment begins with neither a double-slash nor an asterisk, so a
   * per-line filter waves it through as active code.
   */
  private static List<String> uncommentedLines(String source) {
    String blockOpen = "/*";
    String blockClose = "*/";
    String lineComment = "//";
    List<String> out = new ArrayList<>();
    boolean inBlock = false;
    for (String line : source.lines().toList()) {
      String working = line;
      if (inBlock) {
        int close = working.indexOf(blockClose);
        if (close < 0) {
          continue;
        }
        working = working.substring(close + blockClose.length());
        inBlock = false;
      }
      int open = working.indexOf(blockOpen);
      while (open >= 0) {
        int close = working.indexOf(blockClose, open + blockOpen.length());
        if (close < 0) {
          working = working.substring(0, open);
          inBlock = true;
          break;
        }
        working = working.substring(0, open) + working.substring(close + blockClose.length());
        open = working.indexOf(blockOpen);
      }
      int slashes = working.indexOf(lineComment);
      if (slashes >= 0) {
        working = working.substring(0, slashes);
      }
      out.add(working);
    }
    return out;
  }

  /** The default baked into the job's own ACTIVE {@code @Scheduled} annotation. */
  private static String codeDefault(Job job) throws IOException {
    List<String> sites = activeCronSites(job);
    if (sites.size() != 1) {
      return fail(
          job.sourceFile()
              + " has "
              + sites.size()
              + " active @Scheduled sites reading ${"
              + job.property()
              + "} — expected exactly one");
    }
    Matcher m =
        Pattern.compile("cron = \"\\$\\{" + Pattern.quote(job.property()) + ":([^}]*)\\}\"")
            .matcher(sites.get(0));
    if (!m.find()) {
      fail(job.sourceFile() + " has no `cron = \"${" + job.property() + ":<default>}\"`");
    }
    return m.group(1);
  }

  /**
   * The scalar at a dotted YAML path, or {@code null} if absent. Indentation-based and deliberately
   * simple — enough for {@code artha.nse.eod-cron}, and it refuses to guess anywhere else.
   *
   * <p>Resolves the FULL path, never the leaf key: several of these properties end in {@code .cron},
   * and a bare {@code cron: } search matches whichever unrelated line comes first.
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
        return trimmed.substring(want.length()).trim().replaceAll("^\"|\"$", "");
      }
      depth++;
      expectedIndent = indent + 2;
    }
    return null;
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
