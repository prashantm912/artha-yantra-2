package in.arthayantra.strategysignal.paper;

import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.eval.PositionSizer;
import in.arthayantra.strategysignal.paper.InstrumentMetaClient.InstrumentMeta;
import in.arthayantra.strategysignal.signals.EmissionGuard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

/**
 * The paper-module adapter for the signals {@link EmissionGuard} SPI (A12). Bridges the engine's
 * ENTRY emission to the global risk gate and the paper-account-equity sizing — the engine never
 * imports paper, only this port (so the module graph stays acyclic).
 */
@Component
public class PaperEmissionGuard implements EmissionGuard {

  // §3.7 hero-zero profit-funded sizing: deploy ~10% of accumulated realised PROFIT (mode a, "play with
  // house money, never capital"), floored to a ₹2,500 minimum deploy (mode b — the ₹2-3k midpoint) when
  // profits are thin / negative (owner: "a if we have enough profit, else b").
  static final BigDecimal HERO_ZERO_PROFIT_PCT = new BigDecimal("0.10");
  static final BigDecimal HERO_ZERO_MIN_DEPLOY_INR = new BigDecimal("2500");

  private final RiskService risk;
  private final PaperAccountService account;
  private final InstrumentMetaClient instruments;
  private final ScalperAccountModel scalperAccounts;

  /** Wires the risk gate + capital model + the scalper 5-account discipline. */
  public PaperEmissionGuard(
      RiskService risk,
      PaperAccountService account,
      InstrumentMetaClient instruments,
      ScalperAccountModel scalperAccounts) {
    this.risk = risk;
    this.account = account;
    this.instruments = instruments;
    this.scalperAccounts = scalperAccounts;
  }

  @Override
  public boolean entryAllowed(String book) {
    return risk.entryAllowed(book);
  }

  @Override
  public boolean scalperEntryAllowed() {
    return scalperAccounts.scalperEntryAllowed();
  }

  @Override
  public BigDecimal suggestedQty(
      StrategyDefinition.SizingSpec sizing,
      String exchange,
      String tradingsymbol,
      BigDecimal price,
      BigDecimal stopDistance,
      String book) {
    return suggestedQty(sizing, exchange, tradingsymbol, price, stopDistance, null, book);
  }

  @Override
  public BigDecimal suggestedQty(
      StrategyDefinition.SizingSpec sizing,
      String exchange,
      String tradingsymbol,
      BigDecimal price,
      BigDecimal stopDistance,
      BigDecimal multiplier,
      String book) {
    InstrumentMeta meta = instruments.meta(exchange, tradingsymbol);
    long lot = Math.max(1, meta.lotSize());
    long base =
        PositionSizer.size(
            sizing, new PositionSizer.Inputs(account.equity(book), price, stopDistance, meta.lotSize()));
    if (base <= 0) {
      return null;
    }
    if (multiplier == null) {
      return BigDecimal.valueOf(base);
    }
    // E8 §3.2: scale the advisory qty by the graded multiplier, then floor to a WHOLE lot (never round
    // UP, never below one lot for a fired entry). PaperEmissionGuard is the one rounding authority.
    long lots =
        Math.max(
            1L,
            BigDecimal.valueOf(base)
                .multiply(multiplier)
                .divideToIntegralValue(BigDecimal.valueOf(lot))
                .longValueExact());
    return BigDecimal.valueOf(lots * lot);
  }

  @Override
  public BigDecimal heroZeroSuggestedQty(String exchange, String tradingsymbol, BigDecimal premium) {
    if (premium == null || premium.signum() <= 0) {
      return null;
    }
    InstrumentMeta meta = instruments.meta(exchange, tradingsymbol);
    long lot = Math.max(1, meta.lotSize());
    // Hero-zero is a scalper (expiry-day options) concept — funded off the scalper book's realised P&L.
    BigDecimal budget = heroZeroDeployBudget(account.realisedProfit(BookResolver.SCALPER));
    BigDecimal perLotCost = premium.multiply(BigDecimal.valueOf(lot));
    long affordableLots = budget.divide(perLotCost, 0, RoundingMode.DOWN).longValueExact();
    long lots = Math.max(1L, affordableLots); // a fired entry deploys at least one lot (advisory)
    return BigDecimal.valueOf(lots * lot);
  }

  /**
   * §3.7 the hero-zero deploy budget (INR): mode a = 10% of accumulated realised PROFIT when there is
   * enough profit (10% &gt; the floor), mode b = the {@link #HERO_ZERO_MIN_DEPLOY_INR} floor when profits
   * are thin / negative. Package-private + static for a focused unit test.
   */
  static BigDecimal heroZeroDeployBudget(BigDecimal realisedProfit) {
    BigDecimal tenPct =
        realisedProfit == null ? BigDecimal.ZERO : realisedProfit.multiply(HERO_ZERO_PROFIT_PCT);
    return tenPct.compareTo(HERO_ZERO_MIN_DEPLOY_INR) > 0 ? tenPct : HERO_ZERO_MIN_DEPLOY_INR;
  }
}
