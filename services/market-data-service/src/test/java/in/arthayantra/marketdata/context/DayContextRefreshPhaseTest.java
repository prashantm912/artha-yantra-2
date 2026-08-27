package in.arthayantra.marketdata.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.CronExpression;

/**
 * H31: the day-context precompute is correct ONLY because of a PHASE relationship between two
 * schedules in two different services, and that relationship is invisible in either knob.
 *
 * <p><b>The trap this exists to close.</b> The refresh cron runs every 15 minutes while the snapshot
 * is honoured for 300 s, so for 10 of every 15 minutes the held snapshot IS past max-age and
 * {@code DayContextService.heavy()} falls through to an inline compute — which is exactly the H31
 * defect. It does not happen only because the refresh fires at :13/:28/:43/:58, two minutes ahead of
 * the insight sweep at :00/:15/:30/:45. The real safety margin is {@code 120 s − refresh duration}
 * (measured 2026-08-27: a worst-case 34 s refresh spent 28% of it), and **moving either cron alone
 * silently reinstates the defect** — no error, no failing assertion anywhere else, callers simply
 * resume paying the upstream reads inline.
 *
 * <p><b>Why this reads BOTH source files rather than hardcoding the consumer's schedule.</b> A copy
 * of the sweeper's cron kept here would pin THIS module's belief about a collaborator, not the
 * collaborator — it would keep passing after the sweeper moved, which is the precise failure mode
 * that makes such a guard worse than none. Both crons are parsed out of the files that declare them.
 */
class DayContextRefreshPhaseTest {

  private static final String PRODUCER =
      "services/market-data-service/src/main/java/in/arthayantra/marketdata/context/"
          + "DayContextService.java";
  private static final String CONSUMER =
      "services/strategy-signal-service/src/main/java/in/arthayantra/strategysignal/insights/"
          + "InsightSweeper.java";

  /** {@code ${some.property:THE DEFAULT}} — the default is what ships when no env var is set. */
  private static final Pattern PLACEHOLDER_DEFAULT =
      Pattern.compile(
          Pattern.quote("${artha.context.day-context-refresh-cron:") + "([^}]+)}");

  private static final Pattern SWEEP_DEFAULT =
      Pattern.compile(Pattern.quote("${artha.insights.context-cron:") + "([^}]+)}");

  private static final Pattern MAX_AGE_DEFAULT =
      Pattern.compile(
          Pattern.quote("artha.context.day-context-snapshot-max-age-seconds:") + "([0-9]+)}");

  /**
   * Headroom the refresh needs to actually finish before its consumer reads it. The measured
   * worst case is 34 s (2026-08-27, a soft-failing upstream); 60 s keeps a margin over that
   * without pinning the exact 120 s the schedules happen to give today.
   */
  private static final Duration MIN_LEAD = Duration.ofSeconds(60);

  @Test
  void everySweepReadsSnapshotStillInsideMaxAge() throws IOException {
    CronExpression refresh = CronExpression.parse(extract(PRODUCER, PLACEHOLDER_DEFAULT));
    CronExpression sweep = CronExpression.parse(extract(CONSUMER, SWEEP_DEFAULT));
    Duration maxAge = Duration.ofSeconds(Long.parseLong(extract(PRODUCER, MAX_AGE_DEFAULT)));

    // A representative trading Wednesday. Both crons are minute-of-hour patterns restricted to
    // session hours, so one session enumerates every distinct phase relationship they can have.
    // 08:59 rather than 09:00: next() is EXCLUSIVE, so starting at 09:00 would skip the
    // first sweep of the day — the one whose upstream reads are coldest.
    LocalDateTime cursor = LocalDateTime.of(2026, 8, 26, 8, 59);
    LocalDateTime close = LocalDateTime.of(2026, 8, 26, 15, 30);

    int checked = 0;
    for (LocalDateTime at = sweep.next(cursor); at != null && at.isBefore(close);
        at = sweep.next(at)) {
      LocalDateTime lastRefresh = lastRefreshAtOrBefore(refresh, at);
      Duration age = Duration.between(lastRefresh, at);
      assertThat(age)
          .as(
              "sweep at %s reads a snapshot refreshed at %s (age %ss) — max-age is %ss, so this"
                  + " sweep pays an INLINE compute and H31 has silently regressed",
              at.toLocalTime(), lastRefresh.toLocalTime(), age.toSeconds(), maxAge.toSeconds())
          .isLessThanOrEqualTo(maxAge);
      // Max-age alone is NOT sufficient, and this is the half a naive guard misses: a refresh
      // moved to :14:59 would score an age of 1 s and pass, while leaving the refresh no time to
      // COMPLETE before the sweep reads it. The refresh itself is not instant -- measured
      // 2026-08-27, a soft-failing Upstox call made the worst morning refresh take 34 s. Require
      // real headroom so a slow refresh cannot silently land after its own consumer.
      assertThat(age)
          .as(
              "the refresh at %s fires only %ss before the sweep at %s — too tight to finish;"
                  + " a slow refresh (34 s measured 2026-08-27) would land AFTER its consumer",
              lastRefresh.toLocalTime(), age.toSeconds(), at.toLocalTime())
          .isGreaterThanOrEqualTo(MIN_LEAD);
      checked++;
    }

    // Without this the loop could enumerate nothing and the guard would pass vacuously.
    assertThat(checked)
        .as("no sweep fires were enumerated — the guard checked nothing")
        .isGreaterThanOrEqualTo(20);
  }

  private static LocalDateTime lastRefreshAtOrBefore(CronExpression refresh, LocalDateTime at) {
    // CronExpression only walks forward, so step back from an hour earlier to the latest fire that
    // is not after `at`. An hour covers any sane refresh cadence for this schedule.
    LocalDateTime probe = at.minusHours(1);
    LocalDateTime last = null;
    for (LocalDateTime f = refresh.next(probe); f != null && !f.isAfter(at); f = refresh.next(f)) {
      last = f;
    }
    if (last == null) {
      return fail(
          "no day-context refresh fires in the hour before the sweep at "
              + at
              + " — every sweep would compute inline");
    }
    return last;
  }

  private static String extract(String relativePath, Pattern pattern) throws IOException {
    Path file = repoRoot().resolve(relativePath);
    String code = Files.readString(file, StandardCharsets.UTF_8);
    Matcher m = pattern.matcher(code);
    if (!m.find()) {
      return fail(
          "could not find "
              + pattern.pattern()
              + " in "
              + relativePath
              + " — the property was renamed or its default removed, and this guard can no longer"
              + " see the schedule it exists to protect");
    }
    return m.group(1).trim();
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
