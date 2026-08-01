package in.arthayantra.marketdata.screener;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * One parameterized deep-sim run's result, shared by both families (Manas Arora / Minervini) as the
 * DEEP_SWING job wire shape (research-fidelity audit P0-3). The headline portfolio metrics map from
 * the report's RS-priority NET portfolio ({@code portfolioRsPriorityNet}) — the realistic-live
 * estimate the swing doctrine is judged on; {@code report} carries the FULL per-variant
 * {@code ManasAroraBacktestService.Report} / {@code MinerviniBacktestService.Report} verbatim (as
 * JSON), so the backtest-service worker persists it intact in the run's metrics JSONB and nothing
 * existing is lost. {@code capital} is the sim's normalized book (the run's initial-equity basis).
 */
public record DeepSwingRunResult(
    String family,
    String variant,
    LocalDate fromDate,
    String runAt,
    int symbolsScanned,
    @Schema(type = "string") BigDecimal capital,
    @Schema(type = "string") BigDecimal totalReturnPct,
    @Schema(type = "string") BigDecimal cagrPct,
    @Schema(type = "string") BigDecimal maxDrawdownPct,
    @Schema(type = "string") BigDecimal sharpe,
    int tradesTaken,
    int tradesSkipped,
    @Schema(type = "string") BigDecimal winRatePct,
    @Schema(type = "string") BigDecimal profitFactor,
    JsonNode report,
    List<DeepSwingTrade> trades) {}
