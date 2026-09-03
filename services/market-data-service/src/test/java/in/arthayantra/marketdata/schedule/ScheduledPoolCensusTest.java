package in.arthayantra.marketdata.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Re-derives the {@code @Scheduled} census that {@code MonitorSchedulingConfig} argues from, so the
 * figures in that javadoc stop being a number only a throwaway script could reproduce.
 *
 * <p>⚠️ Why this file exists. The default {@code TaskScheduler} is ONE thread, and the case for
 * every dedicated pool bean ({@code dayContextTaskScheduler}, {@code oiCaptureTaskScheduler}, …) is
 * "N jobs already share that one thread, one of which runs ~70 s per pass". That N was written into
 * the code as <b>55</b> and was wrong, because it came from a grep. Both mistakes are pinned here:
 *
 * <ul>
 *   <li>{@code grep -rn "@Scheduled"} also matches {@code @Scheduled} written inside javadoc, which
 *       is what inflated 38 to 55 — so {@link #blankComments} removes block and line comments
 *       first, string/char/text-block aware so a literal {@code "@Scheduled"} cannot be blanked
 *       away NOR counted as a site.
 *   <li>{@code grep -vc "scheduler ="} excludes only SAME-LINE matches, and these annotations
 *       routinely wrap across lines — so {@link #annotationArguments} matches paren-balanced across
 *       the whole annotation rather than per line.
 * </ul>
 *
 * <p><b>What this does and does not detect.</b> It detects an annotation ADDED or REMOVED, and it
 * detects a job MOVED between pools: the default-pool and named-scheduler figures are asserted
 * separately, so moving one job off the default pool onto a dedicated bean holds the total at 38
 * while 9/29 becomes 10/28 and reddens. It does NOT know which job SHOULD live where — it counts,
 * it does not judge — and it is deliberately source-text based, so a job scheduled programmatically
 * (a {@code SchedulingConfigurer} registering a task by hand, with no annotation) is invisible to
 * it.
 */
class ScheduledPoolCensusTest {

  /** One {@code @Scheduled} annotation: where it sits, and the pool it lands on. */
  private record Site(String className, int line, String scheduler) {}

  private static final String MAIN_SOURCES = "services/market-data-service/src/main/java";

  /** The pool a {@code @Scheduled} with no {@code scheduler} argument falls back to. */
  private static final String DEFAULT_POOL = "<default taskScheduler>";

  private static final Pattern SCHEDULED = Pattern.compile("@Scheduled\\b");
  private static final Pattern SCHEDULER_ARG =
      Pattern.compile("\\bscheduler\\s*=\\s*\"([^\"]*)\"");

  /**
   * ⚠️ Computed 2026-08-26 by this test's own scan of the merged tree, not carried over from the
   * comment it replaces — that comment said <b>55</b>, and 55 was a grep artifact.
   *
   * <p>⚠️ RAISED 38 -> 40 and 9 -> 11 later on 2026-08-26 by the Kite TOTP auto-login, which adds
   * {@code KiteAutoLoginService.scheduledLogin} and {@code .watchdog}, BOTH naming {@code
   * monitorTaskScheduler}. The default-pool figure is therefore UNCHANGED at 29, and that is the
   * point of asserting the three separately: a total-only check would have read this as "two more
   * jobs queue behind the ~70 s options pass", which is exactly what did not happen.
   *
   * <p>⚠️ The two new sites exist in the SOURCE unconditionally but their bean is
   * {@code @ConditionalOnProperty(artha.kite.auto-login.enabled)}, default false — so today they
   * are counted here and registered nowhere. This test counts source text and says so; do not read
   * the census as a statement about what is scheduled on the live stack.
   *
   * <p>⚠️ RAISED 41 -> 42 and 11 -> 12 on 2026-09-02 by the NEW-13 outbound-reachability probe
   * ({@code NetworkReachabilityProbe.probe}), which names {@code reachabilityTaskScheduler}.
   * <b>The default-pool figure is UNCHANGED</b>, and that is the whole signal: this job blocks on
   * network timeouts by design, so it joining the shared one-thread pool would have let an outage
   * stall every other scheduled method — the probe would CAUSE the wider failure it exists to
   * observe. A total-only assertion could not have told those two outcomes apart.
   *
   * <p>Raising or lowering any of these three is a deliberate act. They are asserted separately on
   * purpose: the total alone cannot tell "a job was added to the shared pool" from "a job moved off
   * it onto a dedicated bean", and those two have opposite meanings for the single-thread argument
   * in {@code MonitorSchedulingConfig}.
   */
  private static final int EXPECTED_SCHEDULED_ANNOTATIONS = 42;

  /**
   * How many of those name a {@code scheduler} bean, i.e. sit on a dedicated single-thread pool.
   */
  private static final int EXPECTED_NAMING_A_SCHEDULER = 12;

  /**
   * How many share the ONE-thread default pool — the figure the dedicated beans argue from.
   */
  private static final int EXPECTED_ON_DEFAULT_POOL =
      EXPECTED_SCHEDULED_ANNOTATIONS - EXPECTED_NAMING_A_SCHEDULER;

  @Test
  @DisplayName("the @Scheduled census MonitorSchedulingConfig argues from still holds")
  void theScheduledCensusStillHolds() throws IOException {
    List<Site> sites = census();

    assertThat(sites)
        .as(
            "the scan found no @Scheduled annotation at all under %s — the walk or the comment"
                + " stripper is broken, and a census that counts nothing must fail rather than"
                + " agree with whatever number it is compared against",
            MAIN_SOURCES)
        .isNotEmpty();

    String breakdown = breakdown(sites);
    long naming = sites.stream().filter(site -> !DEFAULT_POOL.equals(site.scheduler())).count();
    long onDefault = sites.size() - naming;

    assertThat(sites.size())
        .as(
            "the number of @Scheduled annotations in market-data-service main sources moved."
                + " MonitorSchedulingConfig's javadoc and DayContextSchedulerBindingTest both cite"
                + " this figure as the argument for every dedicated scheduler bean, so update both"
                + " comments in the same PR.%n%s",
            breakdown)
        .isEqualTo(EXPECTED_SCHEDULED_ANNOTATIONS);

    assertThat(naming)
        .as(
            "the number of @Scheduled annotations naming a scheduler bean moved. If the total above"
                + " is unchanged, a job MOVED between pools rather than being added.%n%s",
            breakdown)
        .isEqualTo(EXPECTED_NAMING_A_SCHEDULER);

    assertThat(onDefault)
        .as(
            "the number of jobs on the ONE-thread default pool moved. This is the figure the"
                + " single-thread argument rests on: OptionsSnapshotService.scheduledSnapshot sizes"
                + " its own pass at ~70 s, and everything counted here queues behind it.%n%s",
            breakdown)
        .isEqualTo(EXPECTED_ON_DEFAULT_POOL);
  }

  /**
   * Every {@code @Scheduled} annotation under the service's main sources, in file order.
   *
   * <p>Reads the SOURCE rather than the Spring context on purpose: a context test can only see the
   * beans it chose to load, so a scheduled method in a class the slice does not import counts as
   * zero — the guard-that-checks-nothing shape, wearing a passing gate's clothes.
   */
  private static List<Site> census() throws IOException {
    Path root = repoRoot().resolve(MAIN_SOURCES);
    List<Site> sites = new ArrayList<>();
    List<Path> files;
    try (Stream<Path> walk = Files.walk(root)) {
      files =
          walk.filter(path -> path.getFileName().toString().endsWith(".java"))
              .sorted(Comparator.comparing(Path::toString))
              .toList();
    }
    for (Path file : files) {
      String code = blankComments(Files.readString(file, StandardCharsets.UTF_8));
      String className = file.getFileName().toString().replaceFirst("\\.java$", "");
      Matcher matcher = SCHEDULED.matcher(code);
      while (matcher.find()) {
        String arguments = annotationArguments(code, matcher.end(), file);
        Matcher scheduler = SCHEDULER_ARG.matcher(arguments);
        sites.add(
            new Site(
                className,
                lineOf(code, matcher.start()),
                scheduler.find() ? scheduler.group(1) : DEFAULT_POOL));
      }
    }
    return sites;
  }

  /**
   * The parenthesised argument list following an annotation, or empty if it has none.
   *
   * <p>⚠️ Paren-balanced across newlines. A per-line read is how the original figure went wrong the
   * second way: {@code @Scheduled(cron = "…", zone = "…", scheduler = "…")} wraps, so a line-scoped
   * "does it say scheduler =" answers NO for a job that plainly does.
   */
  private static String annotationArguments(String code, int after, Path file) {
    int at = after;
    while (at < code.length() && Character.isWhitespace(code.charAt(at))) {
      at++;
    }
    if (at >= code.length() || code.charAt(at) != '(') {
      return "";
    }
    int depth = 0;
    for (int i = at; i < code.length(); i++) {
      char c = code.charAt(i);
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth--;
        if (depth == 0) {
          return code.substring(at, i + 1);
        }
      }
    }
    return fail(
        file
            + " has an @Scheduled whose argument list never closes — the source is unparseable"
            + " here, and guessing the arguments would report a scheduler this annotation may not"
            + " name");
  }

  /**
   * The source with every comment replaced by spaces, newlines kept so line numbers stay true.
   *
   * <p>String, char and TEXT-BLOCK aware in both directions. A {@code "//"} inside a SQL text block
   * must not blank the rest of that line, and a {@code "@Scheduled"} inside a string literal must
   * not be counted as an annotation — the census below matches on the returned text, so a literal
   * survives here and is excluded by {@link #SCHEDULED} needing an {@code @} it does not have.
   */
  private static String blankComments(String source) {
    char[] out = source.toCharArray();
    int i = 0;
    while (i < source.length()) {
      char c = source.charAt(i);
      if (source.startsWith("\"\"\"", i)) {
        int end = source.indexOf("\"\"\"", i + 3);
        i = end < 0 ? source.length() : end + 3;
        continue;
      }
      if (c == '"' || c == '\'') {
        i = endOfLiteral(source, i, c);
        continue;
      }
      if (source.startsWith("/*", i)) {
        int end = source.indexOf("*/", i + 2);
        end = end < 0 ? source.length() : end + 2;
        blank(out, i, end);
        i = end;
        continue;
      }
      if (source.startsWith("//", i)) {
        int end = source.indexOf('\n', i);
        end = end < 0 ? source.length() : end;
        blank(out, i, end);
        i = end;
        continue;
      }
      i++;
    }
    return new String(out);
  }

  /** The index just past a string or char literal opened at {@code from}. */
  private static int endOfLiteral(String source, int from, char quote) {
    int i = from + 1;
    while (i < source.length()) {
      char c = source.charAt(i);
      if (c == '\\') {
        i += 2;
        continue;
      }
      i++;
      if (c == quote) {
        break;
      }
    }
    return i;
  }

  private static void blank(char[] out, int from, int to) {
    for (int i = from; i < to; i++) {
      if (out[i] != '\n') {
        out[i] = ' ';
      }
    }
  }

  /** The 1-based line number of an offset. */
  private static int lineOf(String code, int offset) {
    int line = 1;
    for (int i = 0; i < offset; i++) {
      if (code.charAt(i) == '\n') {
        line++;
      }
    }
    return line;
  }

  /**
   * The per-pool, per-class listing printed on failure.
   *
   * <p>A bare "expected 38 but was 39" tells the next reader nothing; WHICH class landed on WHICH
   * pool is the whole answer, and it is what makes a pool MOVE readable as a move.
   */
  private static String breakdown(List<Site> sites) {
    Map<String, List<Site>> byScheduler = new LinkedHashMap<>();
    sites.stream()
        .map(Site::scheduler)
        .distinct()
        .sorted(Comparator.comparing(name -> DEFAULT_POOL.equals(name) ? "" : name))
        .forEach(name -> byScheduler.put(name, new ArrayList<>()));
    for (Site site : sites) {
      byScheduler.get(site.scheduler()).add(site);
    }
    StringBuilder text = new StringBuilder("@Scheduled census (" + sites.size() + " annotations):");
    byScheduler.forEach(
        (scheduler, members) -> {
          text.append(String.format("%n  %s — %d:", scheduler, members.size()));
          for (Site site : members) {
            text.append(String.format("%n      %s:%d", site.className(), site.line()));
          }
        });
    return text.toString();
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
