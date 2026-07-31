package in.arthayantra.backtest.replay;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

/** Typed response views for the results and summary endpoints. */
public final class BacktestViews {

  private BacktestViews() {}

  /** The paged trade envelope for a completed run. */
  public record BacktestTradePage(List<BacktestTradeItem> items, int limit, int offset) {}

  /** One trade row as emitted by the results endpoint. */
  public record BacktestTradeItem(
      int seq,
      String side,
      long qty,
      String entryTs,
      BigDecimal entryPrice,
      @Schema(types = {"string", "null"}) String exitTs,
      @Schema(types = {"number", "null"}) BigDecimal exitPrice,
      BigDecimal pnl,
      BigDecimal pnlPct,
      @Schema(types = {"string", "null"}) String exitReason,
      int barsHeld,
      @Schema(types = {"string", "null"}) String touchBasis,
      @Schema(types = {"object", "null"}) JsonNode contributions,
      @Schema(types = {"string", "null"}) String exchange,
      @Schema(types = {"string", "null"}) String tradingsymbol,
      @Schema(types = {"number", "null"}) BigDecimal stopLoss,
      @Schema(types = {"number", "null"}) BigDecimal takeProfit) {}

  /** The latest-run summary envelope for requested strategy versions. */
  public record BacktestSummaryPage(List<BacktestSummaryItem> items) {}

  /** One latest-run summary row as emitted by the results summary endpoint. */
  public record BacktestSummaryItem(
      @Schema(types = {"string", "null"}) String strategyVersionId,
      String runId,
      String sharpe,
      String totalReturn,
      String maxDrawdown,
      String completedAt,
      List<String> equity) {}
}
