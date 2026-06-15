package in.arthayantra.marketdata.options.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Primitive #2: active (peak-OI) strikes + Active Strike Sentiment % (v1, tunable — D4). */
@Service
public class ActiveStrikeService {

  private final int topN;

  public ActiveStrikeService(@Value("${artha.options.active-strikes-top-n:5}") int topN) {
    this.topN = topN;
  }

  public record StrikeOiSnap(
      BigDecimal strike, long ceOi, long ceOiChange, long peOi, long peOiChange) {}

  public List<StrikeOiSnap> activeStrikes(List<StrikeOiSnap> chain) {
    return chain.stream()
        .sorted(Comparator.comparingLong((StrikeOiSnap s) -> s.ceOi() + s.peOi()).reversed())
        .limit(topN)
        .toList();
  }

  /** 100 · (ΣpeΔOI − ΣceΔOI) / Σ(ceOi+peOi) over active strikes; null when no base OI. */
  public BigDecimal sentimentPct(List<StrikeOiSnap> chain) {
    List<StrikeOiSnap> active = activeStrikes(chain);
    long bullishFlow = 0;
    long baseOi = 0;
    for (StrikeOiSnap s : active) {
      bullishFlow += s.peOiChange() - s.ceOiChange();
      baseOi += s.ceOi() + s.peOi();
    }
    if (baseOi == 0) {
      return null;
    }
    return BigDecimal.valueOf(bullishFlow)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(baseOi), 2, RoundingMode.HALF_UP);
  }
}
