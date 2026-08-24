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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.CronExpression;

/**
 * The owner's operating window, across BOTH services: the machine is shut down at 19:00 IST daily
 * and started after 08:00, so a job scheduled outside 08:00–19:00 does not run late — it does not
 * run at all, silently, on every day the machine goes off on time.
 *
 * <p>⚠️ Reads the default out of the {@code @Scheduled} ANNOTATION, not out of compose. When this
 * test was written, two of the sixteen ({@code artha.insights.*}) had no compose passthrough at all
 * — they reached Spring through {@code application.yml} — so a compose-driven window check would
 * have silently skipped 18:56 and 18:57 and reported a clean sweep. Both passthroughs now exist
 * (added with the review fixes), so that particular gap is closed; reading the annotation stays the
 * right choice because it is the ONE copy that is guaranteed to exist for every job, whatever its
 * override wiring. {@code CronPassthroughParityTest} proves the copies agree byte for byte.
 *
 * <p>⚠️ The catalogue below is hand-written and therefore cannot prove its own completeness — see
 * {@link #everyScheduledJobIsInsideTheWindowOrExplicitlyExcused}, which walks the source instead.
 * Cross-vendor review found six jobs this list had never mentioned.
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
    // Intra-day retry for a FAILED NSE pull (2026-08-24). Three firings a day, so the collision
    // check below expands it to 09:50/11:50/14:50 and each must be free — that is why it is here.
    JOBS.put("artha.nse.fii-retry-cron", MD + "nse/NseEodScheduler.java");
    JOBS.put("artha.minervini.cron", MD + "screener/minervini/MinerviniScheduler.java");
    JOBS.put("artha.manas-arora.cron", MD + "screener/manas/ManasScheduler.java");
    JOBS.put("artha.context.eod-cron", MD + "context/MarketContextEodJob.java");
    JOBS.put("artha.data-quality.eod-cron", MD + "dataquality/DataQualityEodJob.java");
    JOBS.put("artha.breadth.materialize-cron", MD + "nse/analytics/EquityBreadthEodJob.java");
    JOBS.put("artha.bhavcopy-close.cron", MD + "canary/BhavcopyCloseCanary.java");
    JOBS.put(
        "artha.minervini.buyable-alerts.cron", MD + "screener/minervini/MinerviniBuyableProducer.java");
    // The two swing SETTLES. Catalogued 2026-08-19 with ledger H27, which is also the reason they
    // moved out of 16:00/16:02: they were the only evening jobs this map had never named, so the
    // collision check could not see them and their hour was never asserted against the tail they
    // now sit in. Ordering that matters: AFTER artha.bhavcopy.eod-cron (18:45), which writes the
    // session bar they price off, and BEFORE artha.heartbeat.swing-cron (18:54), which reports
    // whether they ran.
    JOBS.put("artha.minervini.swing.cron", SS + "minervini/MinerviniSwingScheduler.java");
    JOBS.put("artha.manas-arora.swing.cron", SS + "manas/ManasAroraSwingScheduler.java");
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

  private static final int EXPECTED_JOB_COUNT = 19;

  @Test
  @DisplayName("the catalogue is not silently shrunk")
  void theCatalogueCoversEveryJobItClaimsTo() {
    assertThat(JOBS)
        .as("the strict-window assertions iterate JOBS, so trimming it makes them pass vacuously")
        .hasSize(EXPECTED_JOB_COUNT);
  }

  /**
   * Jobs allowed to sit outside the window, each with the reason. <b>Deliberately EMPTY.</b>
   *
   * <p>It briefly held the two weekend jobs — {@code CrossSourceOiCanary} (Sunday 07:00) and the
   * {@code InsightSweeper} quality report (Saturday 08:00) — parked on a question this test may not
   * answer by guessing: is the machine up at a weekend? The owner answered on 2026-08-12:
   * <b>weekday-only</b>. So both had never run at all, and both moved into weekday evening slots
   * (Friday 18:16 and Friday 18:12) rather than staying excused. The OI canary is on FRIDAY, not
   * Monday: it selects {@code expiry < today}, and holiday preponement moves a weekly expiry
   * EARLIER, so a Monday can itself be an expiry and would be excluded from its own sweep.
   *
   * <p>The map stays because an empty allowlist is the assertion — every scheduled job in either
   * service can now fire inside the window, and adding an entry here has to be a deliberate act with
   * a written reason rather than a quiet edit to a threshold.
   */
  private static final Map<String, String> EXCUSED_FROM_THE_WINDOW = new LinkedHashMap<>();

  @Test
  @DisplayName("no scheduled job anywhere in either service is invisible to this guard")
  void everyScheduledJobIsInsideTheWindowOrExplicitlyExcused() throws IOException {
    // ⚠️ THE REASON THIS TEST EXISTS. The catalogue above is HAND-WRITTEN, so on its own it proves
    // only that a hand-written map still has 16 entries — a job nobody added to it is not caught,
    // it is invisible, and the sweep reports clean. Cross-vendor review found exactly that: six
    // more jobs outside the window, four of which had never run at all since the machine started
    // going off overnight. This walks the source instead of trusting the list.
    List<DiscoveredJob> all = discoverEveryScheduledJob();
    assertThat(all)
        .as("the source walk found almost nothing — it is checking an empty set, not the schedule")
        .hasSizeGreaterThan(50);

    // Every discovered cron must be IST-zoned BEFORE its hour means anything. An unzoned job runs in
    // the container's UTC, so `0 0 18` fires at 23:30 IST — inside the window on the page, never run
    // in reality, and invisible to any hour check that assumes IST.
    List<String> unzoned =
        all.stream()
            .filter(job -> !"Asia/Kolkata".equals(job.zone()))
            .map(job -> job.simpleClassName() + "#" + job.cron() + " (zone=" + job.zone() + ")")
            .toList();
    assertThat(unzoned)
        .as(
            "a cron without zone=\"Asia/Kolkata\" is scheduled in the JVM zone — UTC in our"
                + " containers — so its hour is not the hour it reads as, and every hour-based"
                + " assertion in this file silently means something else")
        .isEmpty();

    List<String> unreachable = new ArrayList<>();
    for (DiscoveredJob job : all) {
      if (!canEverFireInsideTheWindow(job.cron())) {
        unreachable.add(job.simpleClassName() + "#" + job.cron());
      }
    }
    assertThat(unreachable)
        .as(
            "a job that can never fire between %d:00 and %d:00 on a weekday does not run LATE, it"
                + " does not run at all. Move it into the window, or add it to"
                + " EXCUSED_FROM_THE_WINDOW with the reason",
            WINDOW_START_HOUR, WINDOW_END_HOUR)
        .containsExactlyInAnyOrderElementsOf(EXCUSED_FROM_THE_WINDOW.keySet());
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
    // the one WITHIN the morning: the 08:35 entry pass must START before them.
    //
    // ⚠️ READ WHAT THIS PROVES, AND WHAT IT DOES NOT. It compares CRON MINUTES. A cron minute is a
    // start time, not a dependency, so this shows the intended ORDER of triggers and nothing about
    // completion — a catch-up still running at 08:50 is not excluded by anything here. Cross-vendor
    // review flagged an earlier version of this comment for claiming the jobs "finish" before the
    // open, which no assertion in this file can establish.
    int entries = onlyFiring("artha.swing.catchup-cron", SS + "swing/SwingBatchCatchUp.java");
    int reconcile = onlyFiring("artha.paper.reconciliation.cron", SS + "paper/PaperReconciliationScheduler.java");
    int pastExpiry = onlyFiring("artha.paper.past-expiry-recon.cron", SS + "paper/PaperScheduler.java");

    assertThat(reconcile)
        .as("reconciliation must be TRIGGERED after the 08:35 entry pass, or it cannot even see that"
            + " day's entries")
        .isGreaterThan(entries);
    assertThat(pastExpiry)
        .as("past-expiry recovery's own javadoc puts it just after the reconciler")
        .isGreaterThan(reconcile);
    assertThat(pastExpiry)
        .as("...and both must be triggered before the 09:15 open — whether they FINISH before it is"
            + " not something a cron minute can promise, and nothing here asserts that")
        .isLessThan(9 * 60 + 15);
  }

  @Test
  @DisplayName("each swing settle is triggered after the data it prices off, and before the heartbeat")
  void theSwingSettlesRunAfterTheBarTheyEvaluateAgainst() throws IOException {
    // ⚠️ THIS IS THE ONLY TEST THAT PINS THE HOUR, and it exists because nothing else did.
    // CronPassthroughParityTest proves code and compose AGREE; the window test proves the settle is
    // inside 08:00-19:00; the collision test proves no two jobs share a minute. Restoring 16:00/16:02
    // in BOTH copies satisfies all three — which is exactly the H27 defect, and it would have shipped
    // back green. Cross-vendor review of this PR, 2026-08-20.
    //
    // The invariant is a DEPENDENCY, not a preference: most cash equities have no intraday 1d bar at
    // all, so the session bar these stops are evaluated against is written by the 18:45 bhavcopy EOD
    // ingest. A settle triggered before it prices every held stop off the PREVIOUS session's close.
    //
    // ⚠️ Same caveat as the reconciler ordering test above: these are CRON MINUTES, so this shows
    // the intended order of TRIGGERS. That the 18:45 ingest has FINISHED by 18:52 is not something a
    // cron minute can promise, and nothing here asserts it. What the pin buys is that a late or
    // failed ingest becomes a counted exitSkipped rather than a silent stale-price exit.
    int bhavcopy = onlyFiring("artha.bhavcopy.eod-cron", MD + "bhavcopy/BhavcopyBackfillService.java");
    int minerviniScreen = onlyFiring("artha.minervini.cron", MD + "screener/minervini/MinerviniScheduler.java");
    int manasScreen = onlyFiring("artha.manas-arora.cron", MD + "screener/manas/ManasScheduler.java");
    int minerviniSettle =
        onlyFiring("artha.minervini.swing.cron", SS + "minervini/MinerviniSwingScheduler.java");
    int manasSettle =
        onlyFiring("artha.manas-arora.swing.cron", SS + "manas/ManasAroraSwingScheduler.java");
    int heartbeat = onlyFiring("artha.heartbeat.swing-cron", SS + "signals/SwingBatchHeartbeat.java");

    assertThat(minerviniSettle)
        .as("the minervini settle must follow the 18:45 bhavcopy EOD ingest that writes the session"
            + " bar its stops are evaluated against — this is ledger H27")
        .isGreaterThan(bhavcopy);
    assertThat(manasSettle)
        .as("and so must the manas settle, for the same bar")
        .isGreaterThan(bhavcopy);
    assertThat(minerviniSettle)
        .as("each settle must also follow its OWN screen, which is what refreshes the funnel it reads")
        .isGreaterThan(minerviniScreen);
    assertThat(manasSettle).isGreaterThan(manasScreen);
    assertThat(manasSettle)
        .as("manas stays one behind its minervini twin, as 16:00/16:02 were — they share the mutex")
        .isGreaterThan(minerviniSettle);
    assertThat(heartbeat)
        .as("the heartbeat reports whether the settles ran, so it cannot be triggered before them")
        .isGreaterThan(manasSettle);
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

  /**
   * One {@code @Scheduled} site found by walking the source.
   *
   * <p>{@code zone} is carried, not discarded, and that is load-bearing: without {@code zone} Spring
   * schedules in the JVM's zone, which is UTC in our containers, so a job written {@code 0 0 18}
   * fires at <b>23:30 IST</b>. Reading the hour as if it were IST would let exactly that job pass
   * this guard while never running. Cross-vendor review found it in the first version of this walk,
   * which kept only the class name and the cron.
   */
  private record DiscoveredJob(String simpleClassName, String cron, String zone) {}

  private static final Pattern SCHEDULED = Pattern.compile("@Scheduled\\s*\\(([^)]*)\\)", Pattern.DOTALL);
  private static final Pattern CRON_ARG = Pattern.compile("cron\\s*=\\s*\"([^\"]*)\"");
  private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{([^:}]+)(?::(.*))?\\}$");
  private static final Pattern ZONE_ARG = Pattern.compile("zone\\s*=\\s*\"([^\"]*)\"");

  /**
   * Every {@code @Scheduled} carrying a cron, across BOTH services. {@code fixedRate}/{@code
   * fixedDelay} sites are skipped deliberately — they fire relative to boot, so they always run
   * while the machine is up and have no wall-clock slot to be outside the window.
   */
  private static List<DiscoveredJob> discoverEveryScheduledJob() throws IOException {
    List<DiscoveredJob> out = new ArrayList<>();
    for (String service : List.of("market-data-service", "strategy-signal-service")) {
      Path root = repoRoot().resolve("services/" + service + "/src/main/java");
      List<Path> files;
      try (Stream<Path> walk = Files.walk(root)) {
        files = walk.filter(p -> p.getFileName().toString().endsWith(".java")).sorted().toList();
      }
      for (Path file : files) {
        String source =
            String.join("\n", uncommented(Files.readString(file, StandardCharsets.UTF_8)));
        String simple = file.getFileName().toString().replace(".java", "");
        Matcher site = SCHEDULED.matcher(source);
        while (site.find()) {
          Matcher cron = CRON_ARG.matcher(site.group(1));
          if (cron.find()) {
            Matcher zone = ZONE_ARG.matcher(site.group(1));
            out.add(
                new DiscoveredJob(
                    simple, resolve(simple, cron.group(1)), zone.find() ? zone.group(1) : null));
          }
        }
      }
    }
    return out;
  }

  /**
   * The effective cron: a literal, or a {@code ${property:default}}'s default. A placeholder with NO
   * inline default is resolved from the owning service's {@code application.yml}, which is where the
   * one such job (the hourly expired-backfill self-heal) declares its value.
   */
  private static String resolve(String where, String raw) throws IOException {
    Matcher m = PLACEHOLDER.matcher(raw.trim());
    if (!m.matches()) {
      return raw.trim();
    }
    if (m.group(2) != null) {
      return m.group(2).trim();
    }
    String property = m.group(1);
    String leaf = property.substring(property.lastIndexOf('.') + 1);
    Pattern inYaml =
        Pattern.compile("^\\s*" + Pattern.quote(leaf) + ":\\s*\\$\\{[^:}]+:([^}]*)\\}", Pattern.MULTILINE);
    List<String> found = new ArrayList<>();
    for (String service : List.of("market-data-service", "strategy-signal-service")) {
      Path yaml = repoRoot().resolve("services/" + service + "/src/main/resources/application.yml");
      Matcher hit = inYaml.matcher(Files.readString(yaml, StandardCharsets.UTF_8));
      while (hit.find()) {
        found.add(hit.group(1).trim());
      }
    }
    if (found.size() != 1) {
      return fail(
          where + "'s ${" + property + "} has no inline default and resolving '" + leaf
              + "' in application.yml found " + found.size() + " candidates — this test must not"
              + " guess a schedule it cannot read");
    }
    return found.get(0);
  }

  /**
   * Can this cron fire at least once between {@link #WINDOW_START_HOUR} and {@link #WINDOW_END_HOUR}
   * on a weekday? Deliberately an INTERSECTION test, not containment: an intraday job firing every
   * minute is outside the window most of the day and that is entirely correct — it runs whenever the
   * machine is up. The failure this catches is the strictly-nocturnal or weekend-only job, which
   * never runs at all. The 16 chain jobs get the stricter containment test separately, above.
   */
  private static boolean canEverFireInsideTheWindow(String cron) {
    String[] f = cron.trim().split("\\s+");
    if (f.length != 6) {
      return false;
    }
    boolean hourReachable =
        looseExpand(f[2], 23).stream().anyMatch(h -> h >= WINDOW_START_HOUR && h < WINDOW_END_HOUR);
    return hourReachable && weekdayPossible(f[5]);
  }

  /** Values a cron field can take. Unlike {@link #expand} this ACCEPTS {@code *} and steps. */
  private static List<Integer> looseExpand(String spec, int max) {
    List<Integer> out = new ArrayList<>();
    for (String part : spec.split(",")) {
      int step = 1;
      String base = part;
      int slash = part.indexOf('/');
      if (slash >= 0) {
        base = part.substring(0, slash);
        step = Math.max(1, Integer.parseInt(part.substring(slash + 1)));
      }
      int from;
      int to;
      if ("*".equals(base) || "?".equals(base)) {
        from = 0;
        to = max;
      } else if (base.matches("\\d{1,2}-\\d{1,2}")) {
        from = Integer.parseInt(base.substring(0, base.indexOf('-')));
        to = Integer.parseInt(base.substring(base.indexOf('-') + 1));
      } else if (base.matches("\\d{1,2}")) {
        from = Integer.parseInt(base);
        to = slash >= 0 ? max : from;
      } else {
        // Unreadable rather than absent: treat as reachable so an unparsed field can never be
        // reported as a dead job. A false PASS here is recoverable; a false "never runs" is a
        // schedule change made on a misreading.
        return List.of(WINDOW_START_HOUR);
      }
      for (int v = from; v <= Math.min(to, max); v += step) {
        out.add(v);
      }
    }
    return out;
  }

  /** Whether a Spring day-of-week field admits any of MON–FRI (names or 1–5; 0 and 7 are Sunday). */
  private static boolean weekdayPossible(String dow) {
    String d = dow.toUpperCase(Locale.ROOT);
    if (d.contains("*") || d.contains("?")) {
      return true;
    }
    for (String name : List.of("MON", "TUE", "WED", "THU", "FRI")) {
      if (d.contains(name)) {
        return true;
      }
    }
    for (int v : looseExpand(d.replaceAll("[A-Z]", "9"), 7)) {
      if (v >= 1 && v <= 5) {
        return true;
      }
    }
    return false;
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
