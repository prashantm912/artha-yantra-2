package in.arthayantra.backtest.replay;

import java.util.ArrayList;
import java.util.List;

/** Downsamples an equity/drawdown curve to ~target points for lightweight-charts (§D.3). */
public final class EquityCurveDownsampler {

  private EquityCurveDownsampler() {}

  /** Keeps every n-th point plus always the last, so the endpoints are exact. */
  public static <T> List<T> downsample(List<T> full, int target) {
    if (full.size() <= target || target <= 1) {
      return full;
    }
    int stride = (int) Math.ceil((double) full.size() / target);
    List<T> out = new ArrayList<>();
    for (int i = 0; i < full.size(); i += stride) {
      out.add(full.get(i));
    }
    T last = full.get(full.size() - 1);
    if (!out.get(out.size() - 1).equals(last)) {
      out.add(last);
    }
    return out;
  }
}
