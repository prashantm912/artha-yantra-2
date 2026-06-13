package in.arthayantra.backtest.replay.folds;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.arthayantra.backtest.replay.MetricsCalculator.Metrics;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Builders for hand-crafted {@link FoldResult}s in the aggregator/degradation unit tests. */
final class FoldTestFixtures {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private FoldTestFixtures() {}

  /** A metrics object whose headline Sharpe (and {@code full().sharpe}) is {@code sharpe}. */
  static Metrics metricsWithSharpe(double sharpe) {
    BigDecimal s = BigDecimal.valueOf(sharpe).setScale(6, java.math.RoundingMode.HALF_UP);
    ObjectNode full = MAPPER.createObjectNode();
    full.put("sharpe", s.toPlainString());
    full.put("sortino", s.toPlainString());
    full.put("totalReturn", "0");
    return new Metrics(
        BigDecimal.ZERO, s, s, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, full);
  }

  /** A fold result with the given train/OOS Sharpes and OOS trade count. */
  static FoldResult fold(int index, double trainSharpe, double oosSharpe, int oosTradeCount) {
    OffsetDateTime base = OffsetDateTime.parse("2026-01-05T00:00:00+05:30");
    Fold f =
        new Fold(
            index, base, base.plusDays(5), base.plusDays(5), base.plusDays(7));
    return new FoldResult(
        f, metricsWithSharpe(trainSharpe), metricsWithSharpe(oosSharpe), oosTradeCount);
  }
}
