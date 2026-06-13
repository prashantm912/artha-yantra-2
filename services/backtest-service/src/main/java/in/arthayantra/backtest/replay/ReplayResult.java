package in.arthayantra.backtest.replay;

import in.arthayantra.strategyengine.golden.GoldenSignalsJson;
import java.math.BigDecimal;
import java.util.List;

/**
 * Everything one replay produces: the signal events (the parity surface — byte-identical to live),
 * the closed trades, the mark-to-market equity + drawdown curves, and the equity bounds + exposure
 * the metrics catalog needs.
 */
public record ReplayResult(
    List<GoldenSignalsJson.SignalEvent> signals,
    List<Trade> trades,
    List<EquityPoint> equityCurve,
    List<EquityPoint> drawdownCurve,
    BigDecimal initialEquity,
    BigDecimal finalEquity,
    long totalBars,
    long barsInPosition) {}
