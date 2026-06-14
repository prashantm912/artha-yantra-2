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

  /** Wires the risk gate + capital model. */
  public PaperEmissionGuard(
      RiskService risk, PaperAccountService account, InstrumentMetaClient instruments) {
    this.risk = risk;
    this.account = account;
    this.instruments = instruments;
  }

  @Override
  public boolean entryAllowed() {
    return risk.entryAllowed();
  }

  @Override
  public BigDecimal suggestedQty(
      StrategyDefinition.SizingSpec sizing,
      String exchange,
      String tradingsymbol,
      BigDecimal price,
      BigDecimal stopDistance) {
    InstrumentMeta meta = instruments.meta(exchange, tradingsymbol);
    long qty =
        PositionSizer.size(
            sizing, new PositionSizer.Inputs(account.equity(), price, stopDistance, meta.lotSize()));
    return qty <= 0 ? null : BigDecimal.valueOf(qty);
  }
}
