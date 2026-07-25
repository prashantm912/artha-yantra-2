package in.arthayantra.backtest.experiments;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The metric cells for one run in the server-side compare matrix (audit §13 #11) — the exact set the
 * FE compare page assembles client-side today (total return / CAGR / Sharpe / Sortino / max drawdown /
 * win rate / profit factor + the benchmark-relative alpha/beta/information-ratio). Plain decimal
 * strings (precision-preserving); the benchmark trio is {@code null} on a run with no benchmark
 * coverage (those columns persist NULL, never silently zero — §D.16).
 */
public record CompareMetrics(
    @Schema(types = {"string", "null"}) String totalReturn,
    @Schema(types = {"string", "null"}) String cagr,
    @Schema(types = {"string", "null"}) String sharpe,
    @Schema(types = {"string", "null"}) String sortino,
    @Schema(types = {"string", "null"}) String maxDrawdown,
    @Schema(types = {"string", "null"}) String winRate,
    @Schema(types = {"string", "null"}) String profitFactor,
    @Schema(types = {"string", "null"}) String alpha,
    @Schema(types = {"string", "null"}) String beta,
    @Schema(types = {"string", "null"}) String informationRatio,
    int tradeCount) {}
