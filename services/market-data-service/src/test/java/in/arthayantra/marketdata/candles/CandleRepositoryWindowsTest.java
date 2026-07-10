package in.arthayantra.marketdata.candles;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.candles.CandleRepository.Window;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.Period;
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
