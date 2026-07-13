package in.arthayantra.marketdata.screener;

import in.arthayantra.marketdata.screener.minervini.geometry.DailyBar;
import java.util.List;

/** Shared fill-price selection for the two daily swing deep-sim engines. */
public final class SwingFillPrice {

  public static final String AT_CLOSE = "at_close";
  public static final String NEXT_OPEN = "next_open";

  private SwingFillPrice() {}

  /**
   * Returns the fill for a signal on bar {@code i}; {@link Double#NaN} means a next-open fill has no
   * following bar and the trade must remain unfilled.
   */
  public static double fillPrice(List<DailyBar> bars, int i, String fillTiming) {
    return switch (fillTiming) {
      case AT_CLOSE -> bars.get(i).close();
      case NEXT_OPEN -> i + 1 < bars.size() ? bars.get(i + 1).open() : Double.NaN;
      default -> throw new IllegalArgumentException("unknown swing fill timing: " + fillTiming);
    };
  }
}
