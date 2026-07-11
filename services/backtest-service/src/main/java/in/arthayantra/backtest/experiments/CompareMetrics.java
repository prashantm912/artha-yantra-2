package in.arthayantra.backtest.experiments;

/**
 * The metric cells for one run in the server-side compare matrix (audit §13 #11) — the exact set the
 * FE compare page assembles client-side today (total return / CAGR / Sharpe / Sortino / max drawdown /
 * win rate / profit factor + the benchmark-relative alpha/beta/information-ratio). Plain decimal
 * strings (precision-preserving); the benchmark trio is {@code null} on a run with no benchmark
 * coverage (those columns persist NULL, never silently zero — §D.16).
 */
public record CompareMetrics(
    String totalReturn,
    String cagr,
    String sharpe,
    String sortino,
    String maxDrawdown,
    String winRate,
    String profitFactor,
    String alpha,
    String beta,
    String informationRatio,
    int tradeCount) {}
