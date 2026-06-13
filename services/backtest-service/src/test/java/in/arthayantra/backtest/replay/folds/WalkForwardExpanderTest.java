package in.arthayantra.backtest.replay.folds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.arthayantra.marketcalendar.MarketCalendar;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Hand-computed fixture for {@link WalkForwardExpander}. The NSE trading days from 2026-01-05 are
 * (holidays 2026-01-15 + 2026-01-26 removed): Jan05,06,07,08,09,12,13,14,16,19,20,21,22,23,27,28,
 * 29,30. Boundaries are IST start-of-day (+05:30); windows are half-open on bucketStart.
 */
class WalkForwardExpanderTest {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private final WalkForwardExpander expander = new WalkForwardExpander();
  private final MarketCalendar cal = MarketCalendar.nse();

  private static OffsetDateTime d(String date) {
    return OffsetDateTime.parse(date + "T00:00:00+05:30");
  }

  @Test
  void rollingWindowsMatchHandComputedFixture() {
    List<Fold> folds =
        expander.expand(d("2026-01-05"), d("2026-01-30"), 5, 2, 2, false, cal);

    // 6 folds; the 7th (test window past [from,to)) is the dropped partial tail.
    assertThat(folds).hasSize(6);

    assertFold(folds.get(0), 0, "2026-01-05", "2026-01-12", "2026-01-14");
    assertFold(folds.get(1), 1, "2026-01-07", "2026-01-14", "2026-01-19");
    assertFold(folds.get(2), 2, "2026-01-09", "2026-01-19", "2026-01-21");
    assertFold(folds.get(3), 3, "2026-01-13", "2026-01-21", "2026-01-23");
    assertFold(folds.get(4), 4, "2026-01-16", "2026-01-23", "2026-01-28");
    assertFold(folds.get(5), 5, "2026-01-20", "2026-01-28", "2026-01-30");

    // step alignment: each rolling train-start advances exactly stepDays (=2) trading days.
    assertThat(folds.get(1).trainFrom()).isEqualTo(d("2026-01-07"));
    assertThat(folds.get(2).trainFrom()).isEqualTo(d("2026-01-09"));
    // testFrom == trainTo for every fold (no shared bar, no gap).
    folds.forEach(f -> assertThat(f.testFrom()).isEqualTo(f.trainTo()));
  }

  @Test
  void anchoredWindowsPinTrainStartAndGrowTrainEnd() {
    List<Fold> folds =
        expander.expand(d("2026-01-05"), d("2026-01-30"), 5, 2, 2, true, cal);

    assertThat(folds).hasSize(6);

    assertFold(folds.get(0), 0, "2026-01-05", "2026-01-12", "2026-01-14");
    assertFold(folds.get(1), 1, "2026-01-05", "2026-01-14", "2026-01-19");
    assertFold(folds.get(2), 2, "2026-01-05", "2026-01-19", "2026-01-21");
    assertFold(folds.get(3), 3, "2026-01-05", "2026-01-21", "2026-01-23");
    assertFold(folds.get(4), 4, "2026-01-05", "2026-01-23", "2026-01-28");
    assertFold(folds.get(5), 5, "2026-01-05", "2026-01-28", "2026-01-30");

    // anchored: every train window starts at 'from'; train end grows monotonically.
    folds.forEach(f -> assertThat(f.trainFrom()).isEqualTo(d("2026-01-05")));
    for (int i = 1; i < folds.size(); i++) {
      assertThat(folds.get(i).trainTo()).isAfter(folds.get(i - 1).trainTo());
    }
  }

  @Test
  void partialTailIsDroppedNotTruncated() {
    // to=2026-01-22 (exclusive): the last full 2-day OOS window that fits ends at Jan21 (fold 2,
    // covering Jan19+Jan20). Fold 3 would need test[Jan21,Jan23) — its OOS runs to Jan22, on/past
    // 'to' — so it is DROPPED, never evaluated on a truncated window.
    List<Fold> folds =
        expander.expand(d("2026-01-05"), d("2026-01-22"), 5, 2, 2, false, cal);
    assertThat(folds).hasSize(3);
    assertFold(folds.get(2), 2, "2026-01-09", "2026-01-19", "2026-01-21");
    // every emitted fold's test window ends at or before 'to' — never past it.
    folds.forEach(f -> assertThat(f.testTo()).isBeforeOrEqualTo(d("2026-01-22")));
  }

  @Test
  void rejectsNonPositiveWindowParameters() {
    assertThatThrownBy(() -> expander.expand(d("2026-01-05"), d("2026-01-30"), 0, 2, 2, false, cal))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> expander.expand(d("2026-01-05"), d("2026-01-30"), 5, 0, 2, false, cal))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> expander.expand(d("2026-01-05"), d("2026-01-30"), 5, 2, 0, false, cal))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void emptyWhenWindowTooShortForEvenOneFold() {
    // 5 train + 2 test trading days need 7 days; a 3-day window yields no full fold.
    List<Fold> folds =
        expander.expand(d("2026-01-05"), d("2026-01-08"), 5, 2, 2, false, cal);
    assertThat(folds).isEmpty();
  }

  private static void assertFold(
      Fold fold, int index, String trainFrom, String trainTo, String testTo) {
    assertThat(fold.index()).isEqualTo(index);
    assertThat(fold.trainFrom()).isEqualTo(d(trainFrom));
    assertThat(fold.trainTo()).isEqualTo(d(trainTo));
    assertThat(fold.testFrom()).isEqualTo(d(trainTo));
    assertThat(fold.testTo()).isEqualTo(d(testTo));
  }
}
