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
import org.springframework.scheduling.support.CronExpression;

/**
 * The owner's operating window, across BOTH services: the machine is shut down at 19:00 IST daily
 * and started after 08:00, so a job scheduled outside 08:00–19:00 does not run late — it does not
 * run at all, silently, on every day the machine goes off on time.
 *
 * <p>⚠️ Reads the default out of the {@code @Scheduled} ANNOTATION, not out of compose. Two of these
 * sixteen ({@code artha.insights.*}) have no compose passthrough at all — they reach Spring through
 * {@code application.yml} instead — so a compose-driven window check would silently skip 18:56 and
 * 18:57 and report a clean sweep. That is the guard-with-a-blind-spot shape, and enumerating the
 * schedule before writing this test is what surfaced it. {@code CronPassthroughParityTest} already
 * proves the code default, the compose default and the YAML default agree byte for byte, so reading
 * the annotation covers every job uniformly however its override is wired.
 */
class OperatingWindowTest {

  /** Inclusive start, EXCLUSIVE end — the hours the machine is actually up. */
  private static final int WINDOW_START_HOUR = 8;
  private static final int WINDOW_END_HOUR = 19;

  private static final String MD =
      "services/market-data-service/src/main/java/in/arthayantra/marketdata/";
  private static final String SS =
      "services/strategy-signal-service/src/main/java/in/arthayantra/strategysignal/";

  /** Every scheduled job that must land inside the owner's window, and where its cron is declared. */
  private static final Map<String, String> JOBS = new HashMap<>();

  static {
    JOBS.put("artha.upstox.canary-cron", MD + "upstox/canary/UpstoxContractCanary.java");
    JOBS.put("artha.bhavcopy.eod-cron", MD + "bhavcopy/BhavcopyBackfillService.java");
    JOBS.put("artha.nse.eod-cron", MD + "nse/NseEodScheduler.java");
    JOBS.put("artha.minervini.cron", MD + "screener/minervini/MinerviniScheduler.java");
    JOBS.put("artha.manas-arora.cron", MD + "screener/manas/ManasScheduler.java");
    JOBS.put("artha.context.eod-cron", MD + "context/MarketContextEodJob.java");
    JOBS.put("artha.data-quality.eod-cron", MD + "dataquality/DataQualityEodJob.java");
    JOBS.put("artha.breadth.materialize-cron", MD + "nse/analytics/EquityBreadthEodJob.java");
    JOBS.put("artha.bhavcopy-close.cron", MD + "canary/BhavcopyCloseCanary.java");
    JOBS.put(
        "artha.minervini.buyable-alerts.cron", MD + "screener/minervini/MinerviniBuyableProducer.java");
    JOBS.put("artha.heartbeat.swing-cron", SS + "signals/SwingBatchHeartbeat.java");
    JOBS.put("artha.graduation.promotion-cron", SS + "paper/GraduationPromotionScheduler.java");
    // ⚠️ No compose passthrough — application.yml only. The reason this test reads annotations.
    JOBS.put("artha.insights.strategy-evidence-cron", SS + "insights/InsightSweeper.java");
    JOBS.put("artha.insights.sell-decision-cron", SS + "insights/InsightSweeper.java");
    // Money path, moved to MORNING (owner decision 2026-08-12) so the evening tail is not squeezed
    // against the shutdown boundary. They must follow the 08:35 entry pass; see the ordering test.
    JOBS.put("artha.paper.reconciliation.cron", SS + "paper/PaperReconciliationScheduler.java");
    JOBS.put("artha.paper.past-expiry-recon.cron", SS + "paper/PaperScheduler.java");
  }

  private static final int EXPECTED_JOB_COUNT = 16;

  @Test
  @DisplayName("the catalogue is not silently shrunk")
  void theCatalogueCoversEveryJobItClaimsTo() {
    assertThat(JOBS)
        .as("every assertion here iterates JOBS, so trimming it makes them all pass vacuously")
        .hasSize(EXPECTED_JOB_COUNT);
  }

  @Test
  @DisplayName("EVERY firing of every scheduled job lands inside 08:00–19:00 IST")
  void nothingIsScheduledOutsideTheOperatingWindow() throws IOException {
    for (Map.Entry<String, String> job : JOBS.entrySet()) {
      String cron = codeDefault(job.getKey(), job.getValue());
      for (String at : firings(job.getKey(), cron)) {
        int hour = Integer.parseInt(at.substring(0, at.indexOf(':')));
        assertThat(hour)
            .as(
                "%s fires at %s IST (cron '%s'). The owner shuts the machine down at %d:00 and"
                    + " starts it after 0%d:00, so this NEVER RUNS — it is not late, it is silently"
                    + " skipped every single day",
                job.getKey(), at, cron, WINDOW_END_HOUR, WINDOW_START_HOUR)
            .isBetween(WINDOW_START_HOUR, WINDOW_END_HOUR - 1);
      }
    }
  }

  @Test
  @DisplayName("no two jobs share a firing minute, across both services")
  void noTwoJobsCollideOnAMinute() throws IOException {
    // The evening tail runs one job per minute with no slack, so a reused minute puts two jobs on
    // the same trigger. Spans both services deliberately: they share one machine and one window,
    // and a per-service check would miss exactly the pairs most likely to collide.
    Map<String, String> takenBy = new HashMap<>();
    for (Map.Entry<String, String> job : JOBS.entrySet()) {
      for (String at : firings(job.getKey(), codeDefault(job.getKey(), job.getValue()))) {
        String previous = takenBy.putIfAbsent(at, job.getKey());
        assertThat(previous).as("%s and %s both fire at %s IST", previous, job.getKey(), at).isNull();
      }
    }
    assertThat(takenBy).hasSizeGreaterThanOrEqualTo(EXPECTED_JOB_COUNT);
  }

  @Test
  @DisplayName("the money-path reconcilers still follow the graduation evaluator and the entry pass")
  void theReconcilersRunAfterTheJobsWhoseOutputTheyRead() throws IOException {
    // PaperReconciliationScheduler's contract is "AFTER the graduation evaluator and the swing
    // batches". Since the 16:00/08:35 split those straddle midnight, so the ordering that matters is
    // the one WITHIN the morning: the 08:35 entry pass must precede them, or they reconcile a book
    // that is about to change.
    int entries = onlyFiring("artha.swing.catchup-cron", SS + "swing/SwingBatchCatchUp.java");
    int reconcile = onlyFiring("artha.paper.reconciliation.cron", SS + "paper/PaperReconciliationScheduler.java");
    int pastExpiry = onlyFiring("artha.paper.past-expiry-recon.cron", SS + "paper/PaperScheduler.java");

    assertThat(reconcile)
        .as("reconciliation must run AFTER the 08:35 entry pass, or it misses that day's entries")
        .isGreaterThan(entries);
    assertThat(pastExpiry)
        .as("past-expiry recovery's own javadoc puts it just after the reconciler")
        .isGreaterThan(reconcile);
    assertThat(pastExpiry)
        .as("...and both must finish before the 09:15 market open")
        .isLessThan(9 * 60 + 15);
  }

  /** Minutes-since-midnight of a job's single firing; fails if it has more than one. */
  private static int onlyFiring(String property, String sourceFile) throws IOException {
    List<String> at = firings(property, codeDefault(property, sourceFile));
    if (at.size() != 1) {
      return fail(property + " fires " + at.size() + " times; this ordering check assumes one");
    }
    String[] hm = at.get(0).split(":");
    return Integer.parseInt(hm[0]) * 60 + Integer.parseInt(hm[1]);
  }

  /** Every {@code HH:mm} a second-precision Spring cron fires at. Refuses what it cannot enumerate. */
  private static List<String> firings(String property, String cron) {
    if (!CronExpression.isValidExpression(cron)) {
      fail(property + " = '" + cron + "' is not a valid Spring cron expression");
    }
    String[] f = cron.trim().split("\\s+");
    if (f.length != 6 || !"0".equals(f[0])) {
      fail(property + " = '" + cron + "' must be a 6-field cron firing on the second :00");
    }
    List<String> out = new ArrayList<>();
    for (int h : expand(property, cron, f[2], 23)) {
      for (int m : expand(property, cron, f[1], 59)) {
        out.add(String.format("%02d:%02d", h, m));
      }
    }
    return out;
  }

  private static List<Integer> expand(String property, String cron, String spec, int max) {
    List<Integer> out = new ArrayList<>();
    for (String part : spec.split(",")) {
      Matcher range = Pattern.compile("^(\\d{1,2})-(\\d{1,2})$").matcher(part);
      if (part.matches("\\d{1,2}")) {
        out.add(bounded(property, cron, Integer.parseInt(part), max));
      } else if (range.matches()) {
        int from = bounded(property, cron, Integer.parseInt(range.group(1)), max);
        int to = bounded(property, cron, Integer.parseInt(range.group(2)), max);
        for (int v = from; v <= to; v++) {
          out.add(v);
        }
      } else {
        fail(
            property
                + " = '"
                + cron
                + "' has a field ('"
                + part
                + "') this test cannot enumerate. Widen it deliberately — never let an unreadable"
                + " schedule pass unchecked");
      }
    }
    return out;
  }

  private static int bounded(String property, String cron, int value, int max) {
    if (value > max) {
      fail(property + " = '" + cron + "' has the out-of-range value " + value);
    }
    return value;
  }

  /** The default in the job's own ACTIVE {@code @Scheduled}, with comments stripped. */
  private static String codeDefault(String property, String sourceFile) throws IOException {
    // ⚠️ Search the JOINED uncommented source, not line by line. Several of these annotations wrap
    // across lines — SwingBatchCatchUp puts `zone` and `scheduler` on the lines after `cron` — so a
    // per-line zone check fails on a job that is correctly zoned. This test found that on its very
    // first run, which is the cheap way to discover it.
    String source =
        String.join(
            "\n",
            uncommented(Files.readString(repoRoot().resolve(sourceFile), StandardCharsets.UTF_8)));
    String needle = "cron = \"${" + property + ":";
    int occurrences = source.split(Pattern.quote(needle), -1).length - 1;
    if (occurrences != 1) {
      return fail(
          sourceFile + " has " + occurrences + " active @Scheduled sites reading ${" + property
              + "} — expected exactly one");
    }
    int at = source.indexOf(needle);
    // Without `zone`, Spring schedules in the JVM's zone (UTC in our containers) and 18:45 becomes
    // 00:15 IST — outside the window, on a line that reads as if it were inside it.
    assertThat(source.substring(at, Math.min(source.length(), at + 240)))
        .as("%s must schedule in IST or the hour above means nothing", property)
        .contains("zone = \"Asia/Kolkata\"");
    Matcher m =
        Pattern.compile("cron = \"\\$\\{" + Pattern.quote(property) + ":([^}]*)\\}\"")
            .matcher(source);
    if (!m.find()) {
      fail(sourceFile + " has no readable default for ${" + property + "}");
    }
    return m.group(1);
  }

  /** Source lines outside any comment. Tracks block state across lines, not per line. */
  private static List<String> uncommented(String source) {
    String open = "/" + "*";
    String close = "*" + "/";
    List<String> out = new ArrayList<>();
    boolean inBlock = false;
    for (String line : source.lines().toList()) {
      String working = line;
      if (inBlock) {
        int end = working.indexOf(close);
        if (end < 0) {
          continue;
        }
        working = working.substring(end + close.length());
        inBlock = false;
      }
      int start = working.indexOf(open);
      while (start >= 0) {
        int end = working.indexOf(close, start + open.length());
        if (end < 0) {
          working = working.substring(0, start);
          inBlock = true;
          break;
        }
        working = working.substring(0, start) + working.substring(end + close.length());
        start = working.indexOf(open);
      }
      int slashes = working.indexOf("//");
      out.add(slashes >= 0 ? working.substring(0, slashes) : working);
    }
    return out;
  }

  /** Walks up to the repo root. FAILS rather than skipping if absent. */
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
