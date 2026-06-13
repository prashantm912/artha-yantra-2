package in.arthayantra.backtest.replay.folds;

import in.arthayantra.marketcalendar.MarketCalendar;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Expands a {@code [from, to)} backtest window into walk-forward {@link Fold folds} (guard 2, §D.4).
 * All window arithmetic counts in <b>trading days</b> via {@link MarketCalendar} — never wall-clock
 * days — so weekends and exchange holidays never shrink a fold.
 *
 * <p><b>Day-boundary convention.</b> A fold boundary is the IST start-of-day (00:00 +05:30) of a
 * trading day; a half-open {@code [boundary_a, boundary_b)} window on the candle {@code bucketStart}
 * therefore captures every session bar of the trading days in {@code [a, b)} and none of day {@code
 * b}. "Advance {@code k} trading days from boundary {@code d}" means: the start-of-day of the {@code
 * k}-th trading day strictly after {@code d} (for {@code d} itself a trading day, that is the {@code
 * k}-th forward trading day; {@code k = 0} is {@code d} aligned to the next trading day on/after it).
 *
 * <p><b>ROLLING ({@code anchored = false}).</b> Fold {@code k} trains on {@code [s_k, s_k +
 * trainDays)} and tests on {@code [trainEnd, trainEnd + testDays)}; {@code s_k} advances by {@code
 * stepDays} trading days each fold. {@code s_0} is the first trading day on/after {@code from}.
 *
 * <p><b>ANCHORED ({@code anchored = true}).</b> The train start is pinned at {@code from} for every
 * fold; the train end grows by {@code stepDays} trading days each fold (an expanding window) and the
 * test window follows immediately. Fold {@code 0} trains on {@code trainDays}; fold {@code k} trains
 * on {@code trainDays + k * stepDays}.
 *
 * <p><b>Partial tail.</b> A fold is emitted only when its full {@code testDays}-trading-day OOS
 * window fits inside {@code [from, to)} — i.e. {@code testTo <= to}. A final fold whose test window
 * would be empty or run past {@code to} is dropped rather than evaluated on a truncated window.
 *
 * <p><b>Date-aligned {@code to} contract.</b> The window end is taken at <b>date granularity</b>
 * ({@code windowEndExclusive = to.toLocalDate()}), while {@link FoldEvaluator#slice} filters candles
 * by instant on {@code [from, to)}. The two agree only when {@code to} is a day boundary — which it
 * always is for current callers ({@code BacktestRunner} emits {@code T00:00} for a date {@code to},
 * and the spec documents the backtest {@code to} as date-aligned). {@code to} is normalized to its
 * day boundary here so an intraday {@code to} can never make the date-based emission decision
 * disagree with the instant-based slice (the fold {@code testTo} bounds are always IST start-of-day,
 * so the slice is day-aligned regardless). An intraday {@code to} therefore behaves exactly as its
 * containing trading day's start-of-day — a documented, deterministic normalization rather than a
 * silent partial-day fold.
 */
@Component
public class WalkForwardExpander {

  /** Expands the window; returns folds in chronological order (possibly empty). */
  public List<Fold> expand(
      OffsetDateTime from,
      OffsetDateTime to,
      int trainDays,
      int testDays,
      int stepDays,
      boolean anchored,
      MarketCalendar cal) {
    if (trainDays <= 0 || testDays <= 0 || stepDays <= 0) {
      throw new IllegalArgumentException(
          "walk_forward needs positive train_days/test_days/step_days");
    }
    List<Fold> folds = new ArrayList<>();
    LocalDate windowStart = firstTradingDayOnOrAfter(from.toLocalDate(), cal);
    LocalDate windowEndExclusive = to.toLocalDate(); // [from, to) — 'to' day itself is excluded

    int index = 0;
    for (int fold = 0; ; fold++) {
      LocalDate trainStartDay = anchored ? windowStart : advance(windowStart, fold * stepDays, cal);
      int trainLen = anchored ? trainDays + fold * stepDays : trainDays;
      LocalDate trainEndDay = advance(trainStartDay, trainLen, cal);
      LocalDate testEndDay = advance(trainEndDay, testDays, cal);

      // Drop the partial tail: the OOS window must fit fully inside [from, to).
      if (!testEndDay.isBefore(windowEndExclusive) && !testEndDay.isEqual(windowEndExclusive)) {
        break;
      }
      // Train window must itself be inside the data window (guards anchored growth too).
      if (!trainEndDay.isBefore(windowEndExclusive) && !trainEndDay.isEqual(windowEndExclusive)) {
        break;
      }
      folds.add(
          new Fold(
              index++,
              startOfDay(trainStartDay),
              startOfDay(trainEndDay),
              startOfDay(trainEndDay),
              startOfDay(testEndDay)));

      // Safety stop: rolling train start past the data end can never yield another full fold.
      if (!anchored && !advance(trainStartDay, stepDays, cal).isBefore(windowEndExclusive)) {
        break;
      }
    }
    return folds;
  }

  private static LocalDate firstTradingDayOnOrAfter(LocalDate date, MarketCalendar cal) {
    LocalDate d = date;
    while (!cal.isTradingDay(d)) {
      d = d.plusDays(1);
    }
    return d;
  }

  /**
   * The IST start-of-day of the {@code count}-th trading day strictly after the first trading day
   * on/after {@code start}. {@code count == 0} returns the first trading day on/after {@code start}.
   */
  private static LocalDate advance(LocalDate start, int count, MarketCalendar cal) {
    LocalDate d = firstTradingDayOnOrAfter(start, cal);
    for (int i = 0; i < count; i++) {
      d = cal.nextTradingDay(d);
    }
    return d;
  }

  private static OffsetDateTime startOfDay(LocalDate day) {
    return day.atStartOfDay().atOffset(ZoneOffset.ofHoursMinutes(5, 30));
  }
}
