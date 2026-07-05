package in.arthayantra.strategysignal.paper;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategysignal.notifier.NotifierClient;
import in.arthayantra.strategysignal.paper.GraduationService.StrategyGraduation;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * F7 auto-promotion: the daily evaluator that marks a published strategy GRADUATED once its CLOSED
 * paper trades clear a stricter bar than {@code TAKE_ELIGIBLE} — {@code ≥ promotion-min-trades}
 * closed trades AND positive (net, cost-adjusted) expectancy AND a per-trade Sharpe {@code ≥
 * promotion-min-sharpe} AND max-drawdown no worse than {@code promotion-max-drawdown-pct}. Promotion
 * is a MEASUREMENT stage + one ntfy on the FIRST graduation — it NEVER arms, rewrites gate config, or
 * places a live order (owner ruling: no live signal-gating change; the owner decides any real live
 * step). Reuses {@link GraduationService} for the same live strategy set + net-of-cost metrics and
 * adds the risk-adjusted Sharpe. Gated by the scheduler behind {@code artha.graduation.promotion-enabled}.
 */
@Service
public class GraduationPromotionService {

  private static final Logger log = LoggerFactory.getLogger(GraduationPromotionService.class);

  private final GraduationService graduation;
  private final StrategyGraduationRepository repo;
  private final NotifierClient notifier;
  private final ObjectMapper objectMapper;

  private final int minTrades;
  private final BigDecimal minSharpe;
  private final BigDecimal minExpectancy;
  private final BigDecimal maxDrawdownPct;

  /** Wires the graduation source + the marker store + the notifier and the promotion thresholds. */
  public GraduationPromotionService(
      GraduationService graduation,
      StrategyGraduationRepository repo,
      NotifierClient notifier,
      ObjectMapper objectMapper,
      @Value("${artha.graduation.promotion-min-trades:50}") int minTrades,
      @Value("${artha.graduation.promotion-min-sharpe:0.5}") BigDecimal minSharpe,
      @Value("${artha.graduation.promotion-min-expectancy:0}") BigDecimal minExpectancy,
      @Value("${artha.graduation.promotion-max-drawdown-pct:25}") BigDecimal maxDrawdownPct) {
    this.graduation = graduation;
    this.repo = repo;
    this.notifier = notifier;
    this.objectMapper = objectMapper;
    this.minTrades = minTrades;
    this.minSharpe = minSharpe;
    this.minExpectancy = minExpectancy;
    this.maxDrawdownPct = maxDrawdownPct;
  }

  /** The outcome of one evaluation pass (for logging + the endpoint). */
  public record PromotionResult(int evaluated, int graduated, List<String> newlyGraduated) {}

  /**
   * Evaluates every live strategy and marks the qualifiers GRADUATED (idempotent — re-stamps the
   * metrics of an already-graduated strategy but ntfy-alerts only a NEW one).
   */
  public PromotionResult evaluate() {
    List<StrategyGraduation> board = graduation.board().strategies();
    Set<UUID> already = repo.graduatedIds();
    List<String> newly = new ArrayList<>();
    int graduated = 0;
    for (StrategyGraduation sg : board) {
      BigDecimal sharpe = sharpe(graduation.closedPnls(sg.strategyId()));
      if (!qualifies(sg, sharpe)) {
        continue;
      }
      graduated++;
      repo.upsert(
          sg.strategyId(), sg.trades(), sg.expectancy(), sharpe, sg.maxDrawdownPct(), snapshot(sg, sharpe));
      if (!already.contains(sg.strategyId())) {
        newly.add(sg.slug());
        alert(sg, sharpe);
        log.info(
            "strategy {} GRADUATED — {} trades, expectancy {}, sharpe {}, maxDD {}%",
            sg.slug(), sg.trades(), sg.expectancy().toPlainString(),
            sharpe == null ? "n/a" : sharpe.toPlainString(), sg.maxDrawdownPct().toPlainString());
      }
    }
    return new PromotionResult(board.size(), graduated, newly);
  }

  /** The stricter GRADUATED bar (all four must hold; a null/undefined Sharpe never qualifies). */
  private boolean qualifies(StrategyGraduation sg, BigDecimal sharpe) {
    return sg.trades() >= minTrades
        && sg.expectancy().compareTo(minExpectancy) > 0
        && sharpe != null
        && sharpe.compareTo(minSharpe) >= 0
        && sg.maxDrawdownPct().compareTo(maxDrawdownPct) <= 0;
  }

  private String snapshot(StrategyGraduation sg, BigDecimal sharpe) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("trades", sg.trades());
    m.put("netRealized", sg.netRealized());
    m.put("winRate", sg.winRate());
    m.put("profitFactor", sg.profitFactor());
    m.put("expectancy", sg.expectancy());
    m.put("maxDrawdownPct", sg.maxDrawdownPct());
    m.put("sharpe", sharpe);
    try {
      return objectMapper.writeValueAsString(m);
    } catch (Exception e) {
      return "{}";
    }
  }

  private void alert(StrategyGraduation sg, BigDecimal sharpe) {
    try {
      if (notifier.configured("NTFY")) {
        notifier.send(
            "NTFY",
            "ArthaYantra — strategy GRADUATED",
            sg.name()
                + " cleared the graduation bar: "
                + sg.trades()
                + " trades, expectancy "
                + sg.expectancy().toPlainString()
                + ", Sharpe "
                + (sharpe == null ? "n/a" : sharpe.toPlainString())
                + ", maxDD "
                + sg.maxDrawdownPct().toPlainString()
                + "%. (Measurement only — no live change.)");
      }
    } catch (RuntimeException e) {
      log.warn("graduation ntfy push failed for {}: {}", sg.slug(), e.getMessage());
    }
  }

  /**
   * Per-trade Sharpe of a realized-P&L series: {@code mean / sample-stddev}. Computed in double (a
   * comparison ratio, not money), returned scaled to 4dp. {@code null} when fewer than two trades or
   * the series has zero variance (an undefined / infinite ratio, never a "pass").
   */
  static BigDecimal sharpe(List<BigDecimal> pnls) {
    int n = pnls.size();
    if (n < 2) {
      return null;
    }
    double sum = 0.0;
    for (BigDecimal p : pnls) {
      sum += p == null ? 0.0 : p.doubleValue();
    }
    double mean = sum / n;
    double sse = 0.0;
    for (BigDecimal p : pnls) {
      double d = (p == null ? 0.0 : p.doubleValue()) - mean;
      sse += d * d;
    }
    double stddev = Math.sqrt(sse / (n - 1)); // sample stddev
    if (stddev == 0.0) {
      return null;
    }
    return BigDecimal.valueOf(mean / stddev).setScale(4, RoundingMode.HALF_UP);
  }
}
