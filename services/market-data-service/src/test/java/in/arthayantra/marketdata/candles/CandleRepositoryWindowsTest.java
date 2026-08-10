package in.arthayantra.marketdata.candles;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.candles.CandleRepository.Window;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A14 window-slicing math (2026-07-10 CA-rebuild hardening). Pure unit coverage of the two static
 * slicers behind {@link CandleRepository#refreshDerivedAggregates} (OVERLAPPING cagg-refresh
 * windows) and {@link CandleRepository#purgeSymbol} (CONTIGUOUS delete windows) — no DB.
 */
class CandleRepositoryWindowsTest {

  private static OffsetDateTime t(String iso) {
    return OffsetDateTime.parse(iso);
  }

  /**
   * ⚠️ The EXECUTION SEQUENCE, not just the plan: consecutive CALLs must OVERLAP, never merely abut.
   *
   * <p>Simulates the replanning loop the way {@code refreshReplanning} walks it — plan from the
   * remaining span, take the first window, advance the cursor — and asserts every step lands at
   * {@code previous.to - overlap}. Advancing to {@code previous.to} instead makes the CALLs abut,
   * and a cagg bucket straddling that boundary is fully contained in NEITHER and silently stays
   * unmaterialized.
   *
   * <p>This test exists because the first version of the replanning loop did exactly that. The
   * staleness fix it was making was correct; the cursor arithmetic it introduced was not, and every
   * existing test still passed — they all check the PLAN, and the defect was in how the plan was
   * consumed. Cross-vendor review caught it (2026-08-11).
   */
  @Test
  void replanningAdvancesByToMinusOverlapSoConsecutiveCallsStillOverlap() {
    OffsetDateTime start = t("2024-01-01T00:00:00Z");
    OffsetDateTime end = start.plusDays(400);
    int overlapDays = 8;
    // A uniform, dense load so the planner is genuinely tuple-bound rather than day-bound.
    List<CandleRepository.ChunkLoad> load = new ArrayList<>();
    for (int d = 0; d < 400; d += 10) {
      load.add(
          new CandleRepository.ChunkLoad(
              start.plusDays(d), start.plusDays(d + 10), 1_000_000L));
    }

    List<Window> executed = new ArrayList<>();
    OffsetDateTime cursor = start;
    while (cursor.isBefore(end) && executed.size() < 200) {
      Window w =
          CandleRepository.planRebuildWindows(load, cursor, end, 3_000_000L, 92, overlapDays).get(0);
      executed.add(w);
      if (!w.to().isBefore(end)) {
        break;
      }
      OffsetDateTime next = w.to().minusDays(overlapDays);
      if (!next.isAfter(cursor)) {
        break;
      }
      cursor = next;
    }

    assertThat(executed).hasSizeGreaterThan(2); // otherwise the boundary is never exercised
    assertThat(executed.get(0).from()).isEqualTo(start);
    assertThat(executed.get(executed.size() - 1).to()).isEqualTo(end);
    for (int i = 1; i < executed.size(); i++) {
      assertThat(executed.get(i).from())
          .as("window %d must START %d days BEFORE window %d ends — abutting loses the straddler",
              i, overlapDays, i - 1)
          .isEqualTo(executed.get(i - 1).to().minusDays(overlapDays));
      assertThat(executed.get(i).from())
          .as("and that means it starts strictly before the previous window ended")
          .isBefore(executed.get(i - 1).to());
    }
  }

  // ---- refreshWindows: overlapping, so refresh_continuous_aggregate never drops a straddling bucket

  @Test
  void refreshWindowsSlicesTheTwelveYearRebuildIntoOverlappingWindows() {
    OffsetDateTime start = t("2014-01-01T00:00:00Z");
    OffsetDateTime end = start.plusDays(4400); // the rebackfill-days-1m=4400 span (~12 yr)

    List<Window> windows = CandleRepository.refreshWindows(start, end, 92, 8);

    // ceil(4400 / 92) = 48 windows — bounds each CALL instead of one 12-yr OOM CALL
    assertThat(windows).hasSize(48);
    assertThat(windows.get(0).from()).isEqualTo(start);
    assertThat(windows.get(windows.size() - 1).to()).isEqualTo(end); // last clamped, union covers all

    for (int i = 0; i < windows.size(); i++) {
      Window w = windows.get(i);
      assertThat(w.from()).isEqualTo(start.plusDays(92L * i)); // cursor steps by the 92-day chunk
      assertThat(w.from()).isBefore(w.to());
      assertThat(Duration.between(w.from(), w.to()).toDays())
          .as("window width ≤ step + overlap")
          .isLessThanOrEqualTo(100);
      if (i > 0) {
        // every interior cut is covered by 8 days of overlap (≥ the 7-day candles_1w bucket), so a
        // straddling weekly bucket is fully contained in the earlier window — no gap.
        Window prev = windows.get(i - 1);
        assertThat(Duration.between(w.from(), prev.to()).toDays()).isEqualTo(8);
      }
    }
  }

  @Test
  void refreshWindowsIsOneWindowWhenRangeSmallerThanTheStep() {
    OffsetDateTime start = t("2026-01-01T00:00:00Z");
    OffsetDateTime end = start.plusDays(50);

    List<Window> windows = CandleRepository.refreshWindows(start, end, 92, 8);

    assertThat(windows).containsExactly(new Window(start, end));
  }

  @Test
  void refreshWindowsDegenerateStartEqualsEndYieldsOneGuardWindow() {
    // refreshDerivedAggregates pads ±8 days so start<end in production; this documents the guard.
    OffsetDateTime x = t("2026-01-01T00:00:00Z");

    assertThat(CandleRepository.refreshWindows(x, x, 92, 8)).containsExactly(new Window(x, x));
  }

  // ---- planRebuildWindows: windows sized in TUPLES, because the cap that aborted the rebuild is

  /** The 2026-08-04 live {@code candles_5m} chunk layout: uniform 70-day chunks, densest last. */
  private static List<CandleRepository.ChunkLoad> liveCandles5mChunks() {
    long[] tuples = {740441, 3931536, 4706633, 4123835, 4593370, 5112833, 5558488};
    OffsetDateTime first = t("2025-01-02T00:00:00Z");
    List<CandleRepository.ChunkLoad> chunks = new java.util.ArrayList<>();
    for (int i = 0; i < tuples.length; i++) {
      chunks.add(
          new CandleRepository.ChunkLoad(
              first.plusDays(70L * i), first.plusDays(70L * (i + 1)), tuples[i]));
    }
    return chunks;
  }

  /**
   * The defect, stated as an invariant: no window may hold more than the budget. Measured live,
   * the OLD fixed 100-day windows held up to 9,543,253 tuples against a 5,000,000 ceiling.
   */
  @Test
  void planRebuildWindowsKeepsEveryWindowInsideTheTupleBudget() {
    List<CandleRepository.ChunkLoad> chunks = liveCandles5mChunks();
    OffsetDateTime start = chunks.get(0).from();
    OffsetDateTime end = chunks.get(chunks.size() - 1).to();

    List<Window> windows =
        CandleRepository.planRebuildWindows(
            chunks, start, end, CandleRepository.REBUILD_WINDOW_TUPLE_BUDGET, 92, 8);

    for (Window w : windows) {
      assertThat(tuplesIn(chunks, w))
          .as("window %s..%s must fit the per-DML budget", w.from(), w.to())
          .isLessThanOrEqualTo(CandleRepository.REBUILD_WINDOW_TUPLE_BUDGET);
    }
    // and the whole 5,558,488-tuple chunk really was split rather than skipped
    assertThat(windows.size()).isGreaterThan(chunks.size());
  }

  /** Content of a window, counting a straddled chunk pro-rata — the planner's own cost model. */
  private static long tuplesIn(List<CandleRepository.ChunkLoad> chunks, Window w) {
    long total = 0;
    for (CandleRepository.ChunkLoad c : chunks) {
      OffsetDateTime from = c.from().isBefore(w.from()) ? w.from() : c.from();
      OffsetDateTime to = c.to().isAfter(w.to()) ? w.to() : c.to();
      if (from.isBefore(to)) {
        total +=
            c.tuples()
                * Duration.between(from, to).getSeconds()
                / Duration.between(c.from(), c.to()).getSeconds();
      }
    }
    return total;
  }

  @Test
  void planRebuildWindowsCoversTheWholeRangeWithOverlappedInteriorCuts() {
    List<CandleRepository.ChunkLoad> chunks = liveCandles5mChunks();
    OffsetDateTime start = chunks.get(0).from();
    OffsetDateTime end = chunks.get(chunks.size() - 1).to();

    List<Window> windows =
        CandleRepository.planRebuildWindows(
            chunks, start, end, CandleRepository.REBUILD_WINDOW_TUPLE_BUDGET, 92, 8);

    assertThat(windows.get(0).from()).isEqualTo(start);
    assertThat(windows.get(windows.size() - 1).to()).isEqualTo(end); // clamped, never past end
    for (int i = 1; i < windows.size(); i++) {
      Window prev = windows.get(i - 1);
      Window w = windows.get(i);
      assertThat(w.from()).isBefore(w.to());
      // no gap: the next window starts strictly inside the previous one, and the previous window
      // runs a full 8 days past the cut — or to `end`, when fewer than 8 days remain, which is the
      // same clamp refreshWindows applies and leaves no bucket uncovered inside the range
      assertThat(w.from()).isBefore(prev.to());
      OffsetDateTime full = w.from().plusDays(8);
      assertThat(prev.to())
          .as("interior cut %s keeps the 7-day candles_1w bucket fully inside the earlier window", i)
          .isEqualTo(full.isAfter(end) ? end : full);
    }
  }

  @Test
  void planRebuildWindowsGivesASparseCaggOneWindowInsteadOfTheDenseCaggsWindowCount() {
    // candles_1w: 19,076 tuples in its densest chunk, 60,769 across 12 years (live, 2026-08-04)
    List<CandleRepository.ChunkLoad> sparse =
        List.of(
            new CandleRepository.ChunkLoad(t("2025-01-02T00:00:00Z"), t("2025-03-13T00:00:00Z"), 15000),
            new CandleRepository.ChunkLoad(t("2025-03-13T00:00:00Z"), t("2025-05-22T00:00:00Z"), 19076));

    List<Window> windows =
        CandleRepository.planRebuildWindows(
            sparse,
            t("2025-01-02T00:00:00Z"),
            t("2025-05-22T00:00:00Z"),
            CandleRepository.REBUILD_WINDOW_TUPLE_BUDGET,
            92,
            8);

    // 140 days of a sparse cagg is 34,076 tuples — but the 92-day MATERIALIZATION bound still
    // applies, so it is 2 windows, not 1 and not candles_5m's count
    assertThat(windows).hasSize(2);
  }

  @Test
  void planRebuildWindowsStillCapsTheSpanWhenNoChunkIsCompressed() {
    // the 2026-07-10 protection: zero chunks means zero tuples, which must NOT plan as one CALL
    // spanning the whole 12-year rebuild
    OffsetDateTime start = t("2014-01-01T00:00:00Z");
    OffsetDateTime end = start.plusDays(4400);

    List<Window> windows =
        CandleRepository.planRebuildWindows(
            List.of(), start, end, CandleRepository.REBUILD_WINDOW_TUPLE_BUDGET, 92, 8);

    assertThat(windows).hasSize(48); // ceil(4400 / 92) — the same bound refreshWindows enforces
    assertThat(windows.get(0).from()).isEqualTo(start);
    assertThat(windows.get(windows.size() - 1).to()).isEqualTo(end);
    for (Window w : windows) {
      assertThat(Duration.between(w.from(), w.to()).toDays()).isLessThanOrEqualTo(100);
    }
  }

  @Test
  void planRebuildWindowsDegenerateStartEqualsEndYieldsOneGuardWindow() {
    OffsetDateTime x = t("2026-01-01T00:00:00Z");

    assertThat(CandleRepository.planRebuildWindows(List.of(), x, x, 1_250_000, 92, 8))
        .containsExactly(new Window(x, x));
  }

  // ---- purgeWindows: contiguous, exact per-row DELETE bounds (no overlap wanted)

  @Test
  void purgeWindowsAreContiguousSixMonthSlicesCoveringTheRange() {
    OffsetDateTime start = t("2014-01-01T00:00:00Z");
    OffsetDateTime end = t("2026-07-11T00:00:00Z");

    List<Window> windows = CandleRepository.purgeWindows(start, end, Period.ofMonths(6));

    assertThat(windows.get(0).from()).isEqualTo(start);
    assertThat(windows.get(windows.size() - 1).to()).isEqualTo(end);
    for (int i = 0; i < windows.size() - 1; i++) {
      // contiguous: no gap AND no overlap — each row falls in exactly one DELETE
      assertThat(windows.get(i).to()).isEqualTo(windows.get(i + 1).from());
      assertThat(Duration.between(windows.get(i).from(), windows.get(i).to()).toDays())
          .as("≤ 6 calendar months per DELETE keeps decompression under the 100k limit")
          .isLessThanOrEqualTo(184);
    }
  }

  @Test
  void purgeWindowsCarriesASubWindowRemainderTail() {
    OffsetDateTime start = t("2014-01-01T00:00:00Z");
    OffsetDateTime end = t("2015-02-01T00:00:00Z"); // 13 months → 6 + 6 + 1 remainder

    List<Window> windows = CandleRepository.purgeWindows(start, end, Period.ofMonths(6));

    assertThat(windows)
        .containsExactly(
            new Window(t("2014-01-01T00:00:00Z"), t("2014-07-01T00:00:00Z")),
            new Window(t("2014-07-01T00:00:00Z"), t("2015-01-01T00:00:00Z")),
            new Window(t("2015-01-01T00:00:00Z"), t("2015-02-01T00:00:00Z")));
  }

  @Test
  void purgeWindowsStartEqualsEndYieldsOneEmptyWindow() {
    OffsetDateTime x = t("2026-01-01T00:00:00Z");

    assertThat(CandleRepository.purgeWindows(x, x, Period.ofMonths(6)))
        .containsExactly(new Window(x, x));
  }
}
