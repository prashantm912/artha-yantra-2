package in.arthayantra.strategysignal.paper;

import in.arthayantra.strategysignal.registry.StrategyRepository;
import in.arthayantra.strategysignal.registry.StrategyRepository.StrategyRow;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * F7 graduation framework — MEASUREMENT ONLY. For every published+enabled strategy (the same live set
 * {@code SignalEngine.reload()} loads) it aggregates that strategy's CLOSED paper positions —
 * attributed SAME-schema via {@code paper_orders.signal_id → signals → strategy_versions} (the exact
 * join {@link PaperPositionRepository#intradayOpen()} uses) — into trades / net-realized / win-rate /
 * cost-adjusted profit-factor / expectancy / max-drawdown, scores each against configurable thresholds
 * ({@code artha.graduation.*}) and derives a {@code PAPER} vs {@code TAKE_ELIGIBLE} stage.
 *
 * <p>It NEVER promotes, arms, or places anything — promotion stays an owner action. {@code realized_pnl}
 * is ALREADY net-of-costs (F8 / Phase 43), so profit-factor and expectancy are cost-adjusted by
 * construction and are never re-adjusted here.
 */
@Service
public class GraduationService {

  private final JdbcTemplate jdbc;
  private final StrategyRepository strategies;

  private final int minTrades;
  private final BigDecimal minProfitFactor;
  private final BigDecimal minExpectancy;
  private final BigDecimal maxDrawdownPct;
  private final BigDecimal baseCapital;

  /** Wires the strategy datasource + registry and the graduation thresholds. */
  public GraduationService(
      JdbcTemplate jdbc,
      StrategyRepository strategies,
      @Value("${artha.graduation.min-trades:20}") int minTrades,
      @Value("${artha.graduation.min-profit-factor:1.3}") BigDecimal minProfitFactor,
      @Value("${artha.graduation.min-expectancy:0}") BigDecimal minExpectancy,
      @Value("${artha.graduation.max-drawdown-pct:25}") BigDecimal maxDrawdownPct,
      @Value("${artha.graduation.base-capital:100000}") BigDecimal baseCapital) {
    this.jdbc = jdbc;
    this.strategies = strategies;
    this.minTrades = minTrades;
    this.minProfitFactor = minProfitFactor;
    this.minExpectancy = minExpectancy;
    this.maxDrawdownPct = maxDrawdownPct;
    this.baseCapital = baseCapital;
  }

  /** The thresholds this service scored against (echoed to the UI so the board is self-describing). */
  public record Thresholds(
      int minTrades,
      BigDecimal minProfitFactor,
      BigDecimal minExpectancy,
      BigDecimal maxDrawdownPct) {}

  /** One scored criterion: its name, the required bound (human string), the actual value, pass/fail. */
  public record Criterion(String name, String required, String actual, boolean pass) {}

  /** One strategy's computed readiness metrics, its criteria breakdown and derived stage. */
  public record StrategyGraduation(
      UUID strategyId,
      String slug,
      String name,
      String stage,
      int trades,
      BigDecimal netRealized,
      BigDecimal winRate,
      BigDecimal profitFactor,
      BigDecimal expectancy,
      BigDecimal maxDrawdownPct,
      List<Criterion> criteria) {}

  /** The whole board: one row per live strategy + the thresholds used. */
  public record GraduationBoard(
      List<StrategyGraduation> strategies,
      Thresholds thresholds,
      java.time.OffsetDateTime asOf) {}

  /** The thresholds in force (for the controller's typed response). */
  public Thresholds thresholds() {
    return new Thresholds(minTrades, minProfitFactor, minExpectancy, maxDrawdownPct);
  }

  /** Scores every published+enabled strategy against the thresholds. Read-only — no side effects. */
  public GraduationBoard board() {
    List<StrategyGraduation> rows = new ArrayList<>();
    for (StrategyRow s : strategies.listAll()) {
      if (!s.enabled() || s.publishedVersionId() == null) {
        continue; // the live set — same filter as SignalEngine.reload()
      }
      rows.add(score(s));
    }
    return new GraduationBoard(rows, thresholds(), java.time.OffsetDateTime.now());
  }

  /**
   * A strategy's CLOSED-trade realized-P&L series, oldest-first (the drawdown-walk order). SAME-schema
   * join: {@code paper_orders} carries the signal_id (there is no {@code paper_positions.signal_id}),
   * signals carries the version, strategy_versions carries the strategy — DISTINCT because an averaged
   * add can link a position to several signal-carrying orders of the same strategy. Package-visible so
   * the F7 promotion evaluator can compute its own risk-adjusted stats off the same series.
   */
  List<BigDecimal> closedPnls(UUID strategyId) {
    return jdbc.query(
        """
        SELECT p.realized_pnl AS pnl
        FROM (
          SELECT DISTINCT p.id, p.realized_pnl, p.closed_at
          FROM paper_positions p
          JOIN paper_orders o
            ON o.exchange = p.exchange AND o.tradingsymbol = p.tradingsymbol AND o.side = p.side
            AND o.signal_id IS NOT NULL
          JOIN signals sig ON sig.id = o.signal_id
          JOIN strategy_versions sv ON sv.id = sig.strategy_version_id
          WHERE p.status = 'CLOSED' AND sv.strategy_id = ?
        ) p
        ORDER BY p.closed_at ASC, p.id ASC
        """,
        (rs, n) -> rs.getBigDecimal("pnl"),
        strategyId);
  }

  /** The fixed notional the drawdown % is measured against ({@code artha.graduation.base-capital}). */
  public BigDecimal baseCapital() {
    return baseCapital;
  }

  /** Aggregates one strategy's CLOSED paper positions and scores them. */
  private StrategyGraduation score(StrategyRow s) {
    Metrics m = metrics(closedPnls(s.id()), baseCapital);
    List<Criterion> criteria = criteria(m);
    boolean allPass = criteria.stream().allMatch(Criterion::pass);
    String stage = allPass ? "TAKE_ELIGIBLE" : "PAPER";
    return new StrategyGraduation(
        s.id(),
        s.slug(),
        s.name(),
        stage,
        m.trades(),
        m.netRealized(),
        m.winRate(),
        m.profitFactor(),
        m.expectancy(),
        m.maxDrawdownPct(),
        criteria);
  }

  /** Builds the four scored criteria from computed metrics (order fixed for a stable UI). */
  private List<Criterion> criteria(Metrics m) {
    List<Criterion> out = new ArrayList<>();
    out.add(
        new Criterion(
            "trades", ">= " + minTrades, String.valueOf(m.trades()), m.trades() >= minTrades));
    boolean pfPass = m.profitFactor() != null && m.profitFactor().compareTo(minProfitFactor) >= 0;
    out.add(
        new Criterion(
            "profitFactor",
            ">= " + minProfitFactor.toPlainString(),
            m.profitFactor() == null ? "n/a" : m.profitFactor().toPlainString(),
            pfPass));
    out.add(
        new Criterion(
            "expectancy",
            "> " + minExpectancy.toPlainString(),
            m.expectancy().toPlainString(),
            m.expectancy().compareTo(minExpectancy) > 0));
    out.add(
        new Criterion(
            "maxDrawdownPct",
            "<= " + maxDrawdownPct.toPlainString(),
            m.maxDrawdownPct().toPlainString(),
            m.maxDrawdownPct().compareTo(maxDrawdownPct) <= 0));
    return out;
  }

  /** The pure computed metrics (money in {@link BigDecimal}); {@code profitFactor} null when no losses. */
  public record Metrics(
      int trades,
      BigDecimal netRealized,
      int wins,
      int losses,
      BigDecimal winRate,
      BigDecimal profitFactor,
      BigDecimal expectancy,
      BigDecimal maxDrawdownPct) {}

  private static final int MONEY_SCALE = 4;
  private static final int RATIO_SCALE = 4;

  /**
   * Pure aggregation of a CLOSED-trade realized-P&L series (oldest-first) — the graduation math,
   * factored out so it is unit-testable without a database.
   *
   * <ul>
   *   <li>{@code trades} = the series size; {@code netRealized} = Σ pnl (already net-of-costs).
   *   <li>{@code wins}/{@code losses} count pnl &gt; 0 / &lt; 0 (a flat 0 is neither); {@code winRate}
   *       = wins / trades.
   *   <li>{@code profitFactor} = Σ(pnl&gt;0) / |Σ(pnl&lt;0)| — cost-adjusted since pnl is net; NULL
   *       when there are no losing trades (an undefined ratio, not "infinite pass").
   *   <li>{@code expectancy} = netRealized / trades.
   *   <li>{@code maxDrawdownPct} = the max peak-to-trough drop of the running cumulative-realized-P&L
   *       equity curve (equity starts at {@code baseCapital} and accrues each trade's pnl), expressed
   *       as a % of the running PEAK equity. Documented base = {@code artha.graduation.base-capital}
   *       (default 100000) — a fixed notional so the % is comparable across strategies and never
   *       divides by a near-zero cumulative-P&L peak. 0 when the curve only ever rises.
   * </ul>
   */
  public static Metrics metrics(List<BigDecimal> pnls, BigDecimal baseCapital) {
    int trades = pnls.size();
    if (trades == 0) {
      return new Metrics(
          0, BigDecimal.ZERO, 0, 0, null, null, BigDecimal.ZERO, BigDecimal.ZERO);
    }
    BigDecimal net = BigDecimal.ZERO;
    BigDecimal grossProfit = BigDecimal.ZERO;
    BigDecimal grossLoss = BigDecimal.ZERO; // magnitude (positive)
    int wins = 0;
    int losses = 0;
    BigDecimal equity = baseCapital;
    BigDecimal peak = baseCapital;
    BigDecimal maxDrawdownFrac = BigDecimal.ZERO; // fraction, e.g. 0.1234 = 12.34%
    for (BigDecimal pnl : pnls) {
      BigDecimal p = pnl == null ? BigDecimal.ZERO : pnl;
      net = net.add(p);
      int sign = p.signum();
      if (sign > 0) {
        wins++;
        grossProfit = grossProfit.add(p);
      } else if (sign < 0) {
        losses++;
        grossLoss = grossLoss.add(p.abs());
      }
      equity = equity.add(p);
      if (equity.compareTo(peak) > 0) {
        peak = equity;
      } else if (peak.signum() > 0) {
        BigDecimal ddFrac = peak.subtract(equity).divide(peak, MathContext.DECIMAL64);
        if (ddFrac.compareTo(maxDrawdownFrac) > 0) {
          maxDrawdownFrac = ddFrac;
        }
      }
    }
    BigDecimal winRate =
        BigDecimal.valueOf(wins).divide(BigDecimal.valueOf(trades), RATIO_SCALE, RoundingMode.HALF_UP);
    BigDecimal profitFactor =
        losses == 0 ? null : grossProfit.divide(grossLoss, RATIO_SCALE, RoundingMode.HALF_UP);
    BigDecimal expectancy =
        net.divide(BigDecimal.valueOf(trades), MONEY_SCALE, RoundingMode.HALF_UP);
    BigDecimal maxDrawdownPct =
        maxDrawdownFrac.multiply(BigDecimal.valueOf(100)).setScale(RATIO_SCALE, RoundingMode.HALF_UP);
    return new Metrics(
        trades,
        net.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
        wins,
        losses,
        winRate,
        profitFactor,
        expectancy,
        maxDrawdownPct);
  }
}
