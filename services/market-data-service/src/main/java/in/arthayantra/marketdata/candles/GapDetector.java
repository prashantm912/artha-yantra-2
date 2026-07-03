package in.arthayantra.marketdata.candles;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Gap math (Phase 11 / Flow 3): expected buckets from the calendar minus present buckets →
 * contiguous missing sub-ranges, each then fetched in ≤ 60-day pages. Buckets whose END sits
 * inside the trailing recency window are always re-fetched (the B-4 recency rule: an in-progress
 * bar — today's 1d bucket especially — must keep refreshing; closed bars outside the window are
 * immutable).
 *
 * <p>The window was 2 h until 2026-07-03 (deliberate NARROWING of the frozen B-4 constant, live
 * evidence in `docs/signal-analysis/2026-07-03-session-findings.md` ledger #9): with a 2 h tail,
 * EVERY cache-first read whose {@code to} touched "now" re-fetched two hours of already-covered
 * bars from Kite — a session-long re-fetch treadmill across all pollers (signal engine, charts,
 * EOD job) that burned rate budget and re-stamped tick-agg provenance. 10 min keeps the whole
 * point of the rule (the in-progress bucket's end is always after now, so it always refreshes;
 * just-closed bars get a revision tail) while making closed coverage cache-final. Official-bar
 * convergence beyond the tail is owned by the EOD paths, exactly as before (the old rule only
 * ever converged a rolling 2 h, never the full session).
 */
public final class GapDetector {

  /** A contiguous missing sub-range, half-open {@code [from, to)}. */
  public record Gap(OffsetDateTime from, OffsetDateTime to) {}

  static final Duration RECENCY_WINDOW = Duration.ofMinutes(10);
  static final Duration MAX_PAGE = Duration.ofDays(60);

  private final TradingBuckets buckets;

  /** Wires the bucket enumerator. */
  public GapDetector(TradingBuckets buckets) {
    this.buckets = buckets;
  }

  /**
   * Missing sub-ranges for an interval. {@code present} must be the bucket starts already cached;
   * buckets inside the trailing recency window count as missing regardless.
   */
  public List<Gap> gaps(
      String interval,
      OffsetDateTime from,
      OffsetDateTime to,
      List<OffsetDateTime> present,
      OffsetDateTime now) {
    List<OffsetDateTime> expected =
        interval.equals("1d") ? buckets.dayBuckets(from, to) : buckets.minuteBuckets(from, to);
    if (expected.isEmpty()) {
      return List.of();
    }
    Set<OffsetDateTime> have = new HashSet<>();
    for (OffsetDateTime p : present) {
      have.add(p.toInstant().atOffset(java.time.ZoneOffset.UTC));
    }
    OffsetDateTime recencyStart = now.minus(RECENCY_WINDOW);
    Duration step = interval.equals("1d") ? Duration.ofDays(1) : Duration.ofMinutes(1);

    List<Gap> gaps = new ArrayList<>();
    OffsetDateTime gapStart = null;
    OffsetDateTime previous = null;
    for (OffsetDateTime bucket : expected) {
      // recency compares the bucket END: an in-progress bar (today's 1d bucket especially)
      // must keep re-fetching until the window clears its CLOSE — start-based checks froze it
      boolean missing =
          !have.contains(bucket.toInstant().atOffset(java.time.ZoneOffset.UTC))
              || bucket.plus(step).isAfter(recencyStart);
      if (missing && gapStart == null) {
        gapStart = bucket;
      } else if (!missing && gapStart != null) {
        gaps.add(new Gap(gapStart, previous.plus(step)));
        gapStart = null;
      }
      previous = bucket;
    }
    if (gapStart != null) {
      gaps.add(new Gap(gapStart, previous.plus(step)));
    }
    return gaps;
  }

  /** Splits a gap into ≤ 60-day fetch pages (Kite paging bound — depth is policy, A3). */
  public static List<Gap> pages(Gap gap) {
    List<Gap> pages = new ArrayList<>();
    OffsetDateTime cursor = gap.from();
    while (cursor.isBefore(gap.to())) {
      OffsetDateTime end = cursor.plus(MAX_PAGE);
      if (end.isAfter(gap.to())) {
        end = gap.to();
      }
      pages.add(new Gap(cursor, end));
      cursor = end;
    }
    return pages;
  }
}
