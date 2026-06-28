package in.arthayantra.strategysignal.paper;

import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.eval.PositionSizer;
import in.arthayantra.strategysignal.paper.InstrumentMetaClient.InstrumentMeta;
import in.arthayantra.strategysignal.signals.EmissionGuard;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * The paper-module adapter for the signals {@link EmissionGuard} SPI (A12). Bridges the engine's
 * ENTRY emission to the global risk gate and the paper-account-equity sizing — the engine never
 * imports paper, only this port (so the module graph stays acyclic).
 */
@Component
public class PaperEmissionGuard implements EmissionGuard {

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
  public boolean entryAllowed() {
    return risk.entryAllowed();
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
      BigDecimal stopDistance) {
    return suggestedQty(sizing, exchange, tradingsymbol, price, stopDistance, null);
  }

  @Override
  public BigDecimal suggestedQty(
      StrategyDefinition.SizingSpec sizing,
      String exchange,
      String tradingsymbol,
      BigDecimal price,
      BigDecimal stopDistance,
      BigDecimal multiplier) {
    InstrumentMeta meta = instruments.meta(exchange, tradingsymbol);
    long lot = Math.max(1, meta.lotSize());
    long base =
        PositionSizer.size(
            sizing, new PositionSizer.Inputs(account.equity(), price, stopDistance, meta.lotSize()));
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
}
